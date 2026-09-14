package dev.local.arcaea.apkmanager

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.arcaea.apkmanager.data.AppRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 在真机上验证「导入资源包」的**真实解压路径**（ContentResolver + ZipInputStream）。
 *
 * 这条路径依赖 Android API，桌面单元测试覆盖不到；而它正是「导入 zip 后资源没被填充」
 * 这类问题的现场，所以用 instrumented test 直接跑真实实现。
 */
@RunWith(AndroidJUnit4::class)
class ResourceZipImportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = AppRepository(context)

    private fun writeZip(fileName: String, entries: Map<String, ByteArray>): File {
        val file = File(context.cacheDir, fileName)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private val songdata =
        """{"id":"ignored","set":"ignored","title_localized":{"en":"Everything B.K."},"artist":"nora2r",""" +
            """"bpm":"180","bpm_base":180,"difficulties":[{"ratingClass":0,"rating":0},""" +
            """{"ratingClass":2,"chartDesigner":"Everything B.P.C.","rating":10}]}"""

    /** 正常场景：单曲资源包（根目录扁平结构 + songdata.json）应当被完整解析 */
    @Test
    fun importsSingleSongBundleWithMetadata() {
        val zip = writeZip(
            "single-song.zip",
            linkedMapOf(
                "songdata.json" to songdata.toByteArray(),
                "2.aff" to "AudioOffset:0\n".toByteArray(),
                "base.ogg" to ByteArray(64),
                "base_256.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
            ),
        )

        val bundle = repo.extractResourceZip(Uri.fromFile(zip))

        assertNotNull("应当解析出 songdata.json 元数据", bundle.metadata)
        assertEquals(
            "应当解压出 3 个资源文件（songdata.json 不计入）",
            3,
            bundle.files.size,
        )
        assertTrue(
            "资源文件名应当正确：${bundle.files.map { it.first }}",
            bundle.files.map { it.first }.containsAll(listOf("2.aff", "base.ogg", "base_256.jpg")),
        )
        assertTrue("解压出来的文件必须真实存在", bundle.files.all { it.second.exists() && it.second.length() > 0 })
        assertEquals(
            "元数据里的曲名应当被解析出来",
            "Everything B.K.",
            bundle.metadata?.optObject("title_localized")?.optString("en"),
        )
    }

    /** 嵌套结构（<歌曲目录>/… 与 assets/songs/<id>/…）也应当被正确剥离前缀 */
    @Test
    fun stripsNestedPrefixes() {
        val zip = writeZip(
            "nested.zip",
            linkedMapOf(
                "mysong/songdata.json" to songdata.toByteArray(),
                "mysong/2.aff" to "AudioOffset:0\n".toByteArray(),
                "assets/songs/other/3.aff" to "AudioOffset:1\n".toByteArray(),
                "assets/songs/pack/select_base.png" to ByteArray(8),
            ),
        )

        val bundle = repo.extractResourceZip(Uri.fromFile(zip))

        assertNotNull(bundle.metadata)
        assertEquals(
            "应当剥离前缀只保留文件名：${bundle.files.map { it.first }}",
            listOf("2.aff", "3.aff"),
            bundle.files.map { it.first }.sorted(),
        )
    }

    /** 无关文件（非资源扩展名、曲包横幅）应当被跳过，不写进歌曲目录 */
    @Test
    fun skipsNonResourceFiles() {
        val zip = writeZip(
            "mixed.zip",
            linkedMapOf(
                "readme.txt" to "hello".toByteArray(),
                "assets/songs/abc/2.aff" to "AudioOffset:0\n".toByteArray(),
                "assets/img/bg/1080/base.jpg" to ByteArray(8),
            ),
        )

        val bundle = repo.extractResourceZip(Uri.fromFile(zip))

        assertEquals("只应保留单曲资源文件", listOf("2.aff"), bundle.files.map { it.first })
    }

    /** 把整个 APK 改名成 zip 是最常见的误用，应当明确报错而不是静默导入一堆无关文件 */
    @Test
    fun rejectsWholeApkRenamedToZip() {
        val zip = writeZip(
            "apk-as-zip.zip",
            linkedMapOf(
                "AndroidManifest.xml" to ByteArray(16),
                "assets/songs/abc/2.aff" to "AudioOffset:0\n".toByteArray(),
            ),
        )

        val error = runCatching { repo.extractResourceZip(Uri.fromFile(zip)) }.exceptionOrNull()

        assertTrue("应当抛出可读的 IOException，实际是 $error", error is java.io.IOException)
        assertTrue(
            "错误信息应当说明这是完整 APK：${error?.message}",
            error?.message?.contains("完整的 APK") == true,
        )
    }
}
