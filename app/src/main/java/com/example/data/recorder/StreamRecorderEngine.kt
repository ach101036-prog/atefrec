package com.example.data.recorder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamRecorderEngine {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    val recordingActive: Boolean
        get() = isRecording.get()

    fun stop() {
        isRecording.set(false)
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    /**
     * Records a stream URL (direct progressive stream, MP4, or HLS)
     * directly into the provided OutputStream in phone shared storage.
     */
    suspend fun recordStream(
        streamUrl: String,
        outputStream: OutputStream,
        onProgress: (durationSeconds: Long, bytesWritten: Long, bitrateKbps: Double) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        isRecording.set(true)
        isPaused.set(false)

        var totalBytesWritten = 0L
        val startTime = System.currentTimeMillis()
        var lastStatTime = startTime
        var lastStatBytes = 0L

        try {
            if (streamUrl.contains(".m3u8")) {
                // HLS Stream Recorder
                totalBytesWritten = recordHlsStream(
                    playlistUrl = streamUrl,
                    outputStream = outputStream,
                    startTime = startTime,
                    onProgress = onProgress
                )
            } else {
                // Progressive MP4 / FLV / WebM Stream Recorder
                totalBytesWritten = recordProgressiveStream(
                    streamUrl = streamUrl,
                    outputStream = outputStream,
                    startTime = startTime,
                    onProgress = onProgress
                )
            }
        } finally {
            try {
                outputStream.flush()
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isRecording.set(false)
        }

        totalBytesWritten
    }

    private suspend fun recordProgressiveStream(
        streamUrl: String,
        outputStream: OutputStream,
        startTime: Long,
        onProgress: (durationSeconds: Long, bytesWritten: Long, bitrateKbps: Double) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        var totalBytes = 0L
        var lastStatTime = System.currentTimeMillis()
        var lastStatBytes = 0L

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from stream")
            val inputStream: InputStream = body.byteStream()
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int

            while (isRecording.get() && coroutineContext.isActive) {
                if (isPaused.get()) {
                    kotlinx.coroutines.delay(200)
                    continue
                }

                bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break

                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead

                val now = System.currentTimeMillis()
                val deltaMs = now - lastStatTime
                if (deltaMs >= 1000) {
                    val durationSec = (now - startTime) / 1000
                    val deltaBytes = totalBytes - lastStatBytes
                    val bitrateKbps = if (deltaMs > 0) (deltaBytes * 8.0) / deltaMs else 0.0

                    onProgress(durationSec, totalBytes, bitrateKbps)
                    outputStream.flush()

                    lastStatTime = now
                    lastStatBytes = totalBytes
                }
            }
        }

        totalBytes
    }

    private suspend fun recordHlsStream(
        playlistUrl: String,
        outputStream: OutputStream,
        startTime: Long,
        onProgress: (durationSeconds: Long, bytesWritten: Long, bitrateKbps: Double) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val downloadedSegments = HashSet<String>()
        var totalBytes = 0L
        var lastStatTime = System.currentTimeMillis()
        var lastStatBytes = 0L

        val baseUri = URI(playlistUrl)

        while (isRecording.get() && coroutineContext.isActive) {
            if (isPaused.get()) {
                kotlinx.coroutines.delay(500)
                continue
            }

            try {
                val playlistReq = Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val playlistText = client.newCall(playlistReq).execute().use { res ->
                    if (res.isSuccessful) res.body?.string() else null
                }

                if (playlistText != null) {
                    val lines = playlistText.lines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                        val segmentUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            trimmed
                        } else {
                            baseUri.resolve(trimmed).toString()
                        }

                        if (!downloadedSegments.contains(segmentUrl) && isRecording.get()) {
                            downloadedSegments.add(segmentUrl)

                            // Download this segment
                            val segReq = Request.Builder().url(segmentUrl).build()
                            client.newCall(segReq).execute().use { segRes ->
                                if (segRes.isSuccessful) {
                                    val segBytes = segRes.body?.bytes()
                                    if (segBytes != null && segBytes.isNotEmpty()) {
                                        outputStream.write(segBytes)
                                        totalBytes += segBytes.size
                                    }
                                }
                            }

                            val now = System.currentTimeMillis()
                            val durationSec = (now - startTime) / 1000
                            val deltaMs = now - lastStatTime
                            val deltaBytes = totalBytes - lastStatBytes
                            val bitrateKbps = if (deltaMs > 0) (deltaBytes * 8.0) / deltaMs else 0.0

                            onProgress(durationSec, totalBytes, bitrateKbps)
                            outputStream.flush()

                            lastStatTime = now
                            lastStatBytes = totalBytes
                        }
                    }
                }
            } catch (e: Exception) {
                // Transient network hiccup during live broadcast, continue loop
            }

            // HLS playlists are refreshed every 2-3 seconds for live streaming
            kotlinx.coroutines.delay(2000)
        }

        totalBytes
    }
}
