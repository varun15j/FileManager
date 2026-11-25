package com.varun.filemanager.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import com.varun.filemanager.core.Trash
import com.varun.filemanager.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CleanupViewModel(app: Application) : AndroidViewModel(app) {
    private val trash = Trash(File(app.getExternalFilesDir(null), "trash"))
    private val grouper = SimilarGrouper()
    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var scanned by mutableStateOf(false); private set
    var photoGroups by mutableStateOf<List<SimilarGroup>>(emptyList()); private set
    var videoGroups by mutableStateOf<List<SimilarGroup>>(emptyList()); private set
    var blurry by mutableStateOf<List<MediaItem>>(emptyList()); private set
    /** URIs marked for deletion. Keepers are never pre-marked, blurry photos start unmarked: the user opts in. */
    val marked = mutableStateMapOf<String, Boolean>()

    fun scan() = viewModelScope.launch {
        scanning = true; marked.clear()
        val items = withContext(Dispatchers.IO) {
            MediaScanner(getApplication()).scan(onProgress = { d, t -> progress = if (t == 0) 1f else d / t.toFloat() })
        }
        photoGroups = grouper.groupPhotos(items); videoGroups = grouper.groupVideos(items); blurry = grouper.blurry(items)
        (photoGroups + videoGroups).forEach { g -> g.items.forEach { marked[it.uri] = it !== g.keeper } }
        scanning = false; scanned = true
    }

    fun toggle(i: MediaItem) { marked[i.uri] = marked[i.uri] != true }
    fun markedItems(): List<MediaItem> =
        (photoGroups.flatMap { it.items } + videoGroups.flatMap { it.items } + blurry).distinctBy { it.uri }.filter { marked[it.uri] == true }
    /** Safety: never allow a group to lose every copy. */
    fun safe() = (photoGroups + videoGroups).all { g -> g.items.any { marked[it.uri] != true } }
    fun reclaimable() = markedItems().sumOf { it.sizeBytes }

    /** Android 11+: system trash request (user confirms in a system dialog, items stay recoverable). */
    fun trashRequest(): android.content.IntentSender? =
        if (Build.VERSION.SDK_INT >= 30)
            MediaStore.createTrashRequest(getApplication<Application>().contentResolver, markedItems().map { Uri.parse(it.uri) }, true).intentSender
        else null

    /** Android 8-10 fallback: move files to the app trash. Called only after the user confirms. */
    fun legacyTrash() = viewModelScope.launch {
        withContext(Dispatchers.IO) { markedItems().forEach { i -> i.path?.let { trash.moveToTrash(File(it)) } } }
        scan()
    }
}

@Composable
fun CleanupScreen(vm: CleanupViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.scan() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Photo & video cleanup", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Button(onClick = { vm.scan() }, enabled = !vm.scanning, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(if (vm.scanning) "Analyzing…" else "Analyze photos and videos")
        }
        if (vm.scanning) LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Similar (${vm.photoGroups.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Blurry (${vm.blurry.size})") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Videos (${vm.videoGroups.size})") })
        }
        LazyColumn(Modifier.weight(1f)) {
            when (tab) {
                0 -> items(vm.photoGroups) { GroupCard(it, vm, loader) }
                2 -> items(vm.videoGroups) { GroupCard(it, vm, loader) }
                else -> items(vm.blurry, key = { it.uri }) { i ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
                        Thumb(i, loader)
                        Column(Modifier.padding(start = 8.dp)) { Text(i.name); Text("${formatSize(i.sizeBytes)} · sharpness ${i.blur.toInt()}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        val n = vm.markedItems().size
        if (n > 0) {
            Button(onClick = { confirm = true }, enabled = vm.safe(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Review & delete $n items (${formatSize(vm.reclaimable())})")
            }
            if (!vm.safe()) Text("Keep at least one item in every group.", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Move ${vm.markedItems().size} items to trash?") },
        text = { Text("Frees ${formatSize(vm.reclaimable())}. Items go to the trash and can be restored. One item in every group is kept.") },
        confirmButton = {
            TextButton({
                confirm = false
                vm.trashRequest()?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) } ?: vm.legacyTrash()
            }) { Text("Continue") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun GroupCard(g: SimilarGroup, vm: CleanupViewModel, loader: ImageLoader) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("${g.items.size} similar · ${formatSize(g.reclaimableBytes)} reclaimable", style = MaterialTheme.typography.titleSmall)
            g.items.forEach { i ->
                Row(Modifier.padding(top = 6.dp)) {
                    Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
                    Thumb(i, loader)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(if (i === g.keeper) "${i.name} (best, kept)" else i.name)
                        Text("${i.width}×${i.height} · ${formatSize(i.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Checked items will be moved to trash", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Thumb(i: MediaItem, loader: ImageLoader) =
    AsyncImage(Uri.parse(i.uri), i.name, loader, Modifier.size(64.dp), contentScale = ContentScale.Crop)
