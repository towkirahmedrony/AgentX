package com.androidharness.app.ui.chat.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.androidharness.app.ui.chat.RemoteContentLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saves and shares the original remote image bytes. */
internal object ChatImageActions {
    suspend fun download(context: Context, imageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val image = RemoteContentLoader.downloadOriginal(context, imageUrl) ?: return@withContext false
        val name = "harness-${System.currentTimeMillis()}.${extensionFor(image.mimeType)}"
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AgentX")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                resolver.openOutputStream(uri)?.use { it.write(image.bytes) } ?: return@runCatching false
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(context.filesDir, "images")
                val dir = File(parent, "AgentX").apply { mkdirs() }
                val file = File(dir, name)
                file.writeBytes(image.bytes)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(image.mimeType), null)
                true
            }
        }.getOrDefault(false)
    }

    suspend fun share(context: Context, imageUrl: String): Boolean = withContext(Dispatchers.IO) {
        val image = RemoteContentLoader.downloadOriginal(context, imageUrl) ?: return@withContext false
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            val file = File(dir, "harness-${System.currentTimeMillis()}.${extensionFor(image.mimeType)}")
            file.writeBytes(image.bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = image.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/avif" -> "avif"
        else -> "jpg"
    }
}
