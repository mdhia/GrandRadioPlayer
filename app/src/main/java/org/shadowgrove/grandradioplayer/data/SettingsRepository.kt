package org.shadowgrove.grandradioplayer.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single DataStore instance for the whole app, scoped via a Context extension property.
 */
private val Context.dataStore by preferencesDataStore(name = "grand_radio_player_settings")

/**
 * Persists app-wide settings, most importantly the SAF tree URI of the user-selected
 * root directory that contains the station sub-folders.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ROOT_DIRECTORY_URI = stringPreferencesKey("root_directory_uri")
        val RECENT_FOLDER_ORDER = stringPreferencesKey("recent_folder_order")
    }

    /** Separator between folder names in [Keys.RECENT_FOLDER_ORDER]; unlikely in a folder name. */
    private val recentFolderDelimiter = "\u001F"

    /** How many most-recently-played folder names to remember, oldest dropped first. */
    private val maxRecentFolders = 50

    /**
     * Emits the persisted root directory URI, or null if the user hasn't picked one yet.
     */
    val rootDirectoryUri: Flow<Uri?> = context.dataStore.data.map { prefs ->
        prefs[Keys.ROOT_DIRECTORY_URI]?.let { Uri.parse(it) }
    }

    suspend fun saveRootDirectoryUri(uri: Uri) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ROOT_DIRECTORY_URI] = uri.toString()
        }
    }

    suspend fun clearRootDirectoryUri() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.ROOT_DIRECTORY_URI)
        }
    }

    /**
     * Station folder names ordered by recency of last playback, most recent first. Used by
     * Android Auto's browse root to surface whatever the driver listened to last, instead of a
     * fixed alphabetical order.
     */
    val recentFolderOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.RECENT_FOLDER_ORDER]?.split(recentFolderDelimiter)?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /** Moves [folderName] to the front of [recentFolderOrder], persisting the change. */
    suspend fun recordFolderPlayed(folderName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.RECENT_FOLDER_ORDER]
                ?.split(recentFolderDelimiter)
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            val updated = (listOf(folderName) + current.filterNot { it == folderName })
                .take(maxRecentFolders)
            prefs[Keys.RECENT_FOLDER_ORDER] = updated.joinToString(recentFolderDelimiter)
        }
    }
}
