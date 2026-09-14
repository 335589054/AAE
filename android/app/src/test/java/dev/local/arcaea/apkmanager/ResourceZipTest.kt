package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.ResourceZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
