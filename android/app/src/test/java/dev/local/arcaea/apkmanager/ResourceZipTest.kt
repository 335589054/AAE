package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.JsonNumber
import dev.local.arcaea.apkmanager.core.ResourceZip
import dev.local.arcaea.apkmanager.core.Song
import dev.local.arcaea.apkmanager.core.isChartEmpty
import dev.local.arcaea.apkmanager.core.parseSongBpm
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

    /** 社区常见的「标题目录 + 歌曲id目录」嵌套打包（如 Lost Requiem.zip），旧实现会整包丢弃 */
    @Test
    fun normalizesNestedTitleSongFolderLayout() {
        assertEquals("3.aff", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/3.aff"))
        assertEquals("base.ogg", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/base.ogg"))
        assertEquals("1080_base.jpg", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/1080_base.jpg"))
        // 标题目录下的背景图：先按文件名收下，随后由 AppRepository 依 songlist 的 bg 字段归类到
        // assets/img/bg/1080/
        assertEquals("djmax_wagd.jpg", ResourceZip.normalizeEntryName("Lost Requiem/djmax_wagd.jpg"))
        // 同目录里的非资源文件仍忽略
        assertNull(ResourceZip.normalizeEntryName("Lost Requiem/P.S..txt"))
    }

    /** 用两个真实样本 zip 的条目清单验证兼容性 */
    @Test
    fun supportsBothCommonSampleLayouts() {
        // zipsample/latentduality.zip：扁平结构 + songlist.json
        assertEquals("4.aff", ResourceZip.normalizeEntryName("4.aff"))
        assertEquals("base.ogg", ResourceZip.normalizeEntryName("base.ogg"))
        assertEquals("base.jpg", ResourceZip.normalizeEntryName("base.jpg"))
        assertEquals("base_256.jpg", ResourceZip.normalizeEntryName("base_256.jpg"))
        assertNull(ResourceZip.normalizeEntryName("songlist.json")) // 由调用方作为元数据处理
        assertTrue(ResourceZip.isMetadataName("songlist.json"))

        // zipsample/Lost Requiem.zip：标题目录 + 歌曲id目录
        assertNull(ResourceZip.normalizeEntryName("Lost Requiem/")) // 目录条目
        assertNull(ResourceZip.normalizeEntryName("Lost Requiem/songlist"))
        assertEquals("3.aff", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/3.aff"))
        assertEquals("base.ogg", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/base.ogg"))
        assertEquals("1080_base.jpg", ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/1080_base.jpg"))
        assertEquals(
            "1080_base_256.jpg",
            ResourceZip.normalizeEntryName("Lost Requiem/lostrequiem/1080_base_256.jpg"),
        )
        assertEquals("djmax_wagd.jpg", ResourceZip.normalizeEntryName("Lost Requiem/djmax_wagd.jpg"))
        assertNull(ResourceZip.normalizeEntryName("P.S..txt"))
    }

    @Test
    fun ignoresSystemAndOutOfScopeEntries() {
        assertNull(ResourceZip.normalizeEntryName("__MACOSX/foo.txt"))
        assertNull(ResourceZip.normalizeEntryName("a/b/c/d/2.aff")) // 层级过深
        assertNull(ResourceZip.normalizeEntryName("assets/img/bg/1080/x.jpg")) // 非歌曲目录
        assertNull(ResourceZip.normalizeEntryName("META-INF/CERT.RSA"))
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
        assertTrue(ResourceZip.isMetadataName("songlist.json")) // 如 latentduality.zip
        assertTrue(ResourceZip.isMetadataName("latentduality/songlist.json"))
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
        assertNull(ResourceZip.normalizeEntryName("a/b/c/d/2.aff")) // 层级过深
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
        // 兼容常见变体命名（历史上出现过 songdata..json / songdata_v2.json）
        assertTrue(ResourceZip.isMetadataName("songdata..json"))
        assertTrue(ResourceZip.isMetadataName("mysong/songdata_v2.json"))
        assertNull(ResourceZip.normalizeEntryName("songdata.json"))
        assertNull(ResourceZip.normalizeEntryName("mysong/songdata.json"))
        assertNull(ResourceZip.normalizeEntryName("mysong/songdata..json"))
    }

    /** 空谱面检测：只有 AudioOffset/timing 视为空，含 # 音符命令则非空 */
    @Test
    fun detectsEmptyChart() {
        val emptyFtr = "AudioOffset:611\n-\ntiming(0,180.00,4.00);\r\n"
        assertTrue("只有 timing 的谱面应判为空", isChartEmpty(emptyFtr))

        val real = "AudioOffset:0\n-\ntiming(0,187.20,4.00);\r\n#n(0,0);\r\n(353,1);\r\n"
        assertFalse("含 # 音符命令的谱面不应判为空", isChartEmpty(real))
    }

    /**
     * 回归：Never Say Goodbye 等真实谱面带 `timinggroup(){…}` 块、缩进和地面 note，
     * 但本身大量 arc/hold/(tick,lane)，绝不能被打成「空谱面」。
     */
    @Test
    fun neverFlagsFullChartWithTiminggroupsAsEmpty() {
        val chart = """
            AudioOffset:0
            -
            timing(0,178.20,4.00);
            arc(3095,3432,0.00,0.50,sisi,1.00,0.00,0,none,false);
            (3264,3);
            hold(9156,9745,2);
            arc(5116,5790,-0.50,1.50,s,1.00,1.00,1,none,true)[arctap(5453)];
            timinggroup(){
              timing(0,178.20,4.00);
            };
        """.trimIndent()
        assertFalse("含 arc/hold/地面 note 的谱面不应判为空", isChartEmpty(chart))
    }

    /** timing 命令内有括号（如 timinggroup 的多行写法）时也不能触发空谱面误判 */
    @Test
    fun nestedTimingParensStillRecognized() {
        val chart = "AudioOffset:0\n-\ntiming(0,178.20,4.00);\n(123,1);\n#tap(400,1);\n"
        assertFalse("至少有一个音符的谱面不应判为空", isChartEmpty(chart))
    }

    /** BPM 识别：range 取全部 timing 的 min-max，bpm_base 取累计占时最长的 BPM */
    @Test
    fun bpmParsesRangeAndDominantBase() {
        val chart = """
            timing(0,180.00,4.00);
            timing(4800,178.00,4.00);
            timing(100000,178.00,4.00);
            timing(120000,90.00,4.00);
            timing(120500,170.00,4.00);
        """.trimIndent()
        val info = parseSongBpm(chart)
        assertNotNull(info)
        assertEquals("90-180", info!!.range)
        assertEquals(178.0, info.base, 0.001)
    }

    @Test
    fun bpmSingleValueAndRange() {
        val single = "timing(0,178.20,4.00);\n"
        val one = parseSongBpm(single)!!
        assertEquals("178.2", one.range)
        assertEquals(178.2, one.base, 0.001)

        val varied = "timing(0,174.00,4.00);\ntiming(9600,176.00,4.00);\n"
        val two = parseSongBpm(varied)!!
        assertEquals("174-176", two.range)
    }

    /** 用真实的 songdata.json 结构验证 mergeSongFragment 能填充标题/曲师并保留难度列表 */
    @Test
    fun mergeSongdataFillsTitleArtistAndDifficulties() {
        val songdata = """
            {
            	"id": "everythingbk",
            	"title_localized": { "en": "Everything B.K." },
            	"artist": "nora2r",
            	"bpm": "180",
            	"bpm_base": 180,
            	"set": "base",
            	"purchase": "",
            	"audioPreview": 71278,
            	"audioPreviewEnd": 93944,
            	"side": 1,
            	"bg": "single2_conflict",
            	"date": 1782789460,
            	"version": "",
            	"difficulties": [
            		{ "ratingClass": 0, "chartDesigner": "", "jacketDesigner": "", "rating": 0 },
            		{ "ratingClass": 1, "chartDesigner": "", "jacketDesigner": "", "rating": 0 },
            		{ "ratingClass": 2, "chartDesigner": "Everything B.P.C.", "jacketDesigner": "", "rating": 10 }
            	]
            }
        """.trimIndent().toByteArray()

        val fragment = ResourceZip.parseSongFragment(songdata)!!
        val song = Song.template("everythingbk", "base", "占位标题")
        ResourceZip.mergeSongFragment(song, fragment)

        // id 与 set 必须以用户填写的为准，不能被 songdata 覆盖
        assertEquals("everythingbk", song.id)
        assertEquals("base", song.set)

        // 基本信息自动填充
        assertEquals("Everything B.K.", song.title("en"))
        assertEquals("nora2r", song.artist)

        // 难度列表整体来自 songdata（自动添加难度）
        assertEquals(3, song.difficultyList().size)
        val ftr = song.difficultyList().first { (it["ratingClass"] as JsonNumber).toInt() == 2 }
        assertEquals(10, (ftr["rating"] as JsonNumber).toInt())
    }
}
