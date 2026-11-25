package com.varun.filemanager.ui

import android.app.Application
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.varun.filemanager.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {
    private val trash = Trash(File(app.getExternalFilesDir(null), "trash"))
    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf("")
    var groups by mutableStateOf<List<DuplicateGroup>>(emptyList()); private set
    /** Files marked for deletion. Pre-filled with everything except the suggested keeper; user can change it. */
    val marked = mutableStateMapOf<String, Boolean>()
    var scanned by mutableStateOf(false); private set

    fun scan() = viewModelScope.launch {
        scanning = true; marked.clear()
        groups = withContext(Dispatchers.IO) {
            DuplicateFinder(minSize = 1024).find(listOf(Environment.getExternalStorageDirectory()), onProgress = { progress = it })
        }
        groups.forEach { g -> val keep = g.suggestedKeeper(); g.files.forEach { marked[it.path] = it != keep } }
        scanning = false; scanned = true
    }

    fun toggle(f: File) { marked[f.path] = marked[f.path] != true }
    fun markedFiles() = groups.flatMap { it.files }.filter { marked[it.path] == true }
    /** Safety: never allow a group to lose every copy. */
    fun safeToDelete() = groups.all { g -> g.files.any { marked[it.path] != true } }

    /** Moves marked files to trash (undoable). Call only after the user confirms. */
    fun deleteMarked() = viewModelScope.launch {
        withContext(Dispatchers.IO) { markedFiles().forEach { trash.moveToTrash(it) } }
        scan()
    }
}

@Composable
fun DuplicatesScreen(vm: DuplicatesViewModel) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Duplicate files", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.scan() }, enabled = !vm.scanning) { Text(if (vm.scanning) "Scanning…" else "Scan for exact duplicates") }
        if (vm.scanning) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)); Text(vm.progress, style = MaterialTheme.typography.bodySmall) }
        if (vm.scanned && vm.groups.isEmpty()) Text("No duplicates found.", Modifier.padding(top = 16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(vm.groups, key = { it.hash }) { g ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${g.files.size} copies · ${formatSize(g.sizeBytes)} each · ${formatSize(g.reclaimableBytes)} reclaimable", style = MaterialTheme.typography.titleSmall)
                        g.files.forEach { f ->
                            Row {
                                Checkbox(vm.marked[f.path] == true, { vm.toggle(f) })
                                Column { Text(f.name); Text(f.parent ?: "", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        Text("Checked = will be moved to trash", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val count = vm.markedFiles().size
        if (count > 0) Button(onClick = { confirm = true }, enabled = vm.safeToDelete(), modifier = Modifier.fillMaxWidth()) {
            Text("Review & delete $count files")
        }
        if (count > 0 && !vm.safeToDelete()) Text("Keep at least one copy in every group.", color = MaterialTheme.colorScheme.error)
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Move ${vm.markedFiles().size} files to trash?") },
        text = { Text("You can restore them from the trash folder. At least one copy of each file is kept.") },
        confirmButton = { TextButton({ confirm = false; vm.deleteMarked() }) { Text("Move to trash") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}
