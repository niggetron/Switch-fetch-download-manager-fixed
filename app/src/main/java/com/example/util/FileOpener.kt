package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.local.DownloadEntity
import java.io.File

object FileOpener {

    fun resolveContentUri(context: Context, entity: DownloadEntity): Uri? {
        return if (entity.isSafDestination) {
            Uri.parse(entity.destinationUri)
        } else {
            val file = File(entity.destinationUri)
            if (!file.exists()) return null
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }
    }

    fun openFile(context: Context, entity: DownloadEntity): Boolean {
        val uri = resolveContentUri(context, entity) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entity.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Open ${entity.fileName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    fun shareFile(context: Context, entity: DownloadEntity): Boolean {
        val uri = resolveContentUri(context, entity) ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entity.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Share ${entity.fileName}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }
}
