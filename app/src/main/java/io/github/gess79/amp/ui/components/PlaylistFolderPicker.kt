package io.github.gess79.amp.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import io.github.gess79.amp.data.playlist.DocumentPaths

/** Opens the system folder picker (starting in `Music/`); returns a launch function. */
@Composable
fun rememberPlaylistFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) onPicked(uri)
    }
    return { launcher.launch(DocumentPaths.musicFolderUri) }
}
