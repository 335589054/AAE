package dev.local.arcaea.apkmanager.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 反向导出单曲为资源 zip。
 *
 * 与原「导入资源 zip」互逆：导出产物是一个根部含 `songdata.json` 的单曲资源包，
 * 可直接用本应用的「新增歌曲 → 选择 zip」或其它工具重新导入。
 *
 * songdata.json 的写法兼容 [ResourceZip.parseSongFragment]（裸单曲对象 / {"song":{}}）。
 */
object SongZip {

    /** 导出规格：哪首歌、打包哪些难度 */


    /**
     * 写单曲资源 zip 到 [out]。
     * @param song 要导出的歌曲；其 json 即为 songdata.json 的内容
     * @param files 要写进 zip 的条目（文件名 → 内容），例如 "2.aff"、"base.ogg"、"base.jpg"
     */
    fun write(song: Song, files: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // 1) songdata.json：以裸单曲对象写入
            val songBytes = (Json.write(song.json, 2) + "\n").toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("songdata.json"))
            zip.write(songBytes)
            zip.closeEntry()

            // 2) 资源条目，逐条写入（音频等本身已压缩的用 STORED，避免二次压缩与更慢）
            for ((name, data) in files) {
                zip.putNextEntry(ZipEntry(name).apply {
                    method = if (isBinary(name)) ZipEntry.STORED else ZipEntry.DEFLATED
                    setSize(data.size.toLong())
                    crc = java.util.zip.CRC32().apply { update(data) }.value
                    setCompressedSize(data.size.toLong())
                })
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    /** 判断是否适合直接存储（压缩音频 / 图片） */
    private fun isBinary(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot).lowercase() in BINARY_EXTENSIONS
    }

    private val BINARY_EXTENSIONS = setOf(
        ".ogg", ".opus", ".mp3", ".wav", ".m4a", ".aac", ".flac",
        ".jpg", ".jpeg", ".png", ".webp", ".bmp",
    )
}