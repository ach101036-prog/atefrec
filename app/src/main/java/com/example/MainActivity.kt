package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.LiveRecordingService
import com.example.ui.screens.RecorderScreen
import com.example.ui.screens.RecordingsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WatchlistScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TokCyan
import com.example.ui.theme.TokRed
import com.example.ui.viewmodel.AppTab
import com.example.ui.viewmodel.TokRecordViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TokRecordViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val recordingState by viewModel.serviceRecordingState.collectAsStateWithLifecycle()
                val recordings by viewModel.allRecordings.collectAsStateWithLifecycle()
                val watchedCreators by viewModel.watchedCreators.collectAsStateWithLifecycle()

                val snackbarHostState = remember { SnackbarHostState() }

                // Post notifications permission launcher for Android 13+
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { /* permission handled */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // Show snackbars when message changes
                LaunchedEffect(uiState.snackbarMessage) {
                    uiState.snackbarMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearSnackbar()
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(TokRed),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FiberManualRecord,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "TokRecord",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 18.sp,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        color = TokCyan,
                                        letterSpacing = 1.sp
                                    )
                                }
                            },
                            actions = {
                                if (recordingState.isRecording) {
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(TokRed.copy(alpha = 0.2f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "REC",
                                            color = TokRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.RECORDER,
                                onClick = { viewModel.setTab(AppTab.RECORDER) },
                                icon = {
                                    Icon(
                                        imageVector = if (uiState.activeTab == AppTab.RECORDER) Icons.Filled.Videocam else Icons.Outlined.Videocam,
                                        contentDescription = "Recorder"
                                    )
                                },
                                label = { Text("Recorder") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TokRed,
                                    selectedTextColor = TokRed,
                                    indicatorColor = TokRed.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.testTag("nav_tab_recorder")
                            )

                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.WATCHLIST,
                                onClick = { viewModel.setTab(AppTab.WATCHLIST) },
                                icon = {
                                    Icon(
                                        imageVector = if (uiState.activeTab == AppTab.WATCHLIST) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = "Watchlist"
                                    )
                                },
                                label = { Text("Watchlist") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TokCyan,
                                    selectedTextColor = TokCyan,
                                    indicatorColor = TokCyan.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.testTag("nav_tab_watchlist")
                            )

                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.RECORDINGS,
                                onClick = { viewModel.setTab(AppTab.RECORDINGS) },
                                icon = {
                                    Icon(
                                        imageVector = if (uiState.activeTab == AppTab.RECORDINGS) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary,
                                        contentDescription = "Recordings"
                                    )
                                },
                                label = {
                                    Text(
                                        if (recordings.isNotEmpty()) "Saved (${recordings.size})" else "Recordings"
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = TokCyan,
                                    selectedTextColor = TokCyan,
                                    indicatorColor = TokCyan.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.testTag("nav_tab_recordings")
                            )

                            NavigationBarItem(
                                selected = uiState.activeTab == AppTab.SETTINGS,
                                onClick = { viewModel.setTab(AppTab.SETTINGS) },
                                icon = {
                                    Icon(
                                        imageVector = if (uiState.activeTab == AppTab.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                                        contentDescription = "Settings"
                                    )
                                },
                                label = { Text("Settings") },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = Color.White,
                                    indicatorColor = Color.White.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.testTag("nav_tab_settings")
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Crossfade(
                            targetState = uiState.activeTab,
                            label = "tab_crossfade"
                        ) { tab ->
                            when (tab) {
                                AppTab.RECORDER -> RecorderScreen(
                                    uiState = uiState,
                                    recordingState = recordingState,
                                    onUsernameChange = { viewModel.onUsernameChange(it) },
                                    onCheckLiveStatus = { viewModel.checkLiveStatus() },
                                    onStartRecording = { ctx -> viewModel.startRecording(ctx) },
                                    onStopRecording = { ctx -> viewModel.stopRecording(ctx) },
                                    onAddToWatchlist = { user -> viewModel.addToWatchlist(user) },
                                    onToggleSimulate = { sim -> viewModel.setSimulateIfOffline(sim) },
                                    onCustomStreamUrlChange = { url -> viewModel.setCustomStreamUrl(url) }
                                )

                                AppTab.WATCHLIST -> WatchlistScreen(
                                    creators = watchedCreators,
                                    isAutoMonitoring = uiState.isAutoMonitoring,
                                    onToggleAutoMonitor = { ctx -> viewModel.toggleAutoMonitoring(ctx) },
                                    onCheckAll = { ctx -> viewModel.checkAllWatched(ctx) },
                                    onAddCreator = { user -> viewModel.addToWatchlist(user) },
                                    onToggleAutoRecord = { creator, auto -> viewModel.toggleAutoRecord(creator, auto) },
                                    onRemoveCreator = { creator -> viewModel.removeFromWatchlist(creator) },
                                    onSelectCreatorToRecord = { username ->
                                        viewModel.onUsernameChange(username)
                                        viewModel.checkLiveStatus(username)
                                        viewModel.setTab(AppTab.RECORDER)
                                    }
                                )

                                AppTab.RECORDINGS -> RecordingsScreen(
                                    recordings = recordings,
                                    selectedRecording = uiState.selectedRecording,
                                    onSelectForPlayback = { session -> viewModel.selectRecordingForPlayback(session) },
                                    onDeleteRecording = { session -> viewModel.deleteRecording(session) },
                                    onShareRecording = { session, ctx -> viewModel.shareRecording(session, ctx) },
                                    onOpenInGallery = { session, ctx -> viewModel.openInGallery(session, ctx) }
                                )

                                AppTab.SETTINGS -> SettingsScreen(
                                    simulateIfOffline = uiState.simulateIfOffline,
                                    onToggleSimulate = { sim -> viewModel.setSimulateIfOffline(sim) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
