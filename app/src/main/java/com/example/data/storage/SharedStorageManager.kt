package com.example.data.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SharedStorageManager(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    data class OutputVideoDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val fileName: String,
        val relativePath: String
    )

    /**
     * Creates an MP4 file in shared storage (Movies/TikTokLive)
     * Sets IS_PENDING = 1 for Scoped Storage until recording is finalized.
     */
    suspend fun createVideoFile(username: String): OutputVideoDestination = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().removePrefix("@").replace(Regex("[^a-zA-Z0-9_]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "TikTok_${cleanUser}_$timestamp.mp4"
        val relativePath = "${Environment.DIRECTORY_MOVIES}/TikTokLive"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("Failed to create MediaStore entry in shared storage")

            val outputStream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Failed to open output stream for $uri")

            OutputVideoDestination(uri, outputStream, fileName, relativePath)
        } else {
            // Legacy shared storage
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TikTokLive")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, fileName)
            val outputStream = FileOutputStream(file)
            val uri = Uri.fromFile(file)
            OutputVideoDestination(uri, outputStream, fileName, relativePath)
        }
    }

    /**
     * Marks the recording as complete and visible to system gallery / media store.
     */
    suspend fun finishVideoFile(uri: Uri, fileSizeBytes: Long) = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                    put(MediaStore.Video.Media.SIZE, fileSizeBytes)
                }
                contentResolver.update(uri, values, null, null)
            } else {
                uri.path?.let { path ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(path),
                        arrayOf("video/mp4"),
                        null
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Deletes a recording from shared storage.
     */
    suspend fun deleteVideo(uriString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                val rows = contentResolver.delete(uri, null, null)
                rows > 0
            } else {
                uri.path?.let { File(it).delete() } ?: false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Creates an Intent to share the recorded MP4 with other apps (WhatsApp, Telegram, Drive, etc.)
     */
    fun createShareIntent(uriString: String, username: String): Intent {
        val uri = Uri.parse(uriString)
        val shareUri = if (uri.scheme == "file") {
            uri.path?.let {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it))
            } ?: uri
        } else {
            uri
        }

        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, "TikTok Live - @$username")
            putExtra(Intent.EXTRA_TEXT, "Recorded TikTok Live stream from @$username via TokRecord")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Creates an Intent to open the video in the default video player / gallery.
     */
    fun createViewIntent(uriString: String): Intent {
        val uri = Uri.parse(uriString)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
