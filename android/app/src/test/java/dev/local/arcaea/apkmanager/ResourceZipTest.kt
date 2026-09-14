package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.ResourceZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 资源压缩包的条目名规范化与元数据片段解析规则（与 Windows 版保持一致） */
class ResourceZipTest {

    @Test
    fun normalizesThreeSupportedStructures() {
        assertEquals("2.aff", ResourceZip.normalizeEntryName("2.aff"))
        assertEquals("base.jpg", ResourceZip.normalizeEntryName("mysong/base.jpg"))
        assertEquals("3.aff", ResourceZip.normalizeEntryName("assets/songs/mysong/3.aff"))
        assertEquals("base.ogg", ResourceZip.normalizeEntryName("assets\\songs\\mysong\\base.ogg"))
    }

    @Test
    fun ignoresSystemAndOutOfScopeEntries() {
        assertNull(ResourceZip.normalizeEntryName("__MACOSX/foo.txt"))
        assertNull(ResourceZip.normalizeEntryName("a/b/c/2.aff"))
        assertNull(ResourceZip.normalizeEntryName("assets/songs/pack/select_base.png"))
        assertNull(ResourceZip.normalizeEntryName("assets/songs/songlist"))
        assertNull(ResourceZip.normalizeEntryName("songlist"))
        assertNull(ResourceZip.normalizeEntryName("dir/"))
        assertNull(ResourceZip.normalizeEntryName(".DS_Store"))
    }

    @Test
    fun detectsMetadataFragments() {
        assertTrue(ResourceZip.isMetadataName("songlist"))
        assertTrue(ResourceZip.isMetadataName("songlist.txt"))
        assertTrue(ResourceZip.isMetadataName("slst"))
        assertTrue(ResourceZip.isMetadataName("assets/songs/abc/slst"))
        assertTrue(ResourceZip.isMetadataName("abc/song.json"))
        assertFalse(ResourceZip.isMetadataName("assets/songs/pack/select_base.png"))
        assertFalse(ResourceZip.isMetadataName("2.aff"))
    }

    @Test
    fun parsesAllFragmentShapes() {
        assertEquals("x", ResourceZip.parseSongFragment("""{"songs":[{"id":"x"}]}""".toByteArray())?.optString("id"))
        assertEquals("y", ResourceZip.parseSongFragment("""{"id":"y"}""".toByteArray())?.optString("id"))
        assertEquals("z", ResourceZip.parseSongFragment("""{"song":{"id":"z"}}""".toByteArray())?.optString("id"))
        assertNull(ResourceZip.parseSongFragment("这不是 json".toByteArray()))
    }

    /** 回归：以前只在「整串路径」开头判断 `.`，导致 `X/.outside` 切开后落成 `.outside` 被写进歌曲目录 */
    @Test
    fun rejectsUnsafeFileNamesAfterSplitting() {
        assertNull(ResourceZip.normalizeEntryName("X/.outside"))
        assertNull(ResourceZip.normalizeEntryName("assets/songs/abc/.outside"))
        assertNull(ResourceZip.normalizeEntryName("a/b/c/2.aff"))
        assertNotNull(ResourceZip.normalizeEntryName("X/2.aff"))
        assertTrue(ResourceZip.isSafeFileName("2.aff"))
        assertTrue(ResourceZip.isSafeFileName("base_256.jpg"))
        assertFalse(ResourceZip.isSafeFileName(".outside"))
        assertFalse(ResourceZip.isSafeFileName("a/b"))
        assertFalse(ResourceZip.isSafeFileName(""))
    }

    /** songdata.json 是常见的单曲数据文件，必须被识别为元数据（而不是当作资源写入目录） */
    @Test
    fun recognizesSongdataAsMetadata() {
        assertTrue(ResourceZip.isMetadataName("songdata.json"))
        assertTrue(ResourceZip.isMetadataName("SONGDATA.JSON"))
        assertTrue(ResourceZip.isMetadataName("mysong/songdata.json"))
        assertTrue(ResourceZip.isMetadataName("songdata"))
        assertNull(ResourceZip.normalizeEntryName("songdata.json"))
        assertNull(ResourceZip.normalizeEntryName("mysong/songdata.json"))
    }
}
