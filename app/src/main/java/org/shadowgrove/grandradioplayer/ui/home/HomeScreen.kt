package org.shadowgrove.grandradioplayer.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import org.shadowgrove.grandradioplayer.ui.theme.NeonViolet
import org.shadowgrove.grandradioplayer.ui.theme.TextPrimary
import org.shadowgrove.grandradioplayer.ui.theme.TextSecondary

/**
 * Owns the two-level navigation (folder carousel -> file carousel) plus the shared top bar with
 * the back button (Level 2 only) and the settings gear (always visible, top right).
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val stationFolders by viewModel.stationFolders.collectAsState()
    val nowPlayingMediaId by viewModel.nowPlayingMediaId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    // Only the folder's name is saved across config changes / process death - the folder
    // itself is always re-resolved from the latest scanned list, which may change if the
    // library gets re-scanned.
    var selectedFolderName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedFolder = selectedFolderName?.let { name -> stationFolders.firstOrNull { it.name == name } }

    // Remembers which folder was centered in the Level 1 carousel so navigating into a folder
    // and back restores the exact same scroll position instead of resetting to the first tile.
    var folderCarouselPage by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedFolder != null) {
        selectedFolderName = null
    }

    Scaffold(
        modifier = modifier,
        // Transparent so the app-wide gradient painted by AppGradientBackground shows through
        // the whole window, top bar included.
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedFolder?.name ?: "Grand Radio Player",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (selectedFolder != null) {
                        IconButton(onClick = { selectedFolderName = null }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to stations",
                                tint = TextPrimary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (selectedFolder == null) {
                // Level 1: choose a station folder.
                FolderCarouselScreen(
                    folders = stationFolders,
                    onFolderSelected = { folder -> selectedFolderName = folder.name },
                    nowPlayingMediaId = nowPlayingMediaId,
                    isPlaying = isPlaying,
                    initialPage = folderCarouselPage,
                    onPageChanged = { page -> folderCarouselPage = page }
                )
            } else {
                // Level 2: choose a specific file within the selected folder.
                FileCarouselScreen(
                    folder = selectedFolder,
                    onFileSelected = { file -> viewModel.tuneToStation(file, selectedFolder.name) },
                    nowPlayingMediaId = nowPlayingMediaId,
                    isPlaying = isPlaying
                )
            }

            // Only shown once something has actually been tuned in - lets the user pause/resume
            // from anywhere without having to find the exact playing tile in the carousel.
            if (nowPlayingMediaId != null) {
                PlayPauseButton(
                    isPlaying = isPlaying,
                    onToggle = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                )
            }
        }
    }
}

/** Circular floating play/pause control, anchored bottom-center of the screen. */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(64.dp)
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = NeonViolet,
                spotColor = NeonViolet
            )
            .clip(CircleShape)
            .background(NeonViolet)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}





