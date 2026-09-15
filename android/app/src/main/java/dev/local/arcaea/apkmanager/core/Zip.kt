package dev.local.arcaea.apkmanager.core

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 极简 ZIP 读写器（由 windows/electron/core/zip.ts 忠实移植）。
 *
 * 设计目标：
 * - 读取 APK 的中央目录，只解压需要的条目（例如 songlist / 单张曲绘），避免全量解包。
 * - 导出时对**未改动**的条目做「原始字节直通拷贝」：不重新压缩，因此即便 APK 有 1GB+
 *   也能在几秒内完成重打包。
 * - 自己完成 zipalign 对齐：所有条目 4 字节对齐；未被压缩的原生库（lib 目录下的 .so）按页对齐，
 *   因此不再依赖 build-tools 里的 zipalign。
 */

private const val SIG_LFH = 0x04034b50L
private const val SIG_CD = 0x02014b50L
private const val SIG_EOCD = 0x06054b50L
private const val SIG_ZIP64_EOCD = 0x06064b50L
private const val SIG_ZIP64_LOCATOR = 0x07064b50L

/** 未压缩原生库的页对齐字节数（16KB 页设备同样兼容） */
private const val NATIVE_LIB_ALIGN = 4096
private const val DEFAULT_ALIGN = 4

/* ------------------------------- 字节级小工具 ------------------------------- */

private fun readUInt16LE(buf: ByteArray, offset: Int): Int {
    return (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)
}

private fun readUInt32LE(buf: ByteArray, offset: Int): Long {
    return (buf[offset].toLong() and 0xff) or
        ((buf[offset + 1].toLong() and 0xff) shl 8) or
        ((buf[offset + 2].toLong() and 0xff) shl 16) or
        ((buf[offset + 3].toLong() and 0xff) shl 24)
}

/** 等价于 TS 的 `hi * 0x100000000 + lo`，按位组合出无符号 64 位值 */
private fun readUInt64LE(buf: ByteArray, offset: Int): Long {
    val lo = readUInt32LE(buf, offset)
    val hi = readUInt32LE(buf, offset + 4)
    return (hi shl 32) or lo
}

private fun writeUInt16LE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xff).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

private fun writeUInt32LE(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = (value and 0xff).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xff).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xff).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

/** 等价于 Buffer.subarray：越界时自动裁剪到缓冲区范围内 */
private fun slice(buf: ByteArray, start: Int, end: Int): ByteArray {
    val s = start.coerceIn(0, buf.size)
    val e = end.coerceIn(0, buf.size)
    return if (e <= s) ByteArray(0) else buf.copyOfRange(s, e)
}

/** CRC32：返回无符号 32 位值（0..0xffffffff）放进 Long */
private fun crc32(buf: ByteArray): Long {
    val crc = CRC32()
    crc.update(buf, 0, buf.size)
    return crc.value
}

/** 原始 deflate 解压（对应 zlib.inflateRawSync） */
private fun inflateRaw(data: ByteArray): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(maxOf(data.size, 64))
        val chunk = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n > 0) {
                out.write(chunk, 0, n)
            } else if (inflater.needsInput() || inflater.needsDictionary()) {
                break
            }
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** 原始 deflate 压缩（level 9，对应 zlib.deflateRawSync(data, { level: 9 })） */
private fun deflateRaw(data: ByteArray): ByteArray {
    val deflater = Deflater(9, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(maxOf(data.size, 64))
        val chunk = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(chunk)
            if (n > 0) out.write(chunk, 0, n) else break
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

private fun isNativeLib(name: String): Boolean {
    return name.startsWith("lib/") && name.endsWith(".so")
}

/* ------------------------------- ZIP 读取 ------------------------------- */

class ZipEntryInfo(
    val name: String,
    var method: Int,
    var crc32: Long,
    var compressedSize: Long,
    var uncompressedSize: Long,
    var localHeaderOffset: Long,
    val dosTime: Int,
    val dosDate: Int,
    val externalAttrs: Long,
    val internalAttrs: Int,
    val flags: Int,
    var dataOffset: Long,
)

private fun parseZip64Extra(extra: ByteArray, entry: ZipEntryInfo) {
    var p = 0
    while (p + 4 <= extra.size) {
        val id = readUInt16LE(extra, p)
        val size = readUInt16LE(extra, p + 2)
        val start = p + 4
        if (start + size > extra.size) break
        if (id == 0x0001) {
            var q = start
            if (entry.uncompressedSize == 0xffffffffL && q + 8 <= start + size) {
                entry.uncompressedSize = readUInt64LE(extra, q)
                q += 8
            }
            if (entry.compressedSize == 0xffffffffL && q + 8 <= start + size) {
                entry.compressedSize = readUInt64LE(extra, q)
                q += 8
            }
            if (entry.localHeaderOffset == 0xffffffffL && q + 8 <= start + size) {
                entry.localHeaderOffset = readUInt64LE(extra, q)
            }
            return
        }
        p = start + size
    }
}

/** 读取 APK 的中央目录，只解压需要的条目，避免全量解包 */
class ZipReader(file: File) : Closeable {

    val entries = LinkedHashMap<String, ZipEntryInfo>()

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private val fileSize: Long = raf.length()

    @Volatile
    private var closed = false

    /**
     * 串行化对底层 RandomAccessFile 的访问。
     *
     * 背景：导出会持续 `seek+read` 底层文件；同时「歌曲列表缩略图 / 编辑页文件大小」等
     * 后台读取也会直接读同一个 `raf`。两者并发时，非线程安全的 RandomAccessFile 会把文件指针
     * 相互踩坏，轻则读到脏数据（表现为「导出时读取进程卡住」），重则在 [close] 关闭句柄的瞬间
     * 仍旧在飞的读取抛 IOException → 界面协程直接闪退（正是「清理缓存后仍闪退」的根因）。
     * 这里让所有读操作（含整条目直通拷贝）都以 [lock] 互斥，[close] 也等在锁外，
     * 从而既保证导出输出不被中途打断，也保证绝不会有一个读取正在关闭后的句柄上运行。
     */
    private val lock = Any()

    val size: Long get() = fileSize

    init {
        try {
            parseCentralDirectory()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                raf.close()
            } catch (_: Throwable) {
                /* ignore */
            }
        }
    }

    fun has(name: String): Boolean = entries.containsKey(name)

    /** 按前缀列出条目名 */
    fun list(prefix: String): List<String> {
        val out = ArrayList<String>()
        for (name in entries.keys) {
            if (name.startsWith(prefix)) out.add(name)
        }
        return out
    }

    fun listAll(): List<String> = entries.keys.toList()

    /** 读取并解压条目内容 */
    fun readFile(name: String): ByteArray? {
        val entry = entries[name] ?: return null
        val compressedSize = entry.compressedSize.toInt()
        val compressed = if (entry.compressedSize > 0) readAt(entry.dataOffset, compressedSize) else ByteArray(0)
        if (entry.method == 0) return compressed
        if (entry.method == 8) return inflateRaw(compressed)
        throw IOException("不支持的压缩方式 ${entry.method}（条目 $name）")
    }

    fun readText(name: String): String? {
        val buf = readFile(name) ?: return null
        // 去掉可能存在的 UTF-8 BOM
        var text = String(buf, Charsets.UTF_8)
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return text
    }

    /** 将某个条目的压缩数据原样拷贝到另一个输出流（直通，不解压） */
    fun copyEntryRaw(entry: ZipEntryInfo, out: OutputStream, chunkSize: Int = 4 * 1024 * 1024) {
        // 整条目拷贝必须持锁完成，否则会与另一个后台读取交错 seek，把导出数据写脏
        synchronized(lock) {
            var remaining = entry.compressedSize
            var position = entry.dataOffset
            val chunk = ByteArray(minOf(chunkSize.toLong(), maxOf(remaining, 1L)).toInt())
            while (remaining > 0) {
                val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                raf.seek(position)
                val read = raf.read(chunk, 0, toRead)
                if (read <= 0) break
                out.write(chunk, 0, read)
                position += read
                remaining -= read
            }
        }
    }

    private fun readAt(offset: Long, length: Int): ByteArray {
        synchronized(lock) {
            val buf = ByteArray(length)
            raf.seek(offset)
            var off = 0
            while (off < length) {
                val n = raf.read(buf, off, length - off)
                if (n <= 0) break
                off += n
            }
            return buf
        }
    }

    private fun parseCentralDirectory() {
        val tailLen = minOf(fileSize, (22 + 0xffff).toLong()).toInt()
        val tail = readAt(fileSize - tailLen, tailLen)

        var eocdPos = -1
        var i = tail.size - 22
        while (i >= 0) {
            if (readUInt32LE(tail, i) == SIG_EOCD) {
                eocdPos = i
                break
            }
            i--
        }
        if (eocdPos < 0) throw IOException("不是有效的 ZIP/APK 文件：未找到中央目录结束记录")

        var entryCount = readUInt16LE(tail, eocdPos + 10).toLong()
        var cdSize = readUInt32LE(tail, eocdPos + 12)
        var cdOffset = readUInt32LE(tail, eocdPos + 16)

        // ZIP64：条目数 / 偏移溢出时读取 ZIP64 结束记录
        if (cdOffset == 0xffffffffL || cdSize == 0xffffffffL || entryCount == 0xffffL) {
            val locatorAbs = fileSize - tailLen + eocdPos - 20
            if (locatorAbs >= 0) {
                val locator = readAt(locatorAbs, 20)
                if (readUInt32LE(locator, 0) == SIG_ZIP64_LOCATOR) {
                    val z64Offset = readUInt64LE(locator, 8)
                    val z64 = readAt(z64Offset, 56)
                    if (readUInt32LE(z64, 0) == SIG_ZIP64_EOCD) {
                        entryCount = readUInt64LE(z64, 32)
                        cdSize = readUInt64LE(z64, 40)
                        cdOffset = readUInt64LE(z64, 48)
                    }
                }
            }
        }

        if (cdOffset + cdSize > fileSize) {
            throw IOException("ZIP 中央目录越界，文件可能已损坏")
        }

        val cd = readAt(cdOffset, cdSize.toInt())
        var p = 0
        var idx = 0L
        while (idx < entryCount) {
            if (p + 46 > cd.size) break
            if (readUInt32LE(cd, p) != SIG_CD) break

            val flags = readUInt16LE(cd, p + 8)
            val method = readUInt16LE(cd, p + 10)
            val dosTime = readUInt16LE(cd, p + 12)
            val dosDate = readUInt16LE(cd, p + 14)
            val crc = readUInt32LE(cd, p + 16)
            val compressedSize = readUInt32LE(cd, p + 20)
            val uncompressedSize = readUInt32LE(cd, p + 24)
            val nameLen = readUInt16LE(cd, p + 28)
            val extraLen = readUInt16LE(cd, p + 30)
            val commentLen = readUInt16LE(cd, p + 32)
            val internalAttrs = readUInt16LE(cd, p + 36)
            val externalAttrs = readUInt32LE(cd, p + 38)
            val localHeaderOffset = readUInt32LE(cd, p + 42)

            val nameBuf = slice(cd, p + 46, p + 46 + nameLen)
            val name = String(nameBuf, Charsets.UTF_8)
            val extra = slice(cd, p + 46 + nameLen, p + 46 + nameLen + extraLen)

            val entry = ZipEntryInfo(
                name = name,
                method = method,
                crc32 = crc,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localHeaderOffset = localHeaderOffset,
                dosTime = dosTime,
                dosDate = dosDate,
                externalAttrs = externalAttrs,
                internalAttrs = internalAttrs,
                flags = flags,
                dataOffset = 0L,
            )
            parseZip64Extra(extra, entry)

            // 读取本地文件头以定位压缩数据起点（本地头的 name/extra 长度可能与中央目录不同）
            if (entry.localHeaderOffset + 30 <= fileSize) {
                val lfh = readAt(entry.localHeaderOffset, 30)
                if (readUInt32LE(lfh, 0) == SIG_LFH) {
                    val lNameLen = readUInt16LE(lfh, 26)
                    val lExtraLen = readUInt16LE(lfh, 28)
                    entry.dataOffset = entry.localHeaderOffset + 30 + lNameLen + lExtraLen
                } else {
                    entry.dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
                }
            }

            if (!name.endsWith("/")) entries[name] = entry
            p += 46 + nameLen + extraLen + commentLen
            idx++
        }

        if (entries.isEmpty()) {
            throw IOException("ZIP 中没有任何文件条目，无法作为 APK 使用")
        }
    }
}

/* ------------------------------- ZIP 写入 ------------------------------- */

/**
 * 流式 ZIP 写入器：边写边产出，不把整个 APK 读入内存。
 * 默认对所有条目做 4 字节对齐；未压缩的 lib 目录下 .so 做 4096 字节页对齐（等价 zipalign -p）。
 */
class ZipWriter(private val out: OutputStream) {

    private val bufOut = BufferedOutputStream(out, 1 shl 20)
    private var offset = 0L

    private val central = ArrayList<CentralEntry>()

    val bytesWritten: Long get() = offset

    /** 每次写入都累加偏移，直通拷贝也能正确推进 offset */
    private val counter = object : OutputStream() {
        override fun write(b: Int) {
            bufOut.write(b)
            offset += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            bufOut.write(b, off, len)
            offset += len
        }
    }

    private data class CentralEntry(
        val name: String,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val dosTime: Int,
        val dosDate: Int,
        val externalAttrs: Long,
    )

    /** 直通拷贝一个已存在的条目（不重新压缩） */
    fun addRawEntry(
        src: ZipReader,
        entry: ZipEntryInfo,
        dosTime: Int? = null,
        dosDate: Int? = null,
        externalAttrs: Long? = null,
        align: Int? = null,
    ) {
        val alignVal = align ?: if (entry.method == 0 && isNativeLib(entry.name)) NATIVE_LIB_ALIGN else DEFAULT_ALIGN
        val dosTimeVal = dosTime ?: entry.dosTime
        val dosDateVal = dosDate ?: entry.dosDate
        val externalAttrsVal = externalAttrs ?: entry.externalAttrs
        val localHeaderOffset = writeLocalHeader(
            entry.name, entry.method, entry.crc32, entry.compressedSize, entry.uncompressedSize,
            dosTimeVal, dosDateVal, externalAttrsVal, alignVal,
        )
        src.copyEntryRaw(entry, counter)
        central.add(
            CentralEntry(
                entry.name, entry.method, entry.crc32, entry.compressedSize, entry.uncompressedSize,
                localHeaderOffset, dosTimeVal, dosDateVal, externalAttrsVal,
            )
        )
    }

    /** 新增 / 覆盖一个条目；compress=true 时用 deflate(level 9) 压缩，否则原样存储 */
    fun addBytes(
        name: String,
        data: ByteArray,
        compress: Boolean = true,
        dosTime: Int? = null,
        dosDate: Int? = null,
        externalAttrs: Long? = null,
        align: Int? = null,
    ) {
        if (name.endsWith("/")) return
        val method = if (compress) 8 else 0
        val payload = if (compress) deflateRaw(data) else data
        val checksum = crc32(data)
        val (fallbackTime, fallbackDate) = dosDateTime()
        val dosTimeVal = dosTime ?: fallbackTime
        val dosDateVal = dosDate ?: fallbackDate
        val externalAttrsVal = externalAttrs ?: 0L
        val alignVal = align ?: if (method == 0 && isNativeLib(name)) NATIVE_LIB_ALIGN else DEFAULT_ALIGN

        val localHeaderOffset = writeLocalHeader(
            name, method, checksum, payload.size.toLong(), data.size.toLong(),
            dosTimeVal, dosDateVal, externalAttrsVal, alignVal,
        )
        write(payload)
        central.add(
            CentralEntry(
                name, method, checksum, payload.size.toLong(), data.size.toLong(),
                localHeaderOffset, dosTimeVal, dosDateVal, externalAttrsVal,
            )
        )
    }

    fun finish() {
        val cdStart = offset
        for (e in central) {
            val nameBuf = e.name.toByteArray(Charsets.UTF_8)
            val header = ByteArray(46)
            writeUInt32LE(header, 0, SIG_CD)
            writeUInt16LE(header, 4, 20) // version made by
            writeUInt16LE(header, 6, 20) // version needed
            writeUInt16LE(header, 8, 0x800) // UTF-8 名称
            writeUInt16LE(header, 10, e.method)
            writeUInt16LE(header, 12, e.dosTime)
            writeUInt16LE(header, 14, e.dosDate)
            writeUInt32LE(header, 16, e.crc32)
            writeUInt32LE(header, 20, e.compressedSize)
            writeUInt32LE(header, 24, e.uncompressedSize)
            writeUInt16LE(header, 28, nameBuf.size)
            writeUInt16LE(header, 30, 0) // extra
            writeUInt16LE(header, 32, 0) // comment
            writeUInt16LE(header, 34, 0) // disk
            writeUInt16LE(header, 36, 0) // internal attrs
            writeUInt32LE(header, 38, e.externalAttrs)
            writeUInt32LE(header, 42, e.localHeaderOffset)
            write(header)
            write(nameBuf)
        }
        val cdSize = offset - cdStart

        val eocd = ByteArray(22)
        writeUInt32LE(eocd, 0, SIG_EOCD)
        writeUInt16LE(eocd, 4, 0)
        writeUInt16LE(eocd, 6, 0)
        writeUInt16LE(eocd, 8, minOf(central.size, 0xffff))
        writeUInt16LE(eocd, 10, minOf(central.size, 0xffff))
        writeUInt32LE(eocd, 12, minOf(cdSize, 0xffffffffL))
        writeUInt32LE(eocd, 16, minOf(cdStart, 0xffffffffL))
        writeUInt16LE(eocd, 20, 0)
        write(eocd)

        bufOut.flush()
    }

    private fun write(buf: ByteArray) {
        counter.write(buf, 0, buf.size)
    }

    private fun writeLocalHeader(
        name: String,
        method: Int,
        crc32: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        dosTime: Int,
        dosDate: Int,
        @Suppress("UNUSED_PARAMETER") externalAttrs: Long,
        align: Int,
    ): Long {
        val nameBuf = name.toByteArray(Charsets.UTF_8)
        val base = offset

        var pad = ((align - ((base + 30 + nameBuf.size) % align)) % align).toInt()
        // 额外字段长度必须为 0 或 >= 4，避免写出无法解析的残缺字段
        if (pad > 0 && pad < 4) pad += 4

        // 本地文件头布局（共 30 字节）：
        // 0 签名 | 4 版本 | 6 标志 | 8 压缩方式 | 10 时间 | 12 日期 |
        // 14 CRC-32 | 18 压缩后大小 | 22 原始大小 | 26 文件名长度 | 28 额外字段长度
        val header = ByteArray(30)
        writeUInt32LE(header, 0, SIG_LFH)
        writeUInt16LE(header, 4, 20)
        writeUInt16LE(header, 6, 0x800)
        writeUInt16LE(header, 8, method)
        writeUInt16LE(header, 10, dosTime)
        writeUInt16LE(header, 12, dosDate)
        writeUInt32LE(header, 14, crc32)
        writeUInt32LE(header, 18, compressedSize)
        writeUInt32LE(header, 22, uncompressedSize)
        writeUInt16LE(header, 26, nameBuf.size)
        writeUInt16LE(header, 28, pad)
        write(header)
        write(nameBuf)
        if (pad > 0) write(ByteArray(pad))
        return base
    }

    companion object {
        /** 当前本地时间的 DOS 时间戳 */
        fun dosDateTime(date: Date = Date()): Pair<Int, Int> {
            val cal = Calendar.getInstance()
            cal.time = date
            val dosTime = (cal.get(Calendar.HOUR_OF_DAY) shl 11) or
                (cal.get(Calendar.MINUTE) shl 5) or
                ((cal.get(Calendar.SECOND) / 2) and 0x1f)
            val dosDate = ((cal.get(Calendar.YEAR) - 1980) shl 9) or
                ((cal.get(Calendar.MONTH) + 1) shl 5) or
                cal.get(Calendar.DAY_OF_MONTH)
            return dosTime to dosDate
        }
    }
}
