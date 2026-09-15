package dev.local.arcaea.apkmanager.core

import kotlin.math.roundToLong

/**
 * .aff 谱面的「语句级」解析器与变换器。
 *
 * 设计要点：**时间窗判断靠参数解析；改写靠对原始文本做正则定位替换**。
 * 这样 arc 的内联 `[arctap(...)]`、timing 的堆叠三元组、缩进、注释、未知命令都能被完整保留，
 * 只改我们真正要动的时间/持续时长字段。
 *
 * 只处理五种「有时间语义」的语句，其余原样透传：
 * - timing(t,bpm,beats)（可堆叠多组）
 * - 地面 note：(t,lane);
 * - hold(t1,t2,lane); / arc(t1,t2,...)[arctap(...),...];（物件，必须完整落在窗口内）
 * - flick(t,...);
 * - camera(t,...,duration); / scenecontrol(t,cmd,duration,bool);（控制，用 duration 求活性区间，窗口交集夹取）
 * - timinggroup(...){ body }（body 递归处理）
 */

/** 语句类型 */
enum class AffKind { HEADER, TIMING, GROUND, HOLD, ARC, FLICK, CAMERA, SCENECONTROL, TIMINGGROUP, OTHER }

/** duration 字段单位 */
enum class AffDurUnit { MS, SECOND }

/** 一条谱面语句 */
data class AffStmt(
    val kind: AffKind,
    /** 原始文本（含结尾 `;`；timinggroup 含完整 `{...}`） */
    val raw: String,
    /** 是否为已知「有时间语义」命令，false 则整条透传不参与变换 */
    val timeAware: Boolean = true,
    /** 计算时间窗所需的活跃区间（毫秒） */
    val spanMs: DoubleSpan? = null,
    /** timinggroup 内部的文本（不含外层 `timinggroup(...){}`），用于递归切片；其余类型为 null */
    val groupBody: String? = null,
    /** 控制语句的 duration 下标与单位 */
    val durIdx: Int = -1,
    val durUnit: AffDurUnit = AffDurUnit.MS,
)

/** 闭合区间 */
class DoubleSpan(val start: Double, val end: Double) {
    val isEmpty get() = end <= start
    fun intersects(o: DoubleSpan) = start < o.end && o.start < end
    fun intersection(o: DoubleSpan): DoubleSpan =
        DoubleSpan(maxOf(start, o.start), minOf(end, o.end))
}

/** 解析结果 */
class AffScript(val head: String, val stmts: List<AffStmt>)

/** timing 点 */
data class TimingPoint(val t: Double, val bpm: Double, val beats: Double)

object AffScanner {

    /** 把 aff 按显式换行切块，同一块里最多一条「时间语义」语句；块与块间不会拆坏 timinggroup */
    fun encode(text: String): AffScript {
        val lines = text.lines()
        var cutIdx = -1
        for ((i, l) in lines.withIndex()) if (l.trim() == "-") { cutIdx = i; break }
        val head = if (cutIdx >= 0) lines.subList(0, cutIdx + 1).joinToString("\n") else ""
        val bodyAll = if (cutIdx >= 0) lines.subList(cutIdx + 1, lines.size).joinToString("\n") else ""

        val stmts = ArrayList<AffStmt>()
        var i = 0
        val n = bodyAll.length
        while (i < n) {
            if (bodyAll[i] == '\n') { i++; continue }
            if (bodyAll[i].isWhitespace()) { i++; continue }

            val lineEnd = bodyAll.indexOf('\n', i).let { if (it < 0) n else it }
            val seg = bodyAll.substring(i, lineEnd)

            // timinggroup{...} 可能跨行：找到匹配的 } 再吃进 body
            val tg = Regex("^\\s*timinggroup\\s*\\(\\s*([^)]*)\\s*\\)\\s*\\{").find(seg)
            if (tg != null) {
                val close = findMatchingBrace(bodyAll, i + seg.indexOf('{'))
                if (close >= 0) {
                    val bodyStart = i + seg.indexOf('{') + 1
                    val body = bodyAll.substring(bodyStart, close)
                    val afterIdx = bodyAll.indexOf(';', close + 1).let { if (it < 0) n else it + 1 }
                    val raw = bodyAll.substring(i, afterIdx)
                    stmts.add(AffStmt(AffKind.TIMINGGROUP, raw,
                        spanMs = groupSpan(body), groupBody = body))
                    i = afterIdx
                    continue
                }
            }

            // 单条时间语句（程序按"行首锚定正则"识别；不命中则整行透传）
            val stmt = matchStmt(seg) ?: AffStmt(AffKind.OTHER, seg, timeAware = false)
            stmts.add(stmt)
            i = lineEnd + 1
        }
        return AffScript(head, stmts)
    }

    private fun matchStmt(seg: String): AffStmt? {
        val trimmed = seg.trimStart()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("#")) return null // 注释

        TIMING_RE.find(trimmed)?.let { m ->
            val span = timingSpan(trimmed)
            return AffStmt(AffKind.TIMING, trimmed, spanMs = span)
        }
        GROUND_RE.find(trimmed)?.let { m ->
            val t = m.groupValues[1].toDoubleOrNull() ?: return AffStmt(AffKind.GROUND, trimmed)
            return AffStmt(AffKind.GROUND, trimmed, spanMs = DoubleSpan(t, t))
        }
        HOLD_RE.find(trimmed)?.let { m ->
            val t1 = m.groupValues[1].toDoubleOrNull() ?: return AffStmt(AffKind.HOLD, trimmed)
            val t2 = m.groupValues[2].toDoubleOrNull() ?: return AffStmt(AffKind.HOLD, trimmed)
            return AffStmt(AffKind.HOLD, trimmed, spanMs = DoubleSpan(t1, t2))
        }
        ARC_RE.find(trimmed)?.let { m ->
            val toks = splitTop(m.groupValues[1])
            val t1 = toks.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return AffStmt(AffKind.ARC, trimmed)
            val t2 = toks.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return AffStmt(AffKind.ARC, trimmed)
            return AffStmt(AffKind.ARC, trimmed, spanMs = DoubleSpan(t1, t2))
        }
        FLICK_RE.find(trimmed)?.let { m ->
            val t = parametr0(m.groupValues[1]) ?: return AffStmt(AffKind.FLICK, trimmed)
            return AffStmt(AffKind.FLICK, trimmed, spanMs = DoubleSpan(t, t))
        }
        CAMERA_RE.find(trimmed)?.let { m ->
            val toks = splitTop(m.groupValues[1])
            val t = toks.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return AffStmt(AffKind.CAMERA, trimmed)
            val dur = toks.getOrNull(8)?.trim()?.toDoubleOrNull() ?: 0.0
            return AffStmt(AffKind.CAMERA, trimmed, spanMs = DoubleSpan(t, t + dur), durIdx = 8, durUnit = AffDurUnit.MS)
        }
        SCENECONTROL_RE.find(trimmed)?.let { m ->
            val toks = splitTop(m.groupValues[1])
            val t = toks.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return AffStmt(AffKind.SCENECONTROL, trimmed)
            val dur = toks.getOrNull(2)?.trim()?.toDoubleOrNull() ?: 0.0
            val cmd = toks.getOrNull(1)?.trim()?.lowercase() ?: "trackdisplay"
            val unit = if (cmd in SEC_CONTROL_CMDS) AffDurUnit.SECOND else AffDurUnit.MS
            val mul = if (unit == AffDurUnit.SECOND) 1000.0 else 1.0
            return AffStmt(AffKind.SCENECONTROL, trimmed, spanMs = DoubleSpan(t, t + dur * mul), durIdx = 2, durUnit = unit)
        }
        return null
    }

    private fun parametr0(payload: String): Double? {
        val tok = splitTop(payload).getOrNull(0)?.trim() ?: return null
        return tok.toDoubleOrNull()
    }

    /** timing 的活跃区间：从「首个三元组」的 t 到「末尾三元组」的 t（近似，主要用于包含判断） */
    private fun timingSpan(raw: String): DoubleSpan {
        val segs = splitTimingSegments(raw)
        if (segs.isEmpty()) return DoubleSpan(0.0, 0.0)
        val ts = segs.map { it.first }
        return DoubleSpan(ts.min(), ts.max())
    }

    private fun groupSpan(body: String): DoubleSpan? {
        val span = AffScanner.encode("\n" + body).stmts.mapNotNull { it.spanMs?.let { listOf(it.start, it.end) } }.flatten()
        return if (span.isEmpty()) null else DoubleSpan(span.min(), span.max())
    }

    private val SEC_CONTROL_CMDS = setOf("redline", "arcahvdistort", "arcahvdebris")

    private fun findMatchingBrace(text: String, openIdx: Int): Int {
        var depth = 1
        var i = openIdx + 1
        while (i < text.length) {
            when (text[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
            i++
        }
        return -1
    }
}

// 行首锚定的命令正则
private val TIMING_RE = Regex("^timing\\s*\\(.*[\\);]")
private val GROUND_RE = Regex("^\\(\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\);")
private val HOLD_RE = Regex("^hold\\s*\\(\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,[^)]*\\);")
private val ARC_RE = Regex("^arc\\s*\\(([^)]*)\\)")
private val FLICK_RE = Regex("^flick\\s*\\(([^)]*)\\);")
private val CAMERA_RE = Regex("^camera\\s*\\(([^)]*)\\);")
private val SCENECONTROL_RE = Regex("^scenecontrol\\s*\\(([^)]*)\\);")

private val timingSegRe = Regex("timing\\s*\\(\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*\\)")

/** 从 timing 原始文本提取各三元组的 (t,bpm,beats) */
private fun splitTimingSegments(raw: String): List<Triple<Double, String, String>> {
    val out = ArrayList<Triple<Double, String, String>>()
    for (m in timingSegRe.findAll(raw)) {
        out.add(Triple(m.groupValues[1].toDouble(), m.groupValues[2], m.groupValues[3]))
    }
    return out
}

/** 顶层（括号外）按逗号切分 */
private fun splitTop(s: String): List<String> {
    val out = ArrayList<String>()
    var d = 0; val sb = StringBuilder()
    for (c in s) {
        when (c) { '(' -> d++; ')' -> d-- }
        if (c == ',' && d == 0) { out.add(sb.toString()); sb.setLength(0) }
        else sb.append(c)
    }
    out.add(sb.toString())
    return out
}

/**
 * 变换器：切割 + 变速。
 *
 * @param bufferStart 窗口起点（毫秒，须 >= 0，建议用 bufferStartWithBar）
 * @param cutEnd 窗口终点（毫秒，> bufferStart）
 * @param speed 倍速：<1 慢放、=1 不变、>1 快放；t' = (t - bufferStart) / speed
 */
object AffTransform {

    fun slice(text: String, bufferStart: Double, cutEnd: Double, speed: Double): String {
        val script = AffScanner.encode(text)
        val window = DoubleSpan(bufferStart, cutEnd)
        val out = StringBuilder()
        if (script.head.isNotEmpty()) { out.append(script.head); out.append('\n') }

        var body = ""
        for (stmt in script.stmts) {
            render(stmt, window, speed)?.let { body += "$it\n" }
        }
        val trimmed = body.trim('\n')
        out.append(trimmed)
        if (trimmed.isNotEmpty()) out.append('\n')

        // 保证顶层（timinggroup 之外）存在 t=0 的全局 timing —— Arcaea 主谱面必须由全局 timing 驱动，
        // 仅有 timinggroup 内的 timing 会导致游戏打不开。缺则用窗口起点对应的 bpm 补插一条。
        if (!hasTopLevelTimingZero(body)) {
            val (bpm, beats) = timingAt(text, bufferStart)
            out.append("timing(0," + fmt(bpm) + "," + fmt(beats) + ");\n")
        }
        return out.toString()
    }

    /** 渲染一条语句：null 表示删除 */
    private fun render(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        if (!stmt.timeAware) return stmt.raw
        return when (stmt.kind) {
            AffKind.TIMING -> renderTiming(stmt, window, speed)
            AffKind.GROUND, AffKind.FLICK -> renderInstant(stmt, window, speed)
            AffKind.HOLD, AffKind.ARC -> renderObject(stmt, window, speed)
            AffKind.CAMERA, AffKind.SCENECONTROL -> renderControl(stmt, window, speed)
            AffKind.TIMINGGROUP -> renderGroup(stmt, window, speed)
            else -> stmt.raw
        }
    }

    /** timing：只保留三元组 t 落在窗口内的那些；改写押到窗口内 */
    private fun renderTiming(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        var any = false
        val newParts = ArrayList<String>()
        for (seg in splitTimingSegments(stmt.raw)) {
            val t = seg.first
            if (t < window.start || t >= window.end) continue
            val newT = scale(t - window.start, speed)
            newParts.add("timing($newT,${seg.second},${seg.third})")
            any = true
        }
        if (!any) return null
        return "timing(" + newParts.joinToString("),(") + ");"
    }

    private fun renderInstant(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        val span = stmt.spanMs ?: return stmt.raw
        val t = span.start
        if (t < window.start || t >= window.end) return null
        val newT = scale(t - window.start, speed)
        return rewriteNumeric(stmt, listOf(newT.toString()))
    }

    private fun renderObject(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        val span = stmt.spanMs ?: return stmt.raw
        // 物件必须完整落在窗口内，否则删除
        if (span.start < window.start || span.end > window.end) return null
        val newT1 = scale(span.start - window.start, speed)
        val newT2 = scale(span.end - window.start, speed)
        return rewriteNumeric(stmt, listOf(newT1.toString(), newT2.toString()))
    }

    /** 控制：用 duration 求活性区间，与窗口求交集后夹取 */
    private fun renderControl(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        val span = stmt.spanMs ?: return stmt.raw
        if (!window.intersects(span)) return null
        val inter = window.intersection(span)
        val newT = scale(inter.start - window.start, speed)
        val newDurMs = inter.end - inter.start
        val newDur = if (stmt.durUnit == AffDurUnit.SECOND) newDurMs / 1000.0 else newDurMs
        return rewriteNumericAt(stmt, listOf(Pair(0, newT.toString()), Pair(stmt.durIdx, fmtDur(newDur))))
    }

    /** timinggroup：递归处理内部语句（arc/hold/note/timing 都要按窗口裁剪+缩放），
     *  只有内部仍有语句时才保留组；全空则整组删除。 */
    private fun renderGroup(stmt: AffStmt, window: DoubleSpan, speed: Double): String? {
        if (stmt.spanMs == null) return stmt.raw
        if (!window.intersects(stmt.spanMs)) return null
        val body = stmt.groupBody ?: return stmt.raw
        val newInner = sliceInnerText(body, window, speed)
        if (newInner.isBlank()) return null // 组内无保留内容，删整组
        return "timinggroup(){\n" + newInner + "\n};"
    }

    /** 递归切片一段文本（用于 timinggroup 内部），返回切片后的语句文本。 */
    private fun sliceInnerText(text: String, window: DoubleSpan, speed: Double): String {
        val script = AffScanner.encode(text)
        val out = StringBuilder()
        for (stmt in script.stmts) {
            render(stmt, window, speed)?.let { out.append('\n').append(it) }
        }
        return out.toString().trim('\n')
    }

    /** 在原始文本上改写前 n 个数字参数（把前 n 个逗号分隔项替换为 newVals） */
    private fun rewriteNumeric(stmt: AffStmt, newVals: List<String>): String {
        val raw = stmt.raw
        val openParen = raw.indexOf('(')
        if (openParen < 0) return raw
        val closeParen = matchingParen(raw, openParen)
        if (closeParen < 0) return raw
        val head = raw.substring(0, openParen + 1)
        val tail = raw.substring(closeParen)
        val content = raw.substring(openParen + 1, closeParen)
        val toks = splitTop(content).toMutableList()
        for ((i, v) in newVals.withIndex()) if (i < toks.size) toks[i] = v
        return head + toks.joinToString(",") + tail
    }

    private fun rewriteNumericAt(stmt: AffStmt, edits: List<Pair<Int, String>>): String {
        val raw = stmt.raw
        val openParen = raw.indexOf('(')
        val closeParen = matchingParen(raw, openParen)
        if (openParen < 0 || closeParen < 0) return raw
        val head = raw.substring(0, openParen + 1)
        val tail = raw.substring(closeParen)
        val content = raw.substring(openParen + 1, closeParen)
        val toks = splitTop(content).toMutableList()
        for ((idx, v) in edits) if (idx in toks.indices) toks[idx] = v
        return head + toks.joinToString(",") + tail
    }

    private fun matchingParen(s: String, open: Int): Int {
        var d = 0
        var i = open
        while (i < s.length) {
            when (s[i]) { '(' -> d++; ')' -> { d--; if (d == 0) return i } }
            i++
        }
        return -1
    }

    /**
     * 顶层（timinggroup 之外）是否存在 t=0 的 timing。
     * 用行首无缩进锚定：Arcaea 惯例里全局命令顶格写、timinggroup 内命令有缩进。
     * 避免把 timinggroup 内部的 `timing(0,...)` 误判为已具备全局 timing。
     */
    private fun hasTopLevelTimingZero(body: String): Boolean {
        for (line in body.split('\n')) {
            if (line.isBlank()) continue
            if (line[0].isWhitespace()) continue   // 缩进 → timinggroup 内，忽略
            if (Regex("timing\\s*\\(\\s*0\\s*,").containsMatchIn(line)) return true
        }
        return false
    }

    /** 取 t <= bufferStart 的最后一个 timing 的 (bpm, beats)；没有则回退 180/4 */
    private fun timingAt(text: String, bufferStart: Double): Pair<Double, Double> {
        val pts = parseTimingPoints(text)
        val seg = pts.filter { it.t <= bufferStart }.maxByOrNull { it.t }
            ?: pts.firstOrNull()
        return (seg?.bpm ?: 180.0) to (seg?.beats ?: 4.0)
    }

    private fun scale(offsetMs: Double, speed: Double): Long {
        val v = (offsetMs / speed).roundToLong()
        return if (v < 0) 0 else v
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
        else String.format("%.2f", v).trimEnd('0').trimEnd('.')

    /** duration 字段格式化：保留到 4 位小数，方便还原非整数秒 */
    private fun fmtDur(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString()
        else String.format("%.4f", v).trimEnd('0').trimEnd('.')
}

/**
 * 解析 aff 里所有 timing 点。
 */
fun parseTimingPoints(text: String): List<TimingPoint> {
    val out = ArrayList<TimingPoint>()
    for (m in timingSegRe.findAll(text)) {
        out.add(TimingPoint(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble()))
    }
    return out.sortedBy { it.t }
}

/**
 * 计算窗口起点（含 1 小节缓冲）。
 */
fun bufferStartWithBar(text: String, cutStart: Double, fallbackBpm: Double = 180.0, fallbackBeats: Double = 4.0): Double {
    val timings = parseTimingPoints(text)
    val seg = timings.filter { it.t <= cutStart }.maxByOrNull { it.t }
        ?: TimingPoint(0.0, fallbackBpm, fallbackBeats)
    val barMs = 60000.0 / seg.bpm * seg.beats
    return maxOf(0.0, cutStart - barMs)
}

/**
 * 谱面最晚时刻（毫秒）：所有语句活跃区间的最大 end（含 timing/note/arc/hold/camera/scenecontrol）。
 * 用于练习谱生成的区间选择滑块上限。
 */
fun chartEndMs(text: String): Double {
    var maxEnd = parseTimingPoints(text).maxOfOrNull { it.t } ?: 0.0
    for (stmt in AffScanner.encode(text).stmts) {
        stmt.spanMs?.let { if (it.end > maxEnd) maxEnd = it.end }
    }
    return maxEnd
}