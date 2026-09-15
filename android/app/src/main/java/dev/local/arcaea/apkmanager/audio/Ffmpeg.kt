package dev.local.arcaea.apkmanager.audio

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 音频切割 / 变速，基于一个**静态编译的 ffmpeg 可执行文件**（随 APK 打进 assets）。
 *
 * 为什么用静态二进制而不是 ffmpeg-kit JNI：
 * - ffmpeg-kit 在 Android 15/16（API 35+）上因 classloader 命名空间隔离，`libffmpegkit.so` 加载时
 *   找不到 libc++ 的 `__gxx_personality_v0`，会报 "FFmpegKit failed to start"。
 * - 静态 ffmpeg（`--enable-static --disable-shared`，自包含 libc++/unwind）是独立进程，由
 *   [ProcessBuilder] 启动，完全绕开 JNI 命名空间，在任何系统版本都稳定。
 *
 * 运行方式：二进制随 jniLibs 打进 APK、解压到可执行的 nativeLibraryDir，用 ProcessBuilder 同步执行。
 */
object Ffmpeg {

    private val tagCounter = AtomicInteger(0)

    @Volatile
    private var preparedPath: File? = null

    /** 输出文件唯一命名，避免并发/重复任务覆盖。 */
    fun uniqueFile(dir: File, base: String, ext: String): File {
        return File(dir, "$base-${System.nanoTime()}-${tagCounter.incrementAndGet()}.$ext")
    }

    /** 确保 ffmpeg 可执行文件落在可执行目录，返回其路径。 */
    fun ensureBinary(context: Context): File {
        preparedPath?.let { if (it.exists()) return it }

        // ffmpeg 以 jniLibs 形式打进 APK，解压到 nativeLibraryDir（该目录允许执行，
        // 而 files/cache 等 app 私有目录是 noexec，直接拷过去的二进制会被 SELinux 拒绝执行，
        // 报 error=13 Permission denied）。直接从那里运行即可。
        val target = File(context.applicationInfo.nativeLibraryDir, "libffmpegnative.so")
        if (!target.canExecute()) {
            target.setReadable(true, false)
            target.setExecutable(true, false)
        }
        preparedPath = target
        return target
    }

    /**
     * 切割并变速一段音频，输出 .ogg。
     *
     * @param input 源音频（.ogg 或任意 ffmpeg 可解码格式）
     * @param output 输出 .ogg 路径
     * @param startMs 起点（毫秒）
     * @param lenMs 长度（毫秒）
     * @param speed 变速倍速（<1 慢放、>1 快放）；atempo 每次只支持 0.5..2.0，超范围自动级联
     * @param sampleRate 输出采样率（Arcaea 为 44100）
     * @param onProgress 输出时长进度回调（0.0..1.0，基于变速后的时长），可空
     * @param onLog 可选日志回调
     */
    fun transform(
        context: Context,
        input: File,
        output: File,
        startMs: Long,
        lenMs: Long,
        speed: Double,
        sampleRate: Int = 44100,
        onProgress: ((Float) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ) {
        val ffmpeg = ensureBinary(context)
        val args = buildArgs(input, output, startMs, lenMs, speed, sampleRate)
        onLog?.invoke("ffmpeg " + shellQuoteAll(args))
        // 变速后的输出时长 = 输入时长 / speed（用于把 out_time 折算成百分比）
        val totalMsOut = if (lenMs > 0) (lenMs / speed).toLong() else 0L
        runProcess(ffmpeg, args, totalMsOut, onProgress ?: { _ -> }, onLog)
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw IOException("音频处理完成后未生成有效输出文件")
        }
    }

    // ---------------- 进程执行 ----------------

    private fun runProcess(ffmpeg: File, args: List<String>, totalMs: Long, onProgress: (Float) -> Unit, onLog: ((String) -> Unit)?) {
        val pb = ProcessBuilder(listOf(ffmpeg.absolutePath) + args)
        // 合并 stderr 到 stdout，同时解析其中的 `-progress pipe:1` 输出（out_time_ms=… / out_time_us=…）
        pb.redirectErrorStream(true)
        var process: Process? = null
        try {
            process = pb.start()
            val tail = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            val drain = Thread {
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("out_time_us=") ->
                                line.removePrefix("out_time_us=").toDoubleOrNull()?.let { us ->
                                    reportProgress((us / 1000.0), totalMs, onProgress)
                                }
                            line.startsWith("out_time_ms=") ->
                                line.removePrefix("out_time_ms=").toDoubleOrNull()?.let { ms ->
                                    reportProgress(ms, totalMs, onProgress)
                                }
                            tail.length < 6000 -> tail.append(line).append('\n')
                        }
                    }
                } catch (_: Throwable) { }
            }
            drain.isDaemon = true
            drain.start()

            val done = process.waitFor(5, TimeUnit.MINUTES)
            if (!done || process.isAlive) {
                process.destroyForcibly()
                throw IOException("ffmpeg 处理超时（5 分钟），已终止")
            }
            reader.close()
            drain.join(500)
            onLog?.invoke(tail.toString().trim())

            val rc = process.exitValue()
            if (rc != 0) {
                throw IOException(buildString {
                    append("音频处理失败（ffmpeg 返回码 $rc）")
                    if (tail.isNotBlank()) append("\n").append(tail.trim().take(1200))
                })
            }
        } catch (e: IOException) {
            process?.destroyForcibly()
            throw e
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IOException("音频处理被中断")
        }
    }

    /** 把已输出的毫秒换算成总时长百分比，回调上限封顶在 0.99（避免提前到 100） */
    private fun reportProgress(outMs: Double, totalMs: Long, onProgress: (Float) -> Unit) {
        if (totalMs <= 0) return
        val frac = (outMs / totalMs).toFloat().coerceIn(0f, 0.99f)
        onProgress(frac)
    }

    // ---------------- 命令构建 ----------------

    private fun buildArgs(
        input: File, output: File, startMs: Long, lenMs: Long, speed: Double, sampleRate: Int,
    ): List<String> {
        val args = mutableListOf("-y")

        if (startMs > 0) args += listOf("-ss", formatSeconds(startMs / 1000.0))
        args += listOf("-i", input.absolutePath)
        if (lenMs > 0) args += listOf("-t", formatSeconds(lenMs / 1000.0))

        val af = atempoChain(speed)
        if (af.isNotEmpty()) args += listOf("-af", af)

        args += listOf(
            "-ar", sampleRate.toString(),
            "-c:a", "libvorbis",
            "-q:a", "5",
            "-map_metadata", "-1",
            "-progress", "pipe:1",
            output.absolutePath,
        )
        return args
    }

    /**
     * 把 speed 拆成若干 in-[0.5,2.0] 的 atempo 级联（atempo 通过重采样实现，一次只支持 0.5..2.0）。
     * 0.5 / 0.75 / 0.8 / 0.9 / 1.0 / 1.1 / 1.25 / 1.5 / 2.0 都在区间内，单次即可；更大/更小才级联。
     */
    private fun atempoChain(speed: Double): String {
        if (speed <= 0.0 || kotlin.math.abs(speed - 1.0) < 1e-6) return ""
        val filters = ArrayList<String>()
        var target = speed
        var guard = 0
        while (kotlin.math.abs(target - 1.0) > 1e-6 && guard < 8) {
            val f = when {
                target > 1.0 -> minOf(target, 2.0)
                else -> maxOf(target, 0.5)
            }
            filters.add("atempo=" + String.format("%.4f", f))
            target /= f
            guard++
        }
        return filters.joinToString(",")
    }

    private fun shellQuoteAll(args: List<String>): String =
        args.joinToString(" ") { arg -> shellQuote(arg) }

    private fun shellQuote(arg: String): String {
        if (arg.none { it == ' ' || it == '"' || it == '\'' }) return arg
        return "\"" + arg.replace("\"", "\\\"") + "\""
    }

    private fun formatSeconds(sec: Double): String = String.format("%.3f", sec)
}