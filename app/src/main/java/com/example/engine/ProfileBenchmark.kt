package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Test mode: benchmarks every (transport, browser-profile) combination through
 * the native engine and persists a human-readable result card + log into the
 * public Download folder. All work is done on IO — never call from Main.
 */
object ProfileBenchmark {

    data class Result(val protocol: String, val profile: String, val bytesPerSec: Long)

    data class Report(
        val results: List<Result>,
        val best: Result?,
        val logFile: File,
        val pngFile: File,
    )

    private fun outputDir(context: Context): File {
        // App-specific external Download dir: no storage permission required on
        // Android 11+/API 36, and visible in the Files app under Download.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        return File(base, "AetherIO/Benchmark").apply { mkdirs() }
    }

    suspend fun run(context: Context, url: String, netHandle: Long): Report =
        withContext(Dispatchers.IO) {
            val raw = AetherNativeBridge.benchmarkProfilesSafe(url, netHandle).orEmpty()
            val results = mutableListOf<Result>()
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    results += Result(
                        protocol = o.optString("protocol"),
                        profile = o.optString("profile"),
                        bytesPerSec = o.optLong("bytesPerSec"),
                    )
                }
            }
            val best = results.maxByOrNull { it.bytesPerSec }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = outputDir(context)

            val logFile = File(dir, "aetherio_benchmark_$stamp.log")
            val pngFile = File(dir, "aetherio_benchmark_$stamp.png")
            val human = results.sortedByDescending { it.bytesPerSec }
                .joinToString("\n") { r ->
                    String.format(
                        Locale.US, "%-8s %-12s %10.2f MB/s  %8.1f Mbps",
                        r.protocol, r.profile,
                        r.bytesPerSec / 1_048_576.0,
                        r.bytesPerSec * 8.0 / 1_000_000.0,
                    )
                }
            val header = buildString {
                append("AetherIO Profile Benchmark\n")
                append("Time: $stamp\nURL: $url\n")
                append("Best: ${best?.protocol}/${best?.profile} @ ${
                    String.format(Locale.US, "%.2f MB/s", (best?.bytesPerSec ?: 0L) / 1_048_576.0)
                }\n\n")
            }
            logFile.writeText(header + human + "\n")
            drawCard(pngFile, header, results.sortedByDescending { it.bytesPerSec })
            Report(results, best, logFile, pngFile)
        }

    private fun drawCard(file: File, header: String, sorted: List<Result>) {
        val w = 1000
        val h = 220 + sorted.size * 56
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(9, 14, 26))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 230, 200); textSize = 40f; isFakeBoldText = true
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 27f
        }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 170, 190); textSize = 22f
        }
        c.drawText("AetherIO · Auto Profile Benchmark", 40f, 60f, title)
        var y = 105f
        header.lines().drop(1).forEach { line ->
            c.drawText(line, 40f, y, dim); y += 30f
        }
        y += 20f
        val maxBps = sorted.firstOrNull()?.bytesPerSec?.coerceAtLeast(1) ?: 1
        sorted.forEachIndexed { idx, r ->
            val label = String.format(
                Locale.US, "%d. %s / %s  %6.2f MB/s",
                idx + 1, r.protocol, r.profile, r.bytesPerSec / 1_048_576.0,
            )
            c.drawText(label, 40f, y, body)
            val barW = (760 * (r.bytesPerSec.toDouble() / maxBps)).toFloat().coerceAtLeast(2f)
            c.drawRect(40f, y + 10f, 40f + barW, y + 26f, Paint().apply {
                color = if (idx == 0) Color.rgb(80, 230, 200) else Color.rgb(70, 110, 160)
            })
            y += 56f
        }
        FileOutputStreamCompat(file, bmp)
    }

    private fun FileOutputStreamCompat(file: File, bmp: Bitmap) {
        java.io.FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        bmp.recycle()
    }
}
