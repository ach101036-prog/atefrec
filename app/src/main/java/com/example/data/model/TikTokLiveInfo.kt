package com.example.data.model

data class TikTokLiveInfo(
    val username: String,
    val nickname: String = "",
    val avatarUrl: String = "",
    val roomId: String = "",
    val title: String = "",
    val isLive: Boolean = false,
    val viewerCount: Long = 0,
    val streamUrl: String = "",
    val streamFormat: String = "hls", // hls, flv, mp4
    val error: String? = null,
    val isSimulated: Boolean = false
) {
    val cleanUsername: String
        get() = username.trim().removePrefix("@")
}
