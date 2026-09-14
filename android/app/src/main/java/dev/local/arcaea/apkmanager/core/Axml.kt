package dev.local.arcaea.apkmanager.core

/**
 * 二进制 AndroidManifest.xml（AXML）解析与包名替换。
 *
 * 本文件是 windows/electron/core/axml.ts 的忠实 Kotlin 移植，行为与 TS 版本保持一致。
 *
 * 为什么需要它：APK 内的 AndroidManifest.xml 是编译后的二进制格式，
 * 直接改文本会损坏文件。这里实现最小可用的 AXML 读写：
 * - 解析字符串池与元素/属性，读取 package、versionName、versionCode、uses-sdk 等；
 * - 通过「重建字符串池」把 <manifest package="..."> 换成用户指定的包名，
 *   其余条目、其它字符串引用（按索引引用）全部保持不变。
 *
 * 只做包名替换这一件事，不依赖 apktool / aapt2，速度与风险都可控。
 *
 * 说明：全部字节序都是小端；这里自己实现私有的 ByteArray 读写扩展函数，
 * 不依赖 ByteBuffer 的默认字节序，也不引用任何 android.* 以便在纯 JVM 单测中运行。
 */

private const val CHUNK_STRING_POOL = 0x0001
private const val CHUNK_START_ELEMENT = 0x0102
private const val CHUNK_END_ELEMENT = 0x0103
private const val CHUNK_XML = 0x0003

private const val TYPE_STRING = 0x03
private const val TYPE_INT_DEC = 0x10
private const val TYPE_INT_HEX = 0x11
private const val TYPE_REFERENCE = 0x01

/** 与 JS 的 0xffffffff 等价；uint32 以 Long 表示，便于做无符号比较。 */
private const val NO_INDEX = 0xFFFFFFFFL

/** AXML 单个属性。value 为 String 或 Int，引用类型（TYPE_REFERENCE）为 null。 */
data class AxmlAttribute(
    val name: String,
    val namespace: String?,
    val rawValue: String?,
    val value: Any?,
    val type: Int,
)

/** 解析出的一个 AXML 元素（按出现顺序，含嵌套深度）。 */
data class AxmlElement(
    val name: String,
    val attributes: List<AxmlAttribute>,
    val depth: Int,
)

/** 清单关键信息（只读解析结果）。 */
data class ManifestInfo(
    val isBinary: Boolean,
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val compileSdk: String? = null,
    val applicationName: String? = null,
    val mainActivity: String? = null,
    /** 所有含 android:name 的组件类名（用于判断是否为绝对类名） */
    val componentNames: List<String> = emptyList(),
)

/** 解析出来的字符串池。 */
private class StringPool(
    val strings: List<String>,
    val utf8: Boolean,
    val sorted: Boolean,
    /** 原字符串池 chunk 的原始字节（用于重建 styles 区块） */
    val rawStyles: ByteArray,
    val styleCount: Long,
    val stylesStart: Long,
    val chunkSize: Long,
)

// ---------------------------------------------------------------------------
// 小端字节读写（私有扩展函数）
// ---------------------------------------------------------------------------

private fun ByteArray.readUInt16LE(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.readUInt32LE(offset: Int): Long =
    (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)

private fun ByteArray.writeUInt16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
}

private fun ByteArray.writeUInt32LE(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value ushr 8) and 0xff).toByte()
    this[offset + 2] = ((value ushr 16) and 0xff).toByte()
    this[offset + 3] = ((value ushr 24) and 0xff).toByte()
}

// ---------------------------------------------------------------------------
// uleb128 / 对齐 / 拼接工具
// ---------------------------------------------------------------------------

/** 读取一个 uleb128；返回 (value, next)。与 TS 一致，shift > 28 视为损坏。 */
private fun readUleb128(buf: ByteArray, offset: Int): Pair<Int, Int> {
    var result = 0
    var shift = 0
    var pos = offset
    while (true) {
        val byte = buf[pos++].toInt()
        result = result or ((byte and 0x7f) shl shift)
        if ((byte and 0x80) == 0) break
        shift += 7
        if (shift > 28) throw IllegalStateException("AXML 字符串长度字段损坏")
    }
    // TS 返回 result >>> 0；Int 位模式相同，字符串长度场景不涉及高位溢出。
    return result to pos
}

private fun writeUleb128(value: Int): ByteArray {
    val bytes = ArrayList<Byte>(4)
    var v = value
    do {
        var byte = v and 0x7f
        v = v ushr 7
        if (v != 0) byte = byte or 0x80
        bytes.add(byte.toByte())
    } while (v != 0)
    return bytes.toByteArray()
}

private fun align4(n: Int): Int = (n + 3) and 3.inv()

private fun concat(parts: List<ByteArray>): ByteArray {
    var total = 0
    for (p in parts) total += p.size
    val out = ByteArray(total)
    var pos = 0
    for (p in parts) {
        p.copyInto(out, pos)
        pos += p.size
    }
    return out
}

// ---------------------------------------------------------------------------
// 字符串池解析 / 重建
// ---------------------------------------------------------------------------

private fun parseStringPool(chunk: ByteArray): StringPool {
    val headerSize = chunk.readUInt16LE(2)
    val chunkSize = chunk.readUInt32LE(4)
    val stringCount = chunk.readUInt32LE(8)
    val styleCount = chunk.readUInt32LE(12)
    val flags = chunk.readUInt32LE(16)
    val stringsStart = chunk.readUInt32LE(20)
    val stylesStart = chunk.readUInt32LE(24)

    val utf8 = (flags and 0x100L) != 0L
    val sorted = (flags and 0x1L) != 0L
    val strings = ArrayList<String>(stringCount.toInt())

    for (i in 0 until stringCount.toInt()) {
        val offsetPos = headerSize + i * 4
        val strOffset = chunk.readUInt32LE(offsetPos)
        val pos = (stringsStart + strOffset).toInt()
        if (utf8) {
            val chars = readUleb128(chunk, pos)
            val bytes = readUleb128(chunk, chars.second)
            val start = bytes.second
            strings.add(String(chunk, start, bytes.first, Charsets.UTF_8))
        } else {
            val chars = readUleb128(chunk, pos)
            val start = chars.second
            strings.add(String(chunk, start, chars.first * 2, Charsets.UTF_16LE))
        }
    }

    val rawStyles =
        if (styleCount > 0L && stylesStart > 0L) chunk.copyOfRange(stylesStart.toInt(), chunk.size)
        else ByteArray(0)

    return StringPool(strings, utf8, sorted, rawStyles, styleCount, stylesStart, chunkSize)
}

private fun encodeStringPool(pool: StringPool, replacements: Map<Int, String>): ByteArray {
    val values = pool.strings.toMutableList()
    for ((index, value) in replacements) {
        if (index >= 0 && index < values.size) values[index] = value
    }

    // 依次编码字符串数据，同时记录每个字符串相对 stringsStart 的偏移
    val encoded = ArrayList<ByteArray>(values.size)
    val offsets = ArrayList<Int>(values.size)
    var cursor = 0
    for (value in values) {
        offsets.add(cursor)
        val piece: ByteArray
        if (pool.utf8) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val charLen = value.length // UTF-16 码元数量，AXML 约定
            piece = concat(listOf(writeUleb128(charLen), writeUleb128(bytes.size), bytes, byteArrayOf(0)))
        } else {
            val bytes = value.toByteArray(Charsets.UTF_16LE)
            piece = concat(listOf(writeUleb128(value.length), bytes, byteArrayOf(0, 0)))
        }
        encoded.add(piece)
        cursor += piece.size
    }
    val stringData = concat(encoded)

    val headerSize = 28
    val stringsStart = headerSize + offsets.size * 4 + pool.styleCount.toInt() * 4
    val stylesStart = if (pool.styleCount > 0L) align4(stringsStart + stringData.size) else 0

    val body = ByteArray(offsets.size * 4 + stringData.size)
    for (i in offsets.indices) body.writeUInt32LE(i * 4, offsets[i])
    stringData.copyInto(body, offsets.size * 4)

    var size = stringsStart + stringData.size
    var styleTail = ByteArray(0)
    if (pool.styleCount > 0L && pool.rawStyles.isNotEmpty()) {
        val padding = stylesStart - size
        styleTail = concat(listOf(ByteArray(maxOf(padding, 0)), pool.rawStyles))
        size = stylesStart + pool.rawStyles.size
    }

    val aligned = align4(size)
    val out = ByteArray(aligned)
    out.writeUInt16LE(0, CHUNK_STRING_POOL)
    out.writeUInt16LE(2, headerSize)
    out.writeUInt32LE(4, aligned)
    out.writeUInt32LE(8, offsets.size)
    out.writeUInt32LE(12, pool.styleCount.toInt())
    // 保留 UTF-8 标记，去掉 sorted 标记（顺序可能已被破坏）
    out.writeUInt32LE(16, if (pool.utf8) 0x100 else 0)
    out.writeUInt32LE(20, stringsStart)
    out.writeUInt32LE(24, stylesStart)
    body.copyInto(out, headerSize)
    if (styleTail.isNotEmpty()) styleTail.copyInto(out, size - styleTail.size)
    return out
}

// ---------------------------------------------------------------------------
// 元素解析
// ---------------------------------------------------------------------------

/** 解析 AXML 的全部元素（按出现顺序，含嵌套深度）；TS 中的 parseAxmlElements。 */
private fun parseAxmlElementsInternal(buf: ByteArray): List<AxmlElement> {
    if (buf.size < 8 || buf.readUInt16LE(0) != CHUNK_XML) {
        throw IllegalStateException("不是二进制 AXML 文件")
    }

    val elements = ArrayList<AxmlElement>()
    var pos = buf.readUInt16LE(2)
    var pool: StringPool? = null
    var depth = 0
    val total = buf.size

    while (pos + 8 <= total) {
        val type = buf.readUInt16LE(pos)
        val chunkSize = buf.readUInt32LE(pos + 4).toInt()
        if (chunkSize <= 0 || pos + chunkSize > total) break

        if (type == CHUNK_STRING_POOL) {
            pool = parseStringPool(buf.copyOfRange(pos, pos + chunkSize))
        } else if (type == CHUNK_START_ELEMENT) {
            fun str(idx: Long): String? {
                val p = pool
                if (p == null || idx == NO_INDEX || idx >= p.strings.size) return null
                return p.strings[idx.toInt()]
            }

            val nameIdx = buf.readUInt32LE(pos + 16 + 4)
            val attrStart = buf.readUInt16LE(pos + 16 + 8)
            val attrCount = buf.readUInt16LE(pos + 16 + 12)
            val attrsBase = pos + 16 + attrStart
            val attributes = ArrayList<AxmlAttribute>()
            for (i in 0 until attrCount) {
                val a = attrsBase + i * 20
                if (a + 20 > total) break
                val nsIdx = buf.readUInt32LE(a)
                val aNameIdx = buf.readUInt32LE(a + 4)
                val rawIdx = buf.readUInt32LE(a + 8)
                val dataType = buf[a + 15].toInt() and 0xff
                val data = buf.readUInt32LE(a + 16)
                var value: Any? = null
                if (dataType == TYPE_STRING) value = str(data)
                else if (dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX) value = data.toInt()
                else if (dataType == TYPE_REFERENCE) value = null
                attributes.add(
                    AxmlAttribute(
                        name = str(aNameIdx) ?: "@$aNameIdx",
                        namespace = if (nsIdx == NO_INDEX) null else str(nsIdx),
                        rawValue = if (rawIdx == NO_INDEX) null else str(rawIdx),
                        value = value,
                        type = dataType,
                    ),
                )
            }
            elements.add(AxmlElement(str(nameIdx) ?: "?", attributes, depth))
            depth++
        } else if (type == CHUNK_END_ELEMENT) {
            depth = maxOf(0, depth - 1)
        }

        pos += chunkSize
    }

    return elements
}

private fun attrValue(el: AxmlElement, name: String): Any? =
    el.attributes.firstOrNull { it.name == name }?.value

private fun attrString(el: AxmlElement?, name: String): String? {
    if (el == null) return null
    val value = attrValue(el, name)
    return value as? String
}

// ---------------------------------------------------------------------------
// 包名派生标识符改写
// ---------------------------------------------------------------------------

/** 需要跟随包名一起改写的属性组合；element 为 null 表示匹配任意元素 */
private val PACKAGE_DERIVED_ATTRIBUTES: List<Pair<String?, String>> = listOf(
    "permission" to "name",
    "uses-permission" to "name",
    "uses-permission-sdk-23" to "name",
    "provider" to "authorities",
    null to "permission",
    null to "readPermission",
    null to "writePermission",
)

private fun isPackageDerived(elementName: String, attributeName: String): Boolean =
    PACKAGE_DERIVED_ATTRIBUTES.any { (element, attribute) ->
        (element == null || element == elementName) && attribute == attributeName
    }

/** 把以 oldPackage. 开头的标识符换成新前缀；authorities 可能是分号分隔的多个值 */
private fun rewritePrefixed(value: String, oldPackage: String, newPackage: String): String {
    val prefix = "$oldPackage."
    return value.split(";").joinToString(";") { part ->
        if (part.startsWith(prefix)) "$newPackage${part.substring(oldPackage.length)}" else part
    }
}

/** 纯文本清单的等价改写（仅在 AndroidManifest 未被编译时才会走到） */
private fun rewriteTextIdentifiers(source: String, oldPackage: String, newPackage: String): String {
    fun rewrite(value: String): String = rewritePrefixed(value, oldPackage, newPackage)

    var out = Regex("(<(?:permission|uses-permission|uses-permission-sdk-23)\\b[^>]*?\\bandroid:name\\s*=\\s*\")([^\"]*)(\")")
        .replace(source) { m -> m.groupValues[1] + rewrite(m.groupValues[2]) + m.groupValues[3] }
    out = Regex("(<provider\\b[^>]*?\\bandroid:authorities\\s*=\\s*\")([^\"]*)(\")")
        .replace(out) { m -> m.groupValues[1] + rewrite(m.groupValues[2]) + m.groupValues[3] }
    out = Regex("(\\bandroid:(?:permission|readPermission|writePermission)\\s*=\\s*\")([^\"]*)(\")")
        .replace(out) { m -> m.groupValues[1] + rewrite(m.groupValues[2]) + m.groupValues[3] }
    return out
}

// ---------------------------------------------------------------------------
// 公开 API
// ---------------------------------------------------------------------------

object Axml {

    /** 解析 AXML 的全部元素（按出现顺序，含嵌套深度） */
    fun parseElements(buf: ByteArray): List<AxmlElement> = parseAxmlElementsInternal(buf)

    /** 读取清单关键信息（只读，不修改） */
    fun readManifest(buf: ByteArray): ManifestInfo {
        val isBinary = buf.size >= 8 && buf.readUInt16LE(0) == CHUNK_XML
        if (!isBinary) {
            val text = String(buf, Charsets.UTF_8)
            val pkg = Regex("<manifest[^>]*\\bpackage\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)
            val vName = Regex("android:versionName\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)
            val vCode = Regex("android:versionCode\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(text)
            return ManifestInfo(
                isBinary = false,
                packageName = pkg?.groupValues?.get(1) ?: "",
                versionName = vName?.groupValues?.get(1),
                versionCode = vCode?.groupValues?.get(1)?.toIntOrNull(),
                componentNames = emptyList(),
            )
        }

        val elements = parseAxmlElementsInternal(buf)
        val manifestEl = elements.firstOrNull { it.name == "manifest" }
        val usesSdk = elements.firstOrNull { it.name == "uses-sdk" }
        val application = elements.firstOrNull { it.name == "application" }

        val componentNames = ArrayList<String>()
        for (el in elements) {
            val n = attrValue(el, "name")
            if (n is String) componentNames.add(n)
        }

        val versionCodeRaw = manifestEl?.let { attrValue(it, "versionCode") }
        val minSdkRaw = usesSdk?.let { attrValue(it, "minSdkVersion") }
        val targetSdkRaw = usesSdk?.let { attrValue(it, "targetSdkVersion") }

        return ManifestInfo(
            isBinary = true,
            packageName = attrString(manifestEl, "package") ?: "",
            versionName = attrString(manifestEl, "versionName"),
            versionCode = versionCodeRaw as? Int,
            minSdk = minSdkRaw as? Int,
            targetSdk = targetSdkRaw as? Int,
            compileSdk = attrString(manifestEl, "platformBuildVersionName"),
            applicationName = attrString(application, "name"),
            mainActivity = attrString(elements.firstOrNull { it.name == "activity" }, "name"),
            componentNames = componentNames,
        )
    }

    /**
     * 精确查找 <manifest> 元素上 package 属性所引用的字符串下标。
     * 做法：先解析出字符串池，在池中找到名为 "package" 的字符串下标，
     * 再在 <manifest> 的属性中匹配「属性名索引 == 该下标」的那一项。
     */
    fun findManifestPackageIndex(buf: ByteArray): Int {
        if (buf.size < 8 || buf.readUInt16LE(0) != CHUNK_XML) {
            throw IllegalStateException("不是二进制 AXML 文件，无法定位 package 属性")
        }
        val total = buf.size
        val headerSize = buf.readUInt16LE(2)
        val poolChunkSize = buf.readUInt32LE(headerSize + 4).toInt()
        val pool = parseStringPool(buf.copyOfRange(headerSize, headerSize + poolChunkSize))
        val nameIndexes = pool.strings.withIndex().filter { it.value == "package" }.map { it.index }
        if (nameIndexes.isEmpty()) throw IllegalStateException("清单字符串池中找不到 package 属性名")

        var pos = headerSize + poolChunkSize
        while (pos + 8 <= total) {
            val type = buf.readUInt16LE(pos)
            val chunkSize = buf.readUInt32LE(pos + 4).toInt()
            if (chunkSize <= 0 || pos + chunkSize > total) break
            if (type == CHUNK_START_ELEMENT) {
                val attrStart = buf.readUInt16LE(pos + 16 + 8)
                val attrCount = buf.readUInt16LE(pos + 16 + 12)
                val attrsBase = pos + 16 + attrStart
                for (i in 0 until attrCount) {
                    val a = attrsBase + i * 20
                    if (a + 20 > total) break
                    val nameIdx = buf.readUInt32LE(a + 4).toInt()
                    if (nameIndexes.contains(nameIdx)) {
                        val dataType = buf[a + 15].toInt() and 0xff
                        if (dataType != TYPE_STRING) {
                            throw IllegalStateException("清单中的 package 属性不是字符串类型，无法替换")
                        }
                        return buf.readUInt32LE(a + 16).toInt()
                    }
                }
                break // 只看 <manifest>
            }
            pos += chunkSize
        }
        throw IllegalStateException("未能在 <manifest> 上找到 package 属性")
    }

    /**
     * 收集清单中所有「自定义权限名 / Provider 授权名」属性的字符串池下标
     * （仅包含以旧包名为前缀的值）。
     */
    fun collectPackageDerivedIndices(buf: ByteArray, oldPackage: String): List<Int> {
        if (buf.size < 8 || buf.readUInt16LE(0) != CHUNK_XML) return emptyList()
        val headerSize = buf.readUInt16LE(2)
        val poolChunkSize = buf.readUInt32LE(headerSize + 4).toInt()
        val pool = parseStringPool(buf.copyOfRange(headerSize, headerSize + poolChunkSize))
        val indices = ArrayList<Int>()
        val total = buf.size
        var pos = headerSize + poolChunkSize

        while (pos + 8 <= total) {
            val type = buf.readUInt16LE(pos)
            val chunkSize = buf.readUInt32LE(pos + 4).toInt()
            if (chunkSize <= 0 || pos + chunkSize > total) break
            if (type == CHUNK_START_ELEMENT) {
                val elementName = pool.strings.getOrNull(buf.readUInt32LE(pos + 20).toInt()) ?: ""
                val attrStart = buf.readUInt16LE(pos + 24)
                val attrCount = buf.readUInt16LE(pos + 28)
                val attrsBase = pos + 16 + attrStart
                for (i in 0 until attrCount) {
                    val a = attrsBase + i * 20
                    if (a + 20 > total) break
                    if ((buf[a + 15].toInt() and 0xff) != TYPE_STRING) continue
                    val attributeName = pool.strings.getOrNull(buf.readUInt32LE(a + 4).toInt()) ?: ""
                    if (!isPackageDerived(elementName, attributeName)) continue
                    val valueIndex = buf.readUInt32LE(a + 16).toInt()
                    val value = pool.strings.getOrNull(valueIndex)
                    if (value != null && value.startsWith("$oldPackage.")) indices.add(valueIndex)
                }
            }
            pos += chunkSize
        }
        return indices
    }

    /** 导出字符串池的全部字符串（调试/比对用） */
    fun dumpStringPool(buf: ByteArray): List<String> {
        if (buf.size < 8 || buf.readUInt16LE(0) != CHUNK_XML) return emptyList()
        val headerSize = buf.readUInt16LE(2)
        val poolChunkSize = buf.readUInt32LE(headerSize + 4).toInt()
        return parseStringPool(buf.copyOfRange(headerSize, headerSize + poolChunkSize)).strings
    }

    /**
     * 替换包名。同样长度或不同长度都可处理（重建字符串池）。
     * 返回新的 AndroidManifest.xml 字节；非 AXML（纯文本）时退化为文本替换。
     *
     * rewriteIdentifiers = true 时，还会把清单中**以旧包名为前缀**的自定义标识符一起改写，
     * 例如：
     *   <permission android:name="old.permission.C2D_MESSAGE">
     *   <uses-permission android:name="old.permission.C2D_MESSAGE">
     *   <provider android:authorities="old.provider">
     * 因为这类标识符全局唯一，若不改写，新旧两个包同时安装会分别报
     * INSTALL_FAILED_DUPLICATE_PERMISSION / INSTALL_FAILED_CONFLICTING_PROVIDER。
     *
     * 注意：只改写「权限名 / 授权名」这类属性，**不会**动 android:name 上的组件类名，
     * 否则会把类引用改坏。
     */
    fun setPackageName(buf: ByteArray, newPackage: String, rewriteIdentifiers: Boolean = true): ByteArray {
        val isBinary = buf.size >= 8 && buf.readUInt16LE(0) == CHUNK_XML
        if (!isBinary) {
            val text = String(buf, Charsets.UTF_8)
            val pkgMatch = Regex("(<manifest[^>]*\\bpackage\\s*=\\s*\")([^\"]*)(\")", RegexOption.IGNORE_CASE).find(text)
            var replaced = if (pkgMatch != null) {
                text.substring(0, pkgMatch.range.first) +
                    pkgMatch.groupValues[1] + newPackage + pkgMatch.groupValues[3] +
                    text.substring(pkgMatch.range.last + 1)
            } else {
                text
            }
            if (rewriteIdentifiers) {
                val oldPackage = Regex("<manifest[^>]*\\bpackage\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.get(1) ?: ""
                if (oldPackage.isNotEmpty()) replaced = rewriteTextIdentifiers(replaced, oldPackage, newPackage)
            }
            return replaced.toByteArray(Charsets.UTF_8)
        }

        val xmlHeaderSize = buf.readUInt16LE(2)
        val poolChunkSize = buf.readUInt32LE(xmlHeaderSize + 4).toInt()
        val pool = parseStringPool(buf.copyOfRange(xmlHeaderSize, xmlHeaderSize + poolChunkSize))
        val stringIndex = findManifestPackageIndex(buf)
        val oldPackage = pool.strings.getOrNull(stringIndex) ?: ""

        val replacements = LinkedHashMap<Int, String>()
        replacements[stringIndex] = newPackage
        if (rewriteIdentifiers && oldPackage.isNotEmpty() && oldPackage != newPackage) {
            for (index in collectPackageDerivedIndices(buf, oldPackage)) {
                if (index == stringIndex) continue
                val value = pool.strings.getOrNull(index) ?: continue
                val next = rewritePrefixed(value, oldPackage, newPackage)
                if (next != value) replacements[index] = next
            }
        }

        val newPool = encodeStringPool(pool, replacements)
        val rest = buf.copyOfRange(xmlHeaderSize + poolChunkSize, buf.size)

        val out = ByteArray(8 + newPool.size + rest.size)
        out.writeUInt16LE(0, CHUNK_XML)
        out.writeUInt16LE(2, 8)
        out.writeUInt32LE(4, out.size)
        newPool.copyInto(out, 8)
        rest.copyInto(out, 8 + newPool.size)
        return out
    }
}
