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

    fun isAvailable(): Boolean = binaryFile() != null

    private fun binaryFile(): File? {
        val target = File(context.filesDir, "aria2-next")
        if (target.exists() && target.length() > 1_000_000L) return target
        return try {
            context.assets.open("aria2-next").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            target.setExecutable(true, false)
            if (target.length() > 1_000_000L) target else null
        } catch (t: Throwable) {
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
            repeat(40) {
                if (rpcCall("aria2.getVersion", emptyList()) != null) return true
                Thread.sleep(200)
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun rpcCall(method: String, params: List<Any>): JSONObject? {
        val token = if (secret.isBlank()) "" else "token:$secret:"
        val callParams = if (token.isEmpty()) params else listOf(token) + params
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", method)
            put("params", JSONArray(callParams))
        }.toString().toRequestBody("application/json".toMediaType())
        return try {
            client.newCall(Request.Builder().url(rpcUrl).post(body).build()).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (payload.isBlank()) return null
                val obj = JSONObject(payload)
                if (obj.has("error") && !obj.isNull("error")) null else obj.optJSONObject("result")
            }
        } catch (t: Throwable) {
            null
        }
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
                    errorMessage = "aria2-next engine unavailable (binary not bundled)",
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
