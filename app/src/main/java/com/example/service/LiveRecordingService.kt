package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.TokRecordApp
import com.example.data.local.AppDatabase
import com.example.data.model.RecordingSession
import com.example.data.recorder.StreamRecorderEngine
import com.example.data.storage.SharedStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LiveRecordingService : Service() {

    data class LiveRecordingState(
        val isRecording: Boolean = false,
        val isPaused: Boolean = false,
        val username: String = "",
        val title: String = "",
        val streamUrl: String = "",
        val durationSeconds: Long = 0,
        val bytesWritten: Long = 0,
        val bitrateKbps: Double = 0.0,
        val currentFileName: String = "",
        val currentUri: Uri? = null,
        val statusMessage: String = ""
    )

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recordingJob: Job? = null
    private val engine = StreamRecorderEngine()
    private lateinit var storageManager: SharedStorageManager
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.tokrecord.START_RECORDING"
        const val ACTION_STOP = "com.example.tokrecord.STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.tokrecord.PAUSE_RECORDING"
        const val ACTION_RESUME = "com.example.tokrecord.RESUME_RECORDING"

        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_TITLE = "extra_title"

        private val _recordingState = MutableStateFlow(LiveRecordingState())
        val recordingState = _recordingState.asStateFlow()

        fun startRecording(context: Context, username: String, streamUrl: String, title: String) {
            val intent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, LiveRecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        storageManager = SharedStorageManager(applicationContext)
        database = AppDatabase.getDatabase(applicationContext)

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TokRecord:RecordingWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: "streamer"
                val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "TikTok Live Stream"
                startRecordingSession(username, streamUrl, title)
            }
            ACTION_STOP -> {
                stopRecordingSession()
            }
            ACTION_PAUSE -> {
                engine.pause()
                _recordingState.value = _recordingState.value.copy(isPaused = true)
                updateNotification()
            }
            ACTION_RESUME -> {
                engine.resume()
                _recordingState.value = _recordingState.value.copy(isPaused = false)
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecordingSession(username: String, streamUrl: String, title: String) {
        if (recordingJob?.isActive == true) return

        wakeLock?.acquire(3 * 60 * 60 * 1000L /* 3 hours max */)

        startForeground(NOTIFICATION_ID, buildNotification("Starting live recording for @$username…"))

        recordingJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val destination = storageManager.createVideoFile(username)

                _recordingState.value = LiveRecordingState(
                    isRecording = true,
                    username = username,
                    title = title,
                    streamUrl = streamUrl,
                    currentFileName = destination.fileName,
                    currentUri = destination.uri,
                    statusMessage = "Recording @$username live into phone shared storage (Movies/TikTokLive)"
                )

                val bytesWritten = engine.recordStream(
                    streamUrl = streamUrl,
                    outputStream = destination.outputStream,
                    onProgress = { durationSec, totalBytes, bitrateKbps ->
                        _recordingState.value = _recordingState.value.copy(
                            durationSeconds = durationSec,
                            bytesWritten = totalBytes,
                            bitrateKbps = bitrateKbps
                        )
                        updateNotification()
                    }
                )

                // Finish and publish to MediaStore
                storageManager.finishVideoFile(destination.uri, bytesWritten)

                // Save to Room database
                val session = RecordingSession(
                    username = username,
                    title = title,
                    mediaStoreUriString = destination.uri.toString(),
                    fileName = destination.fileName,
                    durationSeconds = _recordingState.value.durationSeconds,
                    fileSizeBytes = bytesWritten,
                    recordedAtTimestamp = System.currentTimeMillis()
                )
                database.recordingDao().insertRecording(session)

                _recordingState.value = _recordingState.value.copy(
                    isRecording = false,
                    statusMessage = "Saved ${destination.fileName} to Movies/TikTokLive"
                )
            } catch (e: Exception) {
                e.printStackTrace()
                _recordingState.value = _recordingState.value.copy(
                    isRecording = false,
                    statusMessage = "Recording error: ${e.message}"
                )
            } finally {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopRecordingSession() {
        engine.stop()
        recordingJob?.cancel()
        _recordingState.value = _recordingState.value.copy(isRecording = false)
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification() {
        val state = _recordingState.value
        val formattedDuration = formatSeconds(state.durationSeconds)
        val formattedSize = formatBytes(state.bytesWritten)
        val content = "@${state.username} • $formattedDuration • $formattedSize"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(contentText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, LiveRecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TokRecordApp.RECORDING_CHANNEL_ID)
            .setContentTitle("TokRecord: Live Stream Recording")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop & Save", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun formatSeconds(seconds: Long): String {
        val mins = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", mins, secs)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
    }
}
