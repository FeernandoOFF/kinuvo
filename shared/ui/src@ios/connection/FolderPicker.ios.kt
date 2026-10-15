package kinuvo.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** Folders outside the app's sandbox the user picked, e.g. an Obsidian vault, kept readable across launches. */
interface FolderAccess {
    /** Asks for a folder; completes on the main thread with its path, or null when cancelled. */
    fun pick(completion: (String?) -> Unit)
    /** Reopens the folders picked before, so the Server can read them; returns the new paths of folders that moved, by old path. */
    fun restore(): Map<String, String>
}

internal var folderAccess: FolderAccess? = null
fun installFolderAccess(access: FolderAccess) { folderAccess = access }

@Composable
internal actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val picked by rememberUpdatedState(onPicked)
    val access = folderAccess ?: return null
    return remember(access) { { access.pick { path -> path?.let(picked) } } }
}
