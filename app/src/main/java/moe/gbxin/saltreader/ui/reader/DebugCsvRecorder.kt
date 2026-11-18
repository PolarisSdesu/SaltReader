package moe.gbxin.saltreader.ui.reader

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试面板的 CSV 录制器：每采样一次写一行，直接落到应用的外部私有目录
 * （`Android/data/<pkg>/files/debug/`），不用 adb root 也能 `adb pull` 出来。
 *
 * 只在用户按下「开始录制」时创建文件；停止或离开阅读器时 flush + close。
 */
internal class DebugCsvRecorder(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var startedAt = 0L

    var file: File? = null
        private set
    var rows: Int = 0
        private set

    val isRecording: Boolean get() = writer != null

    fun start(bookLabel: String): File? {
        stop()
        val dir = context.getExternalFilesDir("debug") ?: File(context.filesDir, "debug")
        if (!dir.exists() && !dir.mkdirs()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = bookLabel.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(40).ifBlank { "book" }
        val target = File(dir, "reader-$safe-$stamp.csv")
        return runCatching {
            val out = target.bufferedWriter()
            out.appendLine(
                "elapsed_ms,renderer,page,pages,percent,fps,frame_ms,max_frame_ms," +
                    "java_used_mb,java_max_mb,native_mb,pss_mb,gc_count,gc_time_ms,threads",
            )
            out.flush()
            writer = out
            file = target
            rows = 0
            startedAt = SystemClock.elapsedRealtime()
            target
        }.getOrNull()
    }

    fun append(
        renderer: String,
        page: Int,
        pages: Int,
        ratio: Float,
        stats: ReaderDebugStats,
    ) {
        val out = writer ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val line = buildString {
            append(elapsed)
            append(',')
            append(renderer.replace(',', ' '))
            append(',')
            append(page)
            append(',')
            append(pages)
            append(',')
            append((ratio.coerceIn(0f, 1f) * 100f).toInt())
            append(',')
            append(num(stats.fps))
            append(',')
            append(num(stats.frameMs))
            append(',')
            append(num(stats.worstFrameMs))
            append(',')
            append(num(stats.javaUsedMb))
            append(',')
            append(num(stats.javaMaxMb))
            append(',')
            append(num(stats.nativeMb))
            append(',')
            append(num(stats.pssMb))
            append(',')
            append(stats.gcCount)
            append(',')
            append(stats.gcTimeMs)
            append(',')
            append(stats.threads)
        }
        runCatching {
            out.appendLine(line)
            out.flush()
            rows++
        }
    }

    fun stop() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    private fun num(value: Float): String = String.format(Locale.US, "%.2f", value)
}
