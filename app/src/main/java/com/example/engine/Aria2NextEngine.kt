package com.example.engine

import android.content.Context
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.EngineSettings
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Third download engine: aria2-next (https://github.com/AnInsomniacy/aria2-next),
 * the maintained aria2-compatible engine that powers Rayburst.
 *
 * CI bundles the prebuilt Android arm64 executable as an APK asset. On first use
 * it is extracted to app-private storage and run as a loopback-only JSON-RPC
 * daemon on port 16800 with a per-boot secret. Downloads are driven through the
 * standard aria2 JSON-RPC API (aria2.addUri / tellStatus / pause / remove).
 */
class Aria2NextEngine(
    private val context: Context,
    private val repository: DownloadRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val rpcPort = 16800
    private val rpcUrl = "http://127.0.0.1:$rpcPort/jsonrpc"
    private val gids = ConcurrentHashMap<String, String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var secret: String = ""

    @Volatile
    var lastError: String = ""
        private set

    fun isAvailable(): Boolean = binaryFile() != null

    private fun isElf(file: File): Boolean {
        if (!file.exists() || file.length() < 4096L) return false
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4) return false
                magic[0] == 0x7f.toByte() && magic[1] == 'E'.toByte() &&
                    magic[2] == 'L'.toByte() && magic[3] == 'F'.toByte()
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun binaryFile(): File? {
        val target = File(context.filesDir, "aria2-next")
        if (isElf(target)) return target
        return try {
            context.assets.open("aria2-next").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target.setExecutable(true, false)
            if (isElf(target)) {
                lastError = ""
                target
            } else {
                lastError = "Extracted aria2-next asset is not a valid ELF (${target.length()} bytes)"
                DiagnosticsCenter.log("ARIA2_ERROR", lastError)
                null
            }
        } catch (t: Throwable) {
            lastError = "Cannot extract aria2-next: ${t.message ?: t.javaClass.simpleName}"
            DiagnosticsCenter.log("ARIA2_ERROR", lastError)
            null
        }
    }

    private fun isProcessAlive(): Boolean {
        val p = process ?: return false
        return try {
            p.exitValue() // throws while the process is still running
            false
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    private fun ensureDaemon(): Boolean = synchronized(this) {
        if (isProcessAlive() && rpcCall("aria2.getVersion", emptyList()) != null) return true
        val binary = binaryFile() ?: return false
        secret = UUID.randomUUID().toString().replace("-", "")
        val downloadDir = File(context.filesDir, "aria2-downloads").apply { mkdirs() }
        val log = StringBuilder()
        return try {
            process?.destroy()
            process = ProcessBuilder(
                binary.absolutePath,
                "--enable-rpc=true",
                "--rpc-listen-all=false",
                "--rpc-listen-port=$rpcPort",
                "--rpc-secret=$secret",
                "--dir=${downloadDir.absolutePath}",
                "--continue=true",
                "--max-overall-download-limit=0",
                "--summary-interval=0",
                "--console-log-level=warn",
            ).redirectErrorStream(true).start()

            // Drain stderr/stdout so the daemon never blocks on a full pipe, and
            // keep the tail for an actionable error message in the UI.
            Thread {
                try {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        reader.forEachLine { line ->
                            if (log.length < 8_000) log.append(line).append('\n')
                        }
                    }
                } catch (t: Throwable) {
                    // process exited; ignore
                }
            }.apply { isDaemon = true }.start()

            repeat(40) {
                if (rpcCall("aria2.getVersion", emptyList()) != null) {
                    lastError = ""
                    DiagnosticsCenter.log("ARIA2_READY", "aria2-next JSON-RPC online on 127.0.0.1:$rpcPort")
                    return true
                }
                Thread.sleep(200)
            }
            lastError = "aria2-next did not answer JSON-RPC on 127.0.0.1:$rpcPort. ${log.toString().takeLast(400)}"
            DiagnosticsCenter.log("ARIA2_ERROR", lastError)
            false
        } catch (t: Throwable) {
            lastError = "Failed to start aria2-next: ${t.message ?: t.javaClass.simpleName}"
            DiagnosticsCenter.log("ARIA2_ERROR", lastError)
            false
        }
    }

    private fun rpcCall(method: String, params: List<Any>): JSONObject? {
        val token = if (secret.isBlank()) "" else "token:$secret:"
        // Try with the aria2 token prefix first, then without: some aria2-next
        // builds reject the token on introspection calls, others require it.
        val attempts = if (token.isEmpty()) listOf(params) else listOf(listOf(token) + params, params)
        for (callParams in attempts) {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis())
                put("method", method)
                put("params", JSONArray(callParams))
            }.toString().toRequestBody("application/json".toMediaType())
            try {
                client.newCall(Request.Builder().url(rpcUrl).post(body).build()).execute().use { resp ->
                    val payload = resp.body?.string().orEmpty()
                    if (payload.isBlank()) return@use
                    val obj = JSONObject(payload)
                    if (obj.has("error") && !obj.isNull("error")) return@use
                    obj.optJSONObject("result")?.let { return it }
                }
            } catch (t: Throwable) {
                lastError = "RPC $method: ${t.message ?: t.javaClass.simpleName}"
            }
        }
        return null
    }

    private fun addUri(entity: DownloadEntity, settings: EngineSettings): String? {
        val parent = runCatching { File(entity.destinationUri).parentFile?.absolutePath }.getOrNull()
            ?: repository.getDownloadsDirectory().absolutePath
        val options = JSONObject().apply {
            put("out", entity.fileName)
            put("dir", parent)
            put("continue", "true")
            put("split", settings.maxConcurrentWorkers.coerceIn(4, 16).toString())
            put("max-connection-per-server", "8")
            put("min-split-size", "1M")
            put("user-agent", "AetherIO-Omega/9.1 Android")
            put("allow-overwrite", "false")
            put("auto-file-renaming", "true")
        }
        val result = rpcCall("aria2.addUri", listOf(JSONArray(listOf(entity.url)), options)) ?: return null
        val gid = result.optString("gid").ifBlank { null } ?: return null
        gids[entity.id] = gid
        return gid
    }

    fun pause(taskId: String) {
        gids[taskId]?.let { rpcCall("aria2.pause", listOf(it)) }
    }

    fun resume(taskId: String) {
        gids[taskId]?.let { rpcCall("aria2.unpause", listOf(it)) }
    }

    fun stop(taskId: String) {
        gids.remove(taskId)?.let { rpcCall("aria2.remove", listOf(it)) }
    }

    suspend fun run(entity: DownloadEntity, settings: EngineSettings) = withContext(Dispatchers.IO) {
        if (!ensureDaemon()) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "aria2-next engine unavailable: ${lastError.ifBlank { "binary not bundled" }}",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return@withContext
        }
        val gid = addUri(entity, settings)
        if (gid == null) {
            repository.update(
                entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = "aria2-next rejected the task (JSON-RPC addUri failed)",
                    updatedAt = System.currentTimeMillis(),
                )
            )
            return@withContext
        }

        val startedAt = System.currentTimeMillis()
        var peak = 0L
        val fields = JSONArray(
            listOf("status", "completedLength", "totalLength", "downloadSpeed", "errorMessage")
        )
        try {
            while (isActive) {
                delay(700L)
                val state = rpcCall("aria2.tellStatus", listOf(gid, fields)) ?: continue
                val status = state.optString("status")
                val done = state.optLong("completedLength")
                val total = state.optLong("totalLength").takeIf { it > 0L } ?: entity.totalBytes
                val speed = state.optLong("downloadSpeed")
                peak = maxOf(peak, speed)
                val now = System.currentTimeMillis()
                val avg = if (now > startedAt) (done * 1000L) / (now - startedAt) else 0L

                repository.update(
                    entity.copy(
                        status = when (status) {
                            "complete" -> DownloadStatus.COMPLETED.name
                            "error", "removed" -> DownloadStatus.FAILED.name
                            else -> DownloadStatus.STREAMING.name
                        },
                        totalBytes = total,
                        downloadedBytes = done,
                        avgSpeedBps = avg,
                        peakSpeedBps = maxOf(peak, avg),
                        errorMessage = state.optString("errorMessage").ifBlank { null },
                        updatedAt = now,
                        completedAt = if (status == "complete") now else entity.completedAt,
                    )
                )
                if (status == "complete" || status == "error" || status == "removed") break
            }
        } finally {
            gids.remove(entity.id)
        }
    }
}
