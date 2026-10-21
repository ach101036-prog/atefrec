package com.example.data.remote

import com.example.data.model.TikTokLiveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TikTokLiveResolver {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Resolves live status and stream URL for a given TikTok username.
     */
    suspend fun resolveLiveStream(
        username: String,
        simulateIfOffline: Boolean = false
    ): TikTokLiveInfo = withContext(Dispatchers.IO) {
        val cleanUser = username.trim().removePrefix("@")
        if (cleanUser.isBlank()) {
            return@withContext TikTokLiveInfo(
                username = username,
                error = "Please enter a valid TikTok username"
            )
        }

        // 1. Try TikTok API user/room endpoint
        try {
            val apiInfo = checkLiveViaApi(cleanUser)
            if (apiInfo != null && apiInfo.isLive && apiInfo.streamUrl.isNotBlank()) {
                return@withContext apiInfo
            }
        } catch (e: Exception) {
            // fallback to web scraping
        }

        // 2. Try TikTok Web Page Scraping
        try {
            val webInfo = checkLiveViaWebPage(cleanUser)
            if (webInfo != null && webInfo.isLive && webInfo.streamUrl.isNotBlank()) {
                return@withContext webInfo
            }
            if (webInfo != null && !webInfo.isLive && !simulateIfOffline) {
                return@withContext webInfo
            }
        } catch (e: Exception) {
            // continue
        }

        // 3. If user requested simulation / test mode, or streamer is currently offline
        if (simulateIfOffline) {
            return@withContext createSimulatedLiveInfo(cleanUser)
        }

        // Default: Streamer is currently offline
        TikTokLiveInfo(
            username = cleanUser,
            nickname = cleanUser,
            isLive = false,
            error = "@$cleanUser is currently offline on TikTok."
        )
    }

    private fun checkLiveViaApi(cleanUser: String): TikTokLiveInfo? {
        val url = "https://www.tiktok.com/api-live/user/room/?aid=1988&uniqueId=$cleanUser&sourceType=54"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", "https://www.tiktok.com/@$cleanUser/live")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return null
            val liveRoom = data.optJSONObject("liveRoom") ?: return null

            val status = liveRoom.optInt("status", 0)
            val isLive = status == 2 // 2 means Live in TikTok internal status
            val title = liveRoom.optString("title", "TikTok Live Stream")
            val roomId = liveRoom.optString("roomId", "")
            val userCount = liveRoom.optLong("user_count", 0L)

            val owner = liveRoom.optJSONObject("owner")
            val nickname = owner?.optString("nickname", cleanUser) ?: cleanUser
            val avatarThumb = owner?.optJSONObject("avatar_thumb")
            val avatarUrl = avatarThumb?.optJSONArray("url_list")?.optString(0, "") ?: ""

            var streamUrl = ""
            var streamFormat = "hls"

            val streamUrlObj = liveRoom.optJSONObject("streamUrl")
            if (streamUrlObj != null) {
                val pullData = streamUrlObj.optJSONObject("pull_data")
                if (pullData != null) {
                    val streamDataStr = pullData.optString("stream_data", "")
                    if (streamDataStr.isNotBlank()) {
                        try {
                            val streamDataJson = JSONObject(streamDataStr)
                            val dataObj = streamDataJson.optJSONObject("data")
                            // Check for highest quality pull URL
                            val qualities = listOf("origin", "uhd", "hd", "sd", "ld")
                            for (q in qualities) {
                                val qObj = dataObj?.optJSONObject(q)
                                val main = qObj?.optJSONObject("main")
                                val hlsUrl = main?.optString("hls", "")
                                val flvUrl = main?.optString("flv", "")
                                if (!hlsUrl.isNullOrBlank()) {
                                    streamUrl = hlsUrl
                                    streamFormat = "hls"
                                    break
                                } else if (!flvUrl.isNullOrBlank()) {
                                    streamUrl = flvUrl
                                    streamFormat = "flv"
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            // ignore parse error
                        }
                    }
                }
            }

            return TikTokLiveInfo(
                username = cleanUser,
                nickname = nickname,
                avatarUrl = avatarUrl,
                roomId = roomId,
                title = title,
                isLive = isLive,
                viewerCount = userCount,
                streamUrl = streamUrl,
                streamFormat = streamFormat
            )
        }
    }

    private fun checkLiveViaWebPage(cleanUser: String): TikTokLiveInfo? {
        val url = "https://www.tiktok.com/@$cleanUser/live"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            // Search for Universal Data or SIGI state
            val sigiPattern = Pattern.compile("<script id=\"SIGI_STATE\"[^>]*>(.*?)</script>")
            val matcher = sigiPattern.matcher(html)
            var jsonString: String? = null
            if (matcher.find()) {
                jsonString = matcher.group(1)
            } else {
                val universalPattern = Pattern.compile("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>")
                val uMatcher = universalPattern.matcher(html)
                if (uMatcher.find()) {
                    jsonString = uMatcher.group(1)
                }
            }

            var isLive = false
            var roomId = ""
            var title = "Live Stream"
            var nickname = cleanUser
            var avatarUrl = ""
            var streamUrl = ""

            if (jsonString != null) {
                try {
                    val root = JSONObject(jsonString)
                    // Check LiveRoom in state
                    val liveRoom = root.optJSONObject("LiveRoom")?.optJSONObject("liveRoomUserInfo")
                    if (liveRoom != null) {
                        val status = liveRoom.optJSONObject("liveRoom")?.optInt("status", 0) ?: 0
                        isLive = (status == 2)
                        roomId = liveRoom.optJSONObject("liveRoom")?.optString("roomId", "") ?: ""
                        title = liveRoom.optJSONObject("liveRoom")?.optString("title", title) ?: title
                        val userObj = liveRoom.optJSONObject("user")
                        nickname = userObj?.optString("nickname", cleanUser) ?: cleanUser
                        avatarUrl = userObj?.optString("avatarThumb", "") ?: ""
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            // Also check for direct m3u8 or flv pattern in html
            if (streamUrl.isBlank()) {
                val streamMatcher = Pattern.compile("(https://[a-zA-Z0-9._/\\-]+\\.(?:m3u8|flv)[a-zA-Z0-9?&=_%-]*)").matcher(html)
                if (streamMatcher.find()) {
                    streamUrl = streamMatcher.group(1) ?: ""
                    isLive = true
                }
            }

            return TikTokLiveInfo(
                username = cleanUser,
                nickname = nickname,
                avatarUrl = avatarUrl,
                roomId = roomId,
                title = title,
                isLive = isLive,
                streamUrl = streamUrl,
                streamFormat = if (streamUrl.contains(".flv")) "flv" else "hls"
            )
        }
    }

    /**
     * Generates a fully working live stream test feed for testing live MP4 recording
     * and shared storage saving when the creator is offline or unavailable.
     */
    fun createSimulatedLiveInfo(cleanUser: String): TikTokLiveInfo {
        // High quality reliable public video stream for testing live MP4 capture
        val testStreams = listOf(
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
        )
        val selectedStream = testStreams[Math.abs(cleanUser.hashCode()) % testStreams.size]

        return TikTokLiveInfo(
            username = cleanUser,
            nickname = "@$cleanUser (Live Simulation)",
            avatarUrl = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=200&h=200&fit=crop",
            roomId = "SIM_${System.currentTimeMillis() % 1000000}",
            title = "🔴 Gaming & Chat Live Stream - @$cleanUser",
            isLive = true,
            viewerCount = 14850,
            streamUrl = selectedStream,
            streamFormat = "mp4",
            isSimulated = true
        )
    }
}
