package org.shadowgrove.grandradioplayer.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.shadowgrove.grandradioplayer.ui.theme.NeonViolet
import org.shadowgrove.grandradioplayer.ui.theme.TextPrimary
import org.shadowgrove.grandradioplayer.ui.theme.TextSecondary

/**
 * First-run setup screen. Lets the user pick a root directory via SAF and, once picked,
 * validates it before signalling completion. No sub-folders are created - whatever folders
 * already exist under the chosen directory become stations once the library is scanned.
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.onDirectorySelected(uri)
        }
    }

    LaunchedEffect(uiState.setupCompleted) {
        if (uiState.setupCompleted) {
            onSetupComplete()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Glowing radio emblem, echoing the headphone hero of the reference design.
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.radialGradient(
                        listOf(NeonViolet.copy(alpha = 0.55f), Color.Transparent)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Radio,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Grand Radio Player",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Choose a root directory. Every sub-folder inside it - however you've " +
                "organized them - becomes a station automatically. Add or remove folders " +
                "anytime and just rescan from Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(36.dp))

        if (uiState.isProcessing) {
            CircularProgressIndicator(color = NeonViolet)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Checking directory...",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Button(
                onClick = { directoryPickerLauncher.launch(null) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonViolet,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(56.dp)
            ) {
                Text(
                    text = "Choose Root Directory",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        uiState.errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
