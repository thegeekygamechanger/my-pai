package app.pocketagent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.data.Chat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What a long press (or a right click) on a chat offers, and the dialogs those choices lead to. History and the
 * side menu both use it, so a chat can be pinned, renamed, shared or deleted the same way wherever it is listed.
 */
@Composable
fun ChatMenu(chat: Chat, expanded: Boolean, onDismiss: () -> Unit, onDeleted: (Long) -> Unit = {}) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        @Composable
        fun item(label: String, icon: ImageVector, onClick: () -> Unit) = DropdownMenuItem(
            { Text(label) }, { onDismiss(); onClick() }, Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)), leadingIcon = { Icon(icon, null) },
        )
        item(if (chat.pinned) "Unpin" else "Pin to top", Icons.Rounded.PushPin) { scope.launch { g.dao.pin(chat.id, !chat.pinned) } }
        item("Rename", Icons.Rounded.Edit) { renaming = true }
        item("Share with a code", Icons.Rounded.Share) { sharing = true }
        item("Delete", Icons.Rounded.DeleteOutline) { deleting = true }
    }

    if (sharing) ShareChatDialog(chat.id) { sharing = false }
    if (renaming) {
        var name by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename chat") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton({ renaming = false; scope.launch { g.dao.rename(chat.id, name.trim().ifEmpty { "New chat" }) } }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete \"${chat.title.take(40)}\"?") },
        text = { Text("The messages and attachments in it are removed from this device.") },
        // Done by the app itself, not by this row: the row is gone the moment its chat is.
        confirmButton = { TextButton({ deleting = false; val id = chat.id; g.scope.launch { g.deleteChat(id); withContext(Dispatchers.Main) { onDeleted(id) } } }) { Text("Delete") } },
        dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
    )
}
