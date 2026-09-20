package org.shadowgrove.grandradioplayer

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import org.shadowgrove.grandradioplayer.data.LibraryCache
import org.shadowgrove.grandradioplayer.data.SettingsRepository
import org.shadowgrove.grandradioplayer.playback.StationLibraryRepository
import org.shadowgrove.grandradioplayer.ui.components.AppGradientBackground
import org.shadowgrove.grandradioplayer.ui.components.GradientBackgroundState
import org.shadowgrove.grandradioplayer.ui.components.rememberGradientBackgroundState
import org.shadowgrove.grandradioplayer.ui.home.HomeScreen
import org.shadowgrove.grandradioplayer.ui.settings.SettingsScreen
import org.shadowgrove.grandradioplayer.ui.setup.SetupScreen
import org.shadowgrove.grandradioplayer.ui.theme.GrandRadioPlayerTheme
import org.shadowgrove.grandradioplayer.util.AudioFileScanner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so force light system-bar icons regardless of the system's
        // own light/dark setting - otherwise dark icons would vanish on the dark gradient.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            GrandRadioPlayerTheme {
                val gradientState = rememberGradientBackgroundState()
                // The gradient is the outermost layer, so it covers the *entire* window
                // including the status and navigation bar areas.
                AppGradientBackground(
                    state = gradientState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    GrandRadioPlayerApp(gradientState = gradientState)
                }
            }
        }
    }
}

/**
 * Root-level state: either we're still reading the persisted root directory URI from
 * DataStore, or we know whether one exists.
 */
private sealed class RootDirectoryState {
    object Loading : RootDirectoryState()
    data class Loaded(val uri: Uri?) : RootDirectoryState()
}

@OptIn(UnstableApi::class)
@Composable
fun GrandRadioPlayerApp(gradientState: GradientBackgroundState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    // Bumped to force a re-scan of the library on demand.
    var rescanTrigger by remember { mutableIntStateOf(0) }

    // Android 13+ requires runtime permission for the playback service's notification to show.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* no-op: playback still works without it, just without a visible notification */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val rootDirectoryState by produceState<RootDirectoryState>(
        initialValue = RootDirectoryState.Loading,
        key1 = settingsRepository
    ) {
        settingsRepository.rootDirectoryUri.collect { uri ->
            value = RootDirectoryState.Loaded(uri)
        }
    }

    when (val state = rootDirectoryState) {
        is RootDirectoryState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is RootDirectoryState.Loaded -> {
            if (state.uri == null) {
                SetupScreen(
                    onSetupComplete = { /* re-collected automatically via DataStore flow */ },
                    modifier = modifier
                )
            } else {
                // Startup: show the cached library instantly (covers included, no SAF/retriever
                // I/O needed) if we have one for this exact root directory, then cheaply
                // validate in the background and prune anything the user deleted meanwhile. If
                // there's no cache yet (first run, or after picking a new root), fall back to
                // the full, expensive scan once.
                LaunchedEffect(state.uri) {
                    val cached = LibraryCache.load(context, state.uri)
                    if (cached != null) {
                        StationLibraryRepository.updateLibrary(cached)

                        val validated = AudioFileScanner.validateAndPrune(context, cached)
                        if (validated != cached) {
                            StationLibraryRepository.updateLibrary(validated)
                            LibraryCache.save(context, state.uri, validated)
                        }
                    } else {
                        val scanned = AudioFileScanner.scanRootDirectory(context, state.uri)
                        StationLibraryRepository.updateLibrary(scanned)
                        LibraryCache.save(context, state.uri, scanned)
                    }
                }

                // Explicit "Rescan library" from Settings: always does the full scan (picks up
                // new files/folders too, unlike the cheap startup validation above) and
                // refreshes the cache for next time.
                LaunchedEffect(rescanTrigger) {
                    if (rescanTrigger == 0) return@LaunchedEffect
                    val scanned = AudioFileScanner.scanRootDirectory(context, state.uri)
                    StationLibraryRepository.updateLibrary(scanned)
                    LibraryCache.save(context, state.uri, scanned)
                }

                BackHandler(enabled = showSettings) { showSettings = false }

                // Settings should always read clearly, independent of whatever cover the
                // carousel was tinting the background with - force the neutral default
                // gradient while Settings is open. Leaving it re-applies the carousel's
                // colors automatically as soon as it recomposes.
                LaunchedEffect(showSettings) {
                    if (showSettings) {
                        gradientState.reset()
                    }
                }

                if (showSettings) {
                    val folders by StationLibraryRepository.stationFolders.collectAsState()
                    SettingsScreen(
                        rootDirectoryUri = state.uri,
                        folderCount = folders.count { it.audioFiles.isNotEmpty() },
                        stationCount = folders.sumOf { it.audioFiles.size },
                        onRootDirectoryPicked = { uri ->
                            coroutineScope.launch {
                                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                runCatching {
                                    context.contentResolver
                                        .takePersistableUriPermission(uri, takeFlags)
                                }
                                settingsRepository.saveRootDirectoryUri(uri)
                                showSettings = false
                            }
                        },
                        onRescan = {
                            rescanTrigger++
                            showSettings = false
                        },
                        onBack = { showSettings = false },
                        modifier = modifier
                    )
                } else {
                    HomeScreen(
                        onOpenSettings = { showSettings = true },
                        modifier = modifier
                    )
                }
            }
        }
    }
}


