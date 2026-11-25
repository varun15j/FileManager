package com.varun.filemanager.ui

import android.app.Application
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import com.varun.filemanager.core.FileOps
import com.varun.filemanager.core.Trash
import java.io.File

class BrowserViewModel(app: Application) : AndroidViewModel(app) {
    val root: File = Environment.getExternalStorageDirectory()
    private val trash = Trash(File(app.getExternalFilesDir(null), "trash"))
    var dir by mutableStateOf(root); private set
    var entries by mutableStateOf(FileOps.list(root)); private set
    var message by mutableStateOf<String?>(null)
    private var lastTrashed: Trash.Entry? = null

    fun open(d: File) { dir = d; entries = FileOps.list(d) }
    fun up(): Boolean = if (dir != root && dir.parentFile != null) { open(dir.parentFile!!); true } else false
    private fun refresh() { entries = FileOps.list(dir) }

    fun rename(f: File, name: String) = attempt { FileOps.rename(f, name) }.also { refresh() }
    fun copyTo(f: File, target: File) = attempt { FileOps.copy(f, target) }.also { refresh() }

    /** Delete = move to trash, with undo. Called only after the user confirms. */
    fun delete(f: File) {
        lastTrashed = trash.moveToTrash(f)
        message = "Moved ${f.name} to trash"
        refresh()
    }
    fun undo() { lastTrashed?.let { trash.restore(it) }; lastTrashed = null; message = null; refresh() }
    private inline fun <T> attempt(block: () -> T) = runCatching(block).onFailure { message = it.message }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    var toDelete by remember { mutableStateOf<File?>(null) }
    var toRename by remember { mutableStateOf<File?>(null) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = vm.dir != vm.root) { vm.up() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            val r = snackbar.showSnackbar(it, actionLabel = if (it.startsWith("Moved")) "Undo" else null)
            if (r == SnackbarResult.ActionPerformed) vm.undo() else vm.message = null
        }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(vm.dir.path.removePrefix(vm.root.path).ifEmpty { "/" }) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            items(vm.entries, key = { it.path }) { f ->
                ListItem(
                    leadingContent = { Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null) },
                    headlineContent = { Text(f.name) },
                    supportingContent = { if (f.isFile) Text(formatSize(f.length())) },
                    trailingContent = {
                        Row {
                            IconButton({ toRename = f }) { Icon(Icons.Default.Edit, "Rename") }
                            IconButton({ toDelete = f }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    },
                    modifier = Modifier.clickable(enabled = f.isDirectory) { vm.open(f) },
                )
            }
        }
    }
    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Move to trash?") },
            text = { Text(f.name) },
            confirmButton = { TextButton({ vm.delete(f); toDelete = null }) { Text("Move to trash") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancel") } },
        )
    }
    toRename?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(f, name); toRename = null }) { Text("Rename") } },
            dismissButton = { TextButton({ toRename = null }) { Text("Cancel") } },
        )
    }
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}
