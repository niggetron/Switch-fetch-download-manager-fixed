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

    // T2: cache the verified binary. `isAvailable()` is called for EVERY download
    // during backend selection, and verifiedBinary() hashed all 19.5 MB on each
    // call — a noticeable stall before Rust/Kotlin downloads too. Cache keyed by
    // path + size + mtime so any real change re-triggers full verification.
    @Volatile
    private var cachedVerifiedFile: File? = null
    @Volatile
    private var cachedVerifiedSize: Long = -1L
    @Volatile
    private var cachedVerifiedMtime: Long = -1L

    fun isAvailable(): Boolean = binaryFile() != null

    companion object {
        // Pinned from config/aria2-next.lock (aria2-next v3.1.1 arm64 release).
        // The upstream asset is mutable, so we verify the exact digest + size at
        // runtime before executing it. A mismatch means we refuse to run.
        private const val PINNED_SHA256 =
            "8f06939d8786c06204a099a3b9ff19d76ce063f62781eed155d9f3dbf04ea973"
        private const val PINNED_SIZE = 19_512_136L
        // ELF64 little-endian AArch64 magic: class=2, data=1, machine=183 (0xB7)
        private val AARCH64_MAGIC = byteArrayOf(
            0x7f, 'E'.toByte(), 'L'.toByte(), 'F'.toByte(), 2, 1, 1, 0,
        )
    }

    /** Verify exact size, SHA-256 and ELF64/AArch64 header before trusting the binary. */
    private fun verifiedBinary(file: File): Boolean {
        if (!file.exists() || file.length() != PINNED_SIZE) return false
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(18)
                if (input.read(header) != header.size) return false
                // e_machine is at offset 0x12 (bytes 18..19); read 2 more.
                val machine = ByteArray(2)
                if (input.read(machine) != 2) return false
                if (!AARCH64_MAGIC.contentEquals(header.copyOf(8))) return false
                val em = (machine[0].toInt() and 0xFF) or ((machine[1].toInt() and 0xFF) shl 8)
                if (em != 183) return false // EM_AARCH64
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(PINNED_SHA256, ignoreCase = true)
        } catch (t: Throwable) {
            false
        }
    }

    @Synchronized
    private fun binaryFile(): File? {
        // C15/C9: several callers reach this concurrently (UI availability check +
        // IO daemon start). Without @Synchronized they race on aria2-next.tmp.
        // C15: also restore the executable bit on the CACHED path — a restore or a
        // crash between rename and chmod leaves a verified-but-unexecutable binary.
        val target = File(context.filesDir, "aria2-next")
        val stamp = if (target.exists()) target.lastModified() else -1L
        val size = if (target.exists()) target.length() else -1L
        if (cachedVerifiedFile == target && cachedVerifiedSize == size &&
            cachedVerifiedMtime == stamp && target.canExecute()
        ) {
            return target // cheap cached path — no full-file hash
        }
        if (verifiedBinary(target)) {
            if (!target.canExecute() && !target.setExecutable(true, false)) {
                lastError = "Cannot restore execute bit on cached aria2-next binary"
                return null
            }
            cachedVerifiedFile = target
            cachedVerifiedSize = size
            cachedVerifiedMtime = stamp
            return target
        }
        cachedVerifiedFile = null
        return try {
            val tmp = File(context.filesDir, "aria2-next.tmp")
            context.assets.open("aria2-next").use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            if (!verifiedBinary(tmp)) {
                tmp.delete()
                lastError = "Bundled aria2-next failed pinned digest/ABI verification"
                DiagnosticsCenter.log("ARIA2_ERROR", lastError)
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                lastError = "Cannot install verified aria2-next binary"
                DiagnosticsCenter.log("ARIA2_ERROR", lastError)
                return null
            }
            if (!target.setExecutable(true, false)) {
                lastError = "Cannot set execute bit on aria2-next binary"
                return null
            }
            lastError = ""
            target
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

    // C15: the drain thread appends while callers read takeLast() — StringBuffer
    // is synchronized, avoiding corrupted/interleaved log text.
    private val daemonLog = StringBuffer()
    private val rpcId = java.util.concurrent.atomic.AtomicLong(1)

    /** Starts the daemon quickly, then waits for RPC readiness OUTSIDE the lock. */
    private fun ensureDaemon(): Boolean {
        val log = daemonLog
        val spawned = synchronized(this) {
            if (isProcessAlive() && rpcObject("aria2.getVersion", emptyList()) != null) return true
            val binary = binaryFile() ?: return false
            secret = UUID.randomUUID().toString().replace("-", "")
            val downloadDir = File(context.filesDir, "aria2-downloads").apply { mkdirs() }
            try {
                process?.destroy()
                val proc = ProcessBuilder(
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
                process = proc
                Thread {
                    try {
                        // C15: capture the LOCAL process. Reading the mutable field
                        // here could attach to a later process after a restart, leaving
                        // this one's pipe undrained (deadlock once its buffer fills).
                        proc.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (log.length < 8_000) log.append(line).append('\n')
                            }
                        }
                    } catch (t: Throwable) {
                        // process exited; ignore
                    }
                }.apply { isDaemon = true }.start()
                true
            } catch (t: Throwable) {
                lastError = "Failed to start aria2-next: ${t.message ?: t.javaClass.simpleName}"
                DiagnosticsCenter.log("ARIA2_ERROR", lastError)
                false
            }
        }
        if (!spawned) return false

        // T2: the old 40 x 200 ms (up to 8 s) blocking sleep delayed the first
        // byte whenever the daemon was cold. A local process answers in tens of
        // milliseconds; 30 x 50 ms keeps the same retry count budget while cutting
        // the worst-case wait to ~1.5 s.
        repeat(30) {
            if (rpcObject("aria2.getVersion", emptyList()) != null) {
                lastError = ""
                DiagnosticsCenter.log("ARIA2_READY", "aria2-next JSON-RPC online on 127.0.0.1:$rpcPort")
                return true
            }
            Thread.sleep(50)
        }
        lastError = "aria2-next did not answer JSON-RPC on 127.0.0.1:$rpcPort. ${log.toString().takeLast(400)}"
        DiagnosticsCenter.log("ARIA2_ERROR", lastError)
        return false
    }

    /** JSON-RPC results are not always objects: addUri returns a string GID. */
    private fun rpcResult(method: String, params: List<Any>): Any? {
        // aria2-next strips the literal "token:" prefix and uses the remainder
        // verbatim as the secret. A trailing colon (the old bug) made EVERY
        // authenticated call fail with code 1 "Unauthorized", so readiness never
        // succeeded and aria2 was permanently unavailable. Never retry mutations:
        // a lost reply could otherwise add the download twice.
        val callParams = if (secret.isBlank()) {
            params
        } else {
            listOf("token:$secret") + params
        }
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", rpcId.getAndIncrement())
            put("method", method)
            put("params", JSONArray(callParams))
        }.toString().toRequestBody("application/json".toMediaType())
        try {
            client.newCall(Request.Builder().url(rpcUrl).post(body).build()).execute().use { resp ->
                val payload = resp.body?.string().orEmpty()
                if (payload.isBlank()) return null
                val obj = JSONObject(payload)
                if (!obj.isNull("error")) {
                    val err = obj.getJSONObject("error")
                    lastError = "${err.optInt("code")}: ${err.optString("message")}"
                    return null
                }
                if (obj.has("result")) return obj.opt("result")
            }
        } catch (t: Throwable) {
            lastError = "RPC $method: ${t.message ?: t.javaClass.simpleName}"
        }
        return null
    }

    private fun rpcObject(method: String, params: List<Any>): JSONObject? =
        rpcResult(method, params) as? JSONObject

    private fun rpcCall(method: String, params: List<Any>): JSONObject? = rpcObject(method, params)

    private fun addUri(entity: DownloadEntity, settings: EngineSettings): String? {
        val parent = runCatching { File(entity.destinationUri).parentFile?.absolutePath }.getOrNull()
            ?: repository.getDownloadsDirectory().absolutePath
        // Options are validated STRICTLY by aria2-next: an unknown name (e.g.
        // the old "enable-http2") makes the entire addUri call fail with code 1;
        // retired names ("disk-cache", "uri-selector", "optimize-concurrent-downloads",
        // "always-resume") are silently ignored. Only real Task-scope options below.
        // HTTP/2 is decided internally: h1 for parallel ranges, 2TLS otherwise.
        // C23: honour the per-task worker count and the shared 128-lane cap so
        // switching backends does not jump from 128 to 256 lanes.
        val lanes = entity.workerCount.coerceIn(1, com.example.data.model.MAX_CONCURRENT_WORKERS_CAP)
        val options = JSONObject().apply {
            put("out", entity.fileName)
            put("dir", parent)
            put("continue", "true")
            put("split", lanes.toString())
            put("max-connection-per-server", lanes.toString())
            put("min-split-size", "2M")
            put("connect-timeout", "15")
            put("timeout", "30")
            put("lowest-speed-limit", "0")
            put("file-allocation", "none")
            put("retry-wait", "2")
            put("max-tries", "5")
            put("check-certificate", "true")
            put("min-tls-version", "TLSv1.2")
            put("enable-http-keep-alive", "true")
            // W14: gzip on ranged requests corrupts raw byte ranges (RFC 9110
            // §14.1.2 ranges apply to the ENCODED bytes) and aria2 disables
            // segmented download when a coding is seen — plus it double-inflates
            // already-compressed .gz/APK payloads. Always identity.
            put("http-accept-gzip", "false")
            put("user-agent", "AetherIO-Omega/9.2 Android")
            put("allow-overwrite", "false")
            put("auto-file-renaming", "true")
            put("incomplete-suffix", ".part")
            if (!entity.expectedSha256.isNullOrBlank()) {
                put("check-integrity", "true")
                put("checksum", "sha-256=${entity.expectedSha256}")
            }
        }
        val result = rpcResult("aria2.addUri", listOf(JSONArray(entity.allUrls.ifEmpty { listOf(entity.url) }), options))
            ?: return null
        // aria2 returns the GID as a JSON string; some wrappers return {"gid": "..."}.
        val gid = when (result) {
            is String -> result
            is JSONObject -> result.optString("gid")
            else -> ""
        }.ifBlank { null } ?: return null
        gids[entity.id] = gid
        return gid
    }

    fun warmup() {
        runCatching { ensureDaemon() }
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
        // T1: completedLength is cumulative; dividing it by session time inflated
        // AVG. Track a smoothed delta-rate and derive both AVG and MAX from it.
        var lastDone = -1L
        var lastTickMs = startedAt
        var smoothRateBps = 0L
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
                val now = System.currentTimeMillis()
                val deltaBytes = if (lastDone >= 0L) (done - lastDone).coerceAtLeast(0L) else 0L
                val deltaMs = (now - lastTickMs).coerceAtLeast(1L)
                val instantRate = if (lastDone >= 0L) (deltaBytes * 1000L) / deltaMs else speed
                lastDone = done
                lastTickMs = now
                if (instantRate > 0L) {
                    smoothRateBps = if (smoothRateBps <= 0L) instantRate else (smoothRateBps * 7L + instantRate) / 8L
                }
                // aria2's own downloadSpeed is a real instantaneous rate — keep
                // it for MAX; AVG now comes from the delta-rate series.
                peak = maxOf(peak, speed, instantRate)
                val avg = if (smoothRateBps > 0L) smoothRateBps
                    else if (now > startedAt) (done * 1000L) / (now - startedAt) else 0L

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
                        peakSpeedBps = maxOf(peak, avg), // peak now includes aria2's real speed + delta rate
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
