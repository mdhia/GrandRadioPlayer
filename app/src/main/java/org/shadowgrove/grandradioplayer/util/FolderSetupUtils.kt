package org.shadowgrove.grandradioplayer.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates the user-selected root directory (accessed via Storage Access Framework /
 * DocumentFile).
 *
 * Station folders are no longer force-created here: whatever sub-folders already exist directly
 * under the chosen root are treated as stations by [AudioFileScanner]. This means adding or
 * removing a folder on disk (e.g. via a file manager) is immediately reflected next time the
 * library is (re)scanned, with nothing in the app to keep in sync.
 */
object FolderSetupUtils {

    sealed class Result {
        object Success : Result()
        data class Failure(val reason: String) : Result()
    }

    /** Confirms [rootUri] still points at a real, accessible directory. */
    suspend fun validateRootDirectory(context: Context, rootUri: Uri): Result = withContext(Dispatchers.IO) {
        val rootDocument = DocumentFile.fromTreeUri(context, rootUri)
            ?: return@withContext Result.Failure("Root directory URI is invalid.")

        if (!rootDocument.exists() || !rootDocument.isDirectory) {
            return@withContext Result.Failure("Selected item is not a valid directory.")
        }

        Result.Success
    }
}

