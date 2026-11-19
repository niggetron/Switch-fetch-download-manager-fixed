package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.MEDIASTORE_DIRECT_FD_THRESHOLD_BYTES
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder

enum class ResolvedEngineBackend(val id: String, val badge: String) {
    RUST_LIBAETHER_CORE("RUST_LIBAETHER_CORE", "Rust libaether_core.so"),
    KOTLIN_OKHTTP("KOTLIN_OKHTTP", "Kotlin OkHttp"),
}

sealed class BackendSelectionResult {
    data class Selected(val backend: ResolvedEngineBackend) : BackendSelectionResult()
    data class Unavailable(val reason: String) : BackendSelectionResult()
}

data class MediaStoreTarget(
    val uri: Uri,
    val pfd: ParcelFileDescriptor,
    val detachedFd: Int,
)

/**
 * Helpers for Audited Fix #4 (MediaStore 1 GiB FUSE threshold), Dual-Engine routing,
 * and RFC 6266 Content-Disposition filename decoding.
 */
object StoragePlacementHelper {

    fun shouldUseDirectMediaStoreFd(totalBytes: Long, isSafDestination: Boolean): Boolean {
        if (isSafDestination) return false
        return totalBytes in 1L until MEDIASTORE_DIRECT_FD_THRESHOLD_BYTES
    }

    fun selectBackend(
        settings: EngineSettings,
        nativeLoaded: Boolean,
        overridePreference: EngineBackendPreference? = null,
    ): BackendSelectionResult {
        val rustAllowed = settings.enableRustEngine && nativeLoaded
        val kotlinAllowed = settings.enableKotlinOkHttpEngine
        val pref = overridePreference ?: settings.preferredEngineBackend

        return when (pref) {
            EngineBackendPreference.FORCE_RUST_NATIVE -> when {
                !settings.enableRustEngine -> BackendSelectionResult.Unavailable(
                    "Rust libaether_core.so is toggled OFF in Engine settings."
                )
                !nativeLoaded -> BackendSelectionResult.Unavailable(
                    "Rust libaether_core.so not bundled in this build (compile via CI arm64-v8a or switch to Kotlin OkHttp)."
                )
                else -> BackendSelectionResult.Selected(ResolvedEngineBackend.RUST_LIBAETHER_CORE)
            }

            EngineBackendPreference.FORCE_KOTLIN_OKHTTP -> if (kotlinAllowed) {
                BackendSelectionResult.Selected(ResolvedEngineBackend.KOTLIN_OKHTTP)
            } else {
                BackendSelectionResult.Unavailable("Kotlin OkHttp engine is toggled OFF in Engine settings.")
            }

            EngineBackendPreference.AUTO_PREFER_RUST -> when {
                rustAllowed -> BackendSelectionResult.Selected(ResolvedEngineBackend.RUST_LIBAETHER_CORE)
                kotlinAllowed -> BackendSelectionResult.Selected(ResolvedEngineBackend.KOTLIN_OKHTTP)
                else -> BackendSelectionResult.Unavailable(
                    "Both Rust Native and Kotlin OkHttp engines are disabled. Re-enable at least one engine in Engine settings."
                )
            }
        }
    }

    /**
     * Parses RFC 6266 `Content-Disposition` supporting `filename*=UTF-8''...` and quoted `filename="..."`.
     */
    fun parseContentDispositionFilename(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val extRegex = Regex("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
        extRegex.find(header)?.groupValues?.getOrNull(1)?.let { encoded ->
            val decoded = runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }
                .getOrDefault(encoded.trim())
            val leaf = decoded.substringAfterLast('/').substringAfterLast('\\')
            if (leaf.isNotBlank()) return leaf
        }
        val plainRegex = Regex("filename\\s*=\\s*\"([^\"]+)\"|filename\\s*=\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
        val match = plainRegex.find(header) ?: return null
        val candidate = (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: match.groupValues.getOrNull(2)).orEmpty().trim()
        val leaf = candidate.substringAfterLast('/').substringAfterLast('\\')
        return leaf.takeIf { it.isNotBlank() }
    }

    fun openPendingMediaStoreDownload(
        context: Context,
        fileName: String,
        mimeType: String,
    ): MediaStoreTarget? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AetherIO")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                context.contentResolver.delete(uri, null, null)
                return null
            }
            val dup = ParcelFileDescriptor.dup(pfd.fileDescriptor)
            pfd.close()
            val fd = dup.detachFd()
            MediaStoreTarget(uri = uri, pfd = dup, detachedFd = fd)
        }.getOrNull()
    }

    fun finalizePendingMediaStoreDownload(
        context: Context,
        uri: Uri,
        updatedFileName: String? = null,
        updatedMimeType: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
                if (!updatedFileName.isNullOrBlank()) {
                    put(MediaStore.Downloads.DISPLAY_NAME, updatedFileName)
                }
                if (!updatedMimeType.isNullOrBlank()) {
                    put(MediaStore.Downloads.MIME_TYPE, updatedMimeType)
                }
            }
            context.contentResolver.update(uri, cv, null, null)
        }
    }

    fun abortPendingMediaStoreDownload(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }
    }

    fun promoteLocalFileToMediaStore(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !sourceFile.exists()) return null
        var pendingUri: Uri? = null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AetherIO")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            pendingUri = uri
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Failed to open MediaStore descriptor")
            pfd.use { desc ->
                val nativeCopied = AetherNativeBridge.copyStagingToFdSafe(sourceFile.absolutePath, desc.fd)
                if (!nativeCopied) {
                    FileInputStream(sourceFile).channel.use { src ->
                        FileOutputStream(desc.fileDescriptor).channel.use { dst ->
                            var pos = 0L
                            val len = src.size()
                            while (pos < len) {
                                pos += src.transferTo(pos, len - pos, dst)
                            }
                            dst.force(true)
                        }
                    }
                }
            }
            finalizePendingMediaStoreDownload(context, uri)
            uri
        }.onFailure {
            pendingUri?.let { abortPendingMediaStoreDownload(context, it) }
        }.getOrNull()
    }
}
