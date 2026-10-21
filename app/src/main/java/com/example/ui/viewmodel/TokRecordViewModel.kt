package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.RecordingSession
import com.example.data.model.TikTokLiveInfo
import com.example.data.model.WatchedCreator
import com.example.data.remote.TikTokLiveResolver
import com.example.data.storage.SharedStorageManager
import com.example.service.LiveRecordingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab(val title: String) {
    RECORDER("Recorder"),
    WATCHLIST("Watchlist"),
    RECORDINGS("Recordings"),
    SETTINGS("Settings")
}

data class TokRecordUiState(
    val inputUsername: String = "khaby.lame",
    val isCheckingLive: Boolean = false,
    val liveInfo: TikTokLiveInfo? = null,
    val simulateIfOffline: Boolean = true,
    val customStreamUrl: String = "",
    val activeTab: AppTab = AppTab.RECORDER,
    val selectedRecording: RecordingSession? = null,
    val isAutoMonitoring: Boolean = false,
    val snackbarMessage: String? = null
)

class TokRecordViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val recordingDao = db.recordingDao()
    private val watchedCreatorDao = db.watchedCreatorDao()
    private val resolver = TikTokLiveResolver()
    private val storageManager = SharedStorageManager(application)

    private val _uiState = MutableStateFlow(TokRecordUiState())
    val uiState: StateFlow<TokRecordUiState> = _uiState.asStateFlow()

    // Service recording state
    val serviceRecordingState = LiveRecordingService.recordingState

    // Recordings from Room
    val allRecordings: StateFlow<List<RecordingSession>> = recordingDao.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Watchlist from Room
    val watchedCreators: StateFlow<List<WatchedCreator>> = watchedCreatorDao.getAllWatched()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var autoMonitorJob: Job? = null

    init {
        // Pre-populate popular streamers on first run for instant testing
        viewModelScope.launch(Dispatchers.IO) {
            val count = watchedCreatorDao.getByUsername("khaby.lame")
            if (count == null) {
                watchedCreatorDao.insertOrUpdate(
                    WatchedCreator(
                        username = "khaby.lame",
                        nickname = "Khaby Lame",
                        avatarUrl = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=200&h=200&fit=crop",
                        autoRecord = false
                    )
                )
                watchedCreatorDao.insertOrUpdate(
                    WatchedCreator(
                        username = "bellapoarch",
                        nickname = "Bella Poarch",
                        avatarUrl = "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=200&h=200&fit=crop",
                        autoRecord = false
                    )
                )
                watchedCreatorDao.insertOrUpdate(
                    WatchedCreator(
                        username = "zachking",
                        nickname = "Zach King",
                        avatarUrl = "https://images.unsplash.com/photo-1570295999919-56ceb5ecca61?w=200&h=200&fit=crop",
                        autoRecord = false
                    )
                )
            }
        }
    }

    fun onUsernameChange(newUsername: String) {
        _uiState.value = _uiState.value.copy(inputUsername = newUsername)
    }

    fun setSimulateIfOffline(enable: Boolean) {
        _uiState.value = _uiState.value.copy(simulateIfOffline = enable)
    }

    fun setCustomStreamUrl(url: String) {
        _uiState.value = _uiState.value.copy(customStreamUrl = url)
    }

    fun setTab(tab: AppTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    fun showSnackbar(message: String) {
        _uiState.value = _uiState.value.copy(snackbarMessage = message)
    }

    /**
     * Checks if the TikTok user is currently online/live.
     */
    fun checkLiveStatus(targetUsername: String = _uiState.value.inputUsername) {
        val cleanUser = targetUsername.trim().removePrefix("@")
        if (cleanUser.isBlank()) {
            showSnackbar("Please enter a valid TikTok username")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingLive = true)
            try {
                var info = resolver.resolveLiveStream(
                    username = cleanUser,
                    simulateIfOffline = _uiState.value.simulateIfOffline
                )

                // If user provided custom stream URL override
                val customUrl = _uiState.value.customStreamUrl.trim()
                if (customUrl.isNotBlank()) {
                    info = info.copy(
                        streamUrl = customUrl,
                        isLive = true,
                        title = "Custom Stream: @$cleanUser"
                    )
                }

                _uiState.value = _uiState.value.copy(
                    liveInfo = info,
                    isCheckingLive = false
                )

                // Update watchlist entry if tracked
                val existing = watchedCreatorDao.getByUsername(cleanUser)
                if (existing != null) {
                    watchedCreatorDao.insertOrUpdate(
                        existing.copy(
                            isLive = info.isLive,
                            lastTitle = info.title,
                            lastCheckedTimestamp = System.currentTimeMillis(),
                            lastSeenLiveTimestamp = if (info.isLive) System.currentTimeMillis() else existing.lastSeenLiveTimestamp
                        )
                    )
                }

                if (info.isLive) {
                    showSnackbar("🔴 @$cleanUser is LIVE! Ready to record MP4.")
                } else {
                    showSnackbar("⚪ @$cleanUser is currently offline.")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCheckingLive = false,
                    liveInfo = TikTokLiveInfo(
                        username = cleanUser,
                        isLive = false,
                        error = e.localizedMessage ?: "Connection error"
                    )
                )
                showSnackbar("Error checking TikTok live: ${e.message}")
            }
        }
    }

    /**
     * Starts recording the live stream into phone shared storage (Movies/TikTokLive)
     */
    fun startRecording(context: Context) {
        val info = _uiState.value.liveInfo
        if (info == null || !info.isLive || info.streamUrl.isBlank()) {
            showSnackbar("Stream is not live. Check status first!")
            return
        }

        LiveRecordingService.startRecording(
            context = context,
            username = info.cleanUsername,
            streamUrl = info.streamUrl,
            title = info.title
        )
        showSnackbar("Started recording live stream from @${info.cleanUsername}!")
    }

    /**
     * Stops the active recording session and finalizes the MP4 in shared storage.
     */
    fun stopRecording(context: Context) {
        LiveRecordingService.stopRecording(context)
        showSnackbar("Stopped recording. Saving MP4 to phone shared storage…")
    }

    /**
     * Watchlist management
     */
    fun addToWatchlist(username: String) {
        val clean = username.trim().removePrefix("@")
        if (clean.isBlank()) return

        viewModelScope.launch(Dispatchers.IO) {
            val existing = watchedCreatorDao.getByUsername(clean)
            if (existing == null) {
                watchedCreatorDao.insertOrUpdate(
                    WatchedCreator(
                        username = clean,
                        nickname = clean,
                        avatarUrl = _uiState.value.liveInfo?.avatarUrl ?: "",
                        autoRecord = false
                    )
                )
                showSnackbar("Added @$clean to watchlist")
            } else {
                showSnackbar("@$clean is already in your watchlist")
            }
        }
    }

    fun toggleAutoRecord(creator: WatchedCreator, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            watchedCreatorDao.insertOrUpdate(creator.copy(autoRecord = enabled))
        }
    }

    fun removeFromWatchlist(creator: WatchedCreator) {
        viewModelScope.launch(Dispatchers.IO) {
            watchedCreatorDao.delete(creator)
            showSnackbar("Removed @${creator.username} from watchlist")
        }
    }

    fun checkAllWatched(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = watchedCreators.value
            if (list.isEmpty()) return@launch

            showSnackbar("Checking live status for ${list.size} creators…")
            for (creator in list) {
                val info = resolver.resolveLiveStream(
                    username = creator.username,
                    simulateIfOffline = false
                )
                watchedCreatorDao.insertOrUpdate(
                    creator.copy(
                        isLive = info.isLive,
                        lastTitle = info.title,
                        nickname = if (info.nickname.isNotBlank()) info.nickname else creator.nickname,
                        avatarUrl = if (info.avatarUrl.isNotBlank()) info.avatarUrl else creator.avatarUrl,
                        lastCheckedTimestamp = System.currentTimeMillis(),
                        lastSeenLiveTimestamp = if (info.isLive) System.currentTimeMillis() else creator.lastSeenLiveTimestamp
                    )
                )

                // If auto-record is enabled and user is live, and not already recording
                if (creator.autoRecord && info.isLive && !serviceRecordingState.value.isRecording) {
                    launch(Dispatchers.Main) {
                        LiveRecordingService.startRecording(
                            context = context,
                            username = creator.username,
                            streamUrl = info.streamUrl,
                            title = info.title
                        )
                        showSnackbar("Auto-record started for @${creator.username}!")
                    }
                }
            }
        }
    }

    fun toggleAutoMonitoring(context: Context) {
        val current = _uiState.value.isAutoMonitoring
        if (current) {
            autoMonitorJob?.cancel()
            _uiState.value = _uiState.value.copy(isAutoMonitoring = false)
            showSnackbar("Auto-monitoring stopped.")
        } else {
            _uiState.value = _uiState.value.copy(isAutoMonitoring = true)
            showSnackbar("Auto-monitoring enabled! Polling every 60s.")
            autoMonitorJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    checkAllWatched(context)
                    delay(60_000)
                }
            }
        }
    }

    /**
     * Recordings management
     */
    fun selectRecordingForPlayback(session: RecordingSession?) {
        _uiState.value = _uiState.value.copy(selectedRecording = session)
    }

    fun deleteRecording(session: RecordingSession) {
        viewModelScope.launch(Dispatchers.IO) {
            storageManager.deleteVideo(session.mediaStoreUriString)
            recordingDao.deleteRecording(session)
            if (_uiState.value.selectedRecording?.id == session.id) {
                _uiState.value = _uiState.value.copy(selectedRecording = null)
            }
            showSnackbar("Deleted ${session.fileName} from shared storage.")
        }
    }

    fun shareRecording(session: RecordingSession, context: Context) {
        try {
            val intent = storageManager.createShareIntent(session.mediaStoreUriString, session.username)
            val chooser = android.content.Intent.createChooser(intent, "Share TikTok Live Recording")
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            showSnackbar("Failed to share recording: ${e.message}")
        }
    }

    fun openInGallery(session: RecordingSession, context: Context) {
        try {
            val intent = storageManager.createViewIntent(session.mediaStoreUriString)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            showSnackbar("Unable to open in external video player: ${e.message}")
        }
    }
}
