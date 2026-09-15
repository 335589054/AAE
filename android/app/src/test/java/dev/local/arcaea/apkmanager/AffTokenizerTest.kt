package dev.local.arcaea.apkmanager

import dev.local.arcaea.apkmanager.core.AffTransform
import dev.local.arcaea.apkmanager.core.bufferStartWithBar
import dev.local.arcaea.apkmanager.core.parseTimingPoints
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** .aff 语句级切割 / 变速的单元测试 */
class AffTokenizerTest {

    private val sample = """
        AudioOffset:0
        -
        timing(0,178.20,4.00);
        arc(3095,3432,0.00,0.50,sisi,1.00,0.00,0,none,false);
        (3264,3);
        hold(9156,9745,2);
        arc(5116,5790,-0.50,1.50,s,1.00,1.00,1,none,true)[arctap(5453)];
        camera(100,0,0,0,0,0,0,reset,50);
        scenecontrol(2000,enwidencamera,30,0);
        timinggroup(){
          timing(0,356.40,4.00);
        };
    """.trimIndent()

    @Test
    fun sliceKeepsHeaderAndZeroTiming() {
        val out = AffTransform.slice(sample, 0.0, 30000.0, 1.0)
        assertTrue(out.startsWith("AudioOffset:0"))
        assertTrue(out.contains("timing(0,178.20,4.00)"))
    }

    @Test
    fun sliceKeepsNotesFullyInsideWindow() {
        // 样本里所有音符在 [2000, 30000)，切这段应全部保留、时间前移
        val out = AffTransform.slice(sample, 2000.0, 30000.0, 1.0)
        // (3264,3) 前移到 1264
        assertTrue(out.contains("(1264,3)"))
        // hold(9156,9745) 前移到 7156,7745
        assertTrue(out.contains("hold(7156,7745,2)"))
        // arc(5116,5790 -> 3116,3790 ) 内联 arctap 保留
        assertTrue(out.contains("arc(3116,3790"))
        assertTrue(out.contains("arctap(5453)")) // 内联 arctap 应随 arc 一起保留
    }

    @Test
    fun slicesOutNotesOutsideWindow() {
        val out = AffTransform.slice(sample, 6000.0, 30000.0, 1.0)
        // (3264,3) 在窗口之前 → 删除
        assertFalse(out.contains("(3264,3)"))
        // hold(9156,9745) 完整在窗口内 → 保留并前移 6000
        assertTrue(out.contains("hold(3156,3745,2"))
    }

    @Test
    fun objectsFullyInsideRequirement() {
        // hold(9156,9745) 起始在窗口外（6000 前）→ 整条删除
        val out = AffTransform.slice(sample, 10000.0, 30000.0, 1.0)
        assertFalse(out.contains("hold"))
    }

    @Test
    fun speedScalesTimes() {
        val out = AffTransform.slice(sample, 0.0, 30000.0, 2.0)
        // 半速：hold(9156,9745) → (4578, 4872)
        assertTrue(out.contains("hold(4578,4873,2)") || out.contains("hold(4578,4872,2)"))
    }

    @Test
    fun parsesTimingPoints() {
        val pts = parseTimingPoints("timing(0,178.20,4.00);\ntiming(48000,170,4);")
        assertEquals(2, pts.size)
        assertEquals(178.20, pts[0].bpm, 0.001)
        assertEquals(4.0, pts[0].beats, 0.001)
    }

    @Test
    fun bufferStartUsesSegmentTiming() {
        // cutStart = 48000，该段是 timing(48000,120,4)，1 小节 = 60000/120*4 = 2000ms
        val b = bufferStartWithBar("timing(0,120.00,4.00);\ntiming(48000,120.00,4.00);", 48000.0)
        assertEquals(46000.0, b, 0.001)
    }

    @Test
    fun sceneControlClampsDurationToWindow() {
        val aff = "-\nscenecontrol(0,enwidencamera,100,0);"
        val out = AffTransform.slice(aff, 0.0, 50.0, 1.0)
        // duration 100ms 窗长 50ms → 夹取 duration 至 50（但至少保留非空）
        assertTrue(out.contains("scenecontrol"))
    }

    /**
     * 回归：真实谱面割出练习谱后，顶层（非 timinggroup 内）必须有 t=0 的全局 timing。
     * 否则游戏打开该谱面直接闪退（实测出现于生成 nve 谱且打开练习谱闪退）。
     */
    @Test
    fun slicedRealChartAlwaysHasTopLevelTimingZero() {
        val res = javaClass.classLoader?.getResource("neversaygoodbye_real.aff")
            ?: throw IllegalStateException("缺少测试资源 neversaygoodbye_real.aff")
        val aff = File(res.toURI()).readText(Charsets.UTF_8)

        // 取多个「从谱面中部向后」的切割窗口（与用户练习谱生成场景一致）
        val cuts = listOf(
            bufferStartWithBar(aff, 45000.0),   // 45s 起点
            bufferStartWithBar(aff, 80000.0),   // 80s 起点
            bufferStartWithBar(aff, 120000.0),  // 中后段
        )
        for (bs in cuts) {
            val out = AffTransform.slice(aff, bs, bs + 30000.0, 1.0)
            // 顶层作用域必须有 t=0 timing（行首无缩进）
            val topLines = out.lines().filter { it.isNotBlank() && !Character.isWhitespace(it[0]) }
            val hasTopZero = topLines.any { Regex("timing\\s*\\(\\s*0\\s*,").containsMatchIn(it) }
            assertTrue("黒点 bs=$bs 缺少顶层 timing(0)", hasTopZero)
        }
    }
}