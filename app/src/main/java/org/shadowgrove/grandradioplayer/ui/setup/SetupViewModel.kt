package org.shadowgrove.grandradioplayer.ui.setup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.shadowgrove.grandradioplayer.data.SettingsRepository
import org.shadowgrove.grandradioplayer.util.FolderSetupUtils

/**
 * UI state for the Setup Screen.
 */
data class SetupUiState(
    val isProcessing: Boolean = false,
    val errorMessage: String? = null,
    val setupCompleted: Boolean = false
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * Called once the user picked a directory via ACTION_OPEN_DOCUMENT_TREE. Persists
     * read/write access, confirms the directory is actually usable and stores the URI so the
     * setup isn't shown again on the next launch. Whatever sub-folders already exist under it
     * become stations the next time the library is scanned - none are created here.
     */
    fun onDirectorySelected(treeUri: Uri) {
        _uiState.value = _uiState.value.copy(isProcessing = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val context = getApplication<Application>()

                // Persist the permission across reboots / app restarts.
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

                when (val result = FolderSetupUtils.validateRootDirectory(context, treeUri)) {
                    is FolderSetupUtils.Result.Success -> {
                        settingsRepository.saveRootDirectoryUri(treeUri)
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            setupCompleted = true
                        )
                    }
                    is FolderSetupUtils.Result.Failure -> {
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            errorMessage = result.reason
                        )
                    }
                }
            } catch (e: SecurityException) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = "Permission denied for the selected directory."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = e.message ?: "Unknown error while validating the directory."
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}
