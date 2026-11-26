package com.varun.filemanager.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
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
import com.varun.filemanager.training.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A reviewed group waiting for the system delete dialog result. */
private data class Pending(val kind: String, val groupId: String, val items: Int, val deleted: Int, val kept: Int, val overrides: Int)

class CleanupViewModel(app: Application) : AndroidViewModel(app) {
    private val trash = Trash(File(app.getExternalFilesDir(null), "trash"))
    val settings = AppSettings(app)
    private fun grouper() = settings.thresholds.let {
        SimilarGrouper(maxHamming = it.maxHamming, photoWindowMs = it.windowMinutes * 60_000L, sceneMaxHamming = it.sceneHamming)
    }
    private val engine = ScanEngine(app)
    private val scanner = engine.scanner

    /** Documents/FileManagerTraining survives uninstall. Without all-files access we fall back to app storage (lost on uninstall). */
    val training: TrainingStore = TrainingStore(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FileManagerTraining")
            .takeIf { runCatching { it.mkdirs(); it.canWrite() }.getOrDefault(false) }
            ?: File(app.getExternalFilesDir(null), "FileManagerTraining")
    )

    var scanning by mutableStateOf(false); private set
    var progress by mutableStateOf(0f); private set
    var analyzedCount by mutableIntStateOf(0); private set
    var scanned by mutableStateOf(false); private set
    var photoGroups by mutableStateOf<List<SimilarGroup>>(emptyList()); private set
    var videoGroups by mutableStateOf<List<SimilarGroup>>(emptyList()); private set
    var blurry by mutableStateOf<List<MediaItem>>(emptyList()); private set
    /** Single photos with a face problem, for quick review: eyes closed, blurry face, sad mouth. */
    var eyesClosed by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var blurryFace by mutableStateOf<List<MediaItem>>(emptyList()); private set
    var sadFace by mutableStateOf<List<MediaItem>>(emptyList()); private set
    private fun faceLists() = listOf("eyes" to eyesClosed, "faceblur" to blurryFace, "sad" to sadFace)
    var stats by mutableStateOf(training.stats()); private set
    var reports by mutableStateOf<List<ScanReport>>(emptyList()); private set
    var samplesEnabled by mutableStateOf(training.samplesEnabled); private set
    /** URIs marked for deletion. Set once per item, so the user's own changes survive live updates. */
    val marked = mutableStateMapOf<String, Boolean>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            reports = engine.reports()
            // Show what earlier scans (and a restored backup) already know, before anything is scanned again.
            val saved = runCatching { engine.cached() }.getOrDefault(emptyList())
            if (saved.isNotEmpty() && !scanning) { analyzedCount = saved.size; publish(saved); scanned = true }
        }
    }

    private val dismissed = mutableSetOf<String>()
    private var pending: List<Pending> = emptyList()
    private var cancelled = false

    /**
     * Saved results from earlier scans (and the nightly scan) show at once; only new or changed photos and
     * videos are decoded, newest first, and the list keeps updating while that runs.
     */
    fun scan() = viewModelScope.launch {
        if (scanning) return@launch
        scanning = true; cancelled = false; marked.clear(); dismissed.clear(); analyzedCount = 0
        photoGroups = emptyList(); videoGroups = emptyList(); blurry = emptyList(); eyesClosed = emptyList(); blurryFace = emptyList(); sadFace = emptyList()
        val started = System.currentTimeMillis()
        val seen = LinkedHashMap<String, MediaItem>()
        var lastPublish = 0L
        val all = withContext(Dispatchers.IO) {
            engine.run(
                source = "manual",
                thresholds = settings.thresholds,
                detectFaces = settings.faceDetection,
                onCached = { cached ->
                    synchronized(seen) { cached.forEach { seen[it.uri] = it } }
                    analyzedCount = seen.size
                    publish(synchronized(seen) { seen.values.toList() })
                },
                onItem = { item ->
                    synchronized(seen) { seen[item.uri] = item }
                    analyzedCount = seen.size
                    val now = System.currentTimeMillis()
                    if (now - lastPublish > 1500) { lastPublish = now; publish(synchronized(seen) { seen.values.toList() }) }
                },
                onProgress = { d, t -> progress = if (t == 0) 1f else d / t.toFloat() },
                isCancelled = { cancelled },
            )
        }
        publish(all)
        training.logScan(MiniJson.obj("ts" to System.currentTimeMillis(), "items" to all.size, "photoGroups" to photoGroups.size,
            "videoGroups" to videoGroups.size, "blurry" to blurry.size, "ms" to System.currentTimeMillis() - started))
        reports = engine.reports()
        scanning = false; scanned = true
    }

    fun cancelScan() { cancelled = true }

    private fun publish(items: List<MediaItem>) {
        val grouper = grouper()
        photoGroups = grouper.groupPhotos(items).filter { it.id !in dismissed }
        videoGroups = grouper.groupVideos(items).filter { it.id !in dismissed }
        blurry = grouper.blurry(items, settings.thresholds.blurThreshold).filter { "blur:${it.uri}" !in dismissed }
        val photos = items.filter { !it.isVideo && it.faces.isNotEmpty() }
        eyesClosed = photos.filter { FaceRules.hasEyesClosed(it.faces) && "eyes:${it.uri}" !in dismissed }
        blurryFace = photos.filter { FaceRules.hasBlurryFace(it.faces) && "faceblur:${it.uri}" !in dismissed }
        sadFace = photos.filter { FaceRules.hasSadMouth(it.faces) && "sad:${it.uri}" !in dismissed }
        (photoGroups + videoGroups).forEach { g -> g.items.forEach { marked.putIfAbsent(it.uri, it !== g.keeper) } }
    }

    fun toggle(i: MediaItem) { marked[i.uri] = marked[i.uri] != true }
    fun markedItems(): List<MediaItem> =
        (photoGroups.flatMap { it.items } + videoGroups.flatMap { it.items } + blurry + faceLists().flatMap { it.second }).distinctBy { it.uri }.filter { marked[it.uri] == true }
    /** Safety: never allow a group to lose every copy. */
    fun safe() = (photoGroups + videoGroups).all { g -> g.items.any { marked[it.uri] != true } }
    fun reclaimable() = markedItems().sumOf { it.sizeBytes }

    fun setSamples(on: Boolean) { training.samplesEnabled = on; samplesEnabled = on }

    /** User says this group is not really similar / this photo is fine. Logged as feedback and hidden. */
    fun reject(g: SimilarGroup, kind: String) = viewModelScope.launch {
        dismissed += g.id
        withContext(Dispatchers.IO) { logGroup(kind, g); training.logDecision(Decision(now(), kind, g.id, "rejected", g.items.size, 0, g.items.size, 0)) }
        if (kind == "photo") photoGroups = photoGroups.filter { it.id != g.id } else videoGroups = videoGroups.filter { it.id != g.id }
        stats = training.stats()
    }

    fun keepBlurry(i: MediaItem) = viewModelScope.launch {
        dismissed += "blur:${i.uri}"
        withContext(Dispatchers.IO) { training.logDecision(Decision(now(), "blur", "blur-${i.id}", "rejected", 1, 0, 1, 0)); saveSamples("blur-${i.id}", listOf(i)) }
        blurry = blurry.filter { it.uri != i.uri }; stats = training.stats()
    }

    /** User says this photo is fine for this face check. Logged as feedback (so the thresholds can be tuned) and hidden. */
    fun keepFace(kind: String, i: MediaItem) = viewModelScope.launch {
        dismissed += "$kind:${i.uri}"
        withContext(Dispatchers.IO) { training.logDecision(Decision(now(), kind, "$kind-${i.id}", "rejected", 1, 0, 1, 0)); saveSamples("$kind-${i.id}", listOf(i)) }
        when (kind) { "eyes" -> eyesClosed = eyesClosed.filter { it.uri != i.uri }; "faceblur" -> blurryFace = blurryFace.filter { it.uri != i.uri }; else -> sadFace = sadFace.filter { it.uri != i.uri } }
        stats = training.stats()
    }

    /** Starts deletion: logs what the analyzer found and the user's choices, saves samples, then asks Android to trash. */
    fun requestDelete(): android.content.IntentSender? {
        val toDelete = markedItems().map { it.uri }.toSet()
        pending = (photoGroups.map { "photo" to it } + videoGroups.map { "video" to it }).mapNotNull { (kind, g) ->
            val del = g.items.count { it.uri in toDelete }
            if (del == 0) null else Pending(kind, g.id, g.items.size, del, g.items.size - del, g.items.count { (it.uri in toDelete) == (it === g.keeper) })
        } + blurry.filter { it.uri in toDelete }.map { Pending("blur", "blur-${it.id}", 1, 1, 0, 0) } +
            faceLists().flatMap { (k, l) -> l.filter { it.uri in toDelete }.map { Pending(k, "$k-${it.id}", 1, 1, 0, 0) } }
        viewModelScope.launch(Dispatchers.IO) {
            (photoGroups.map { "photo" to it } + videoGroups.map { "video" to it }).filter { (_, g) -> g.items.any { it.uri in toDelete } }.forEach { (k, g) -> logGroup(k, g) }
            blurry.filter { it.uri in toDelete }.forEach { saveSamples("blur-${it.id}", listOf(it)) }
            faceLists().forEach { (k, l) -> l.filter { it.uri in toDelete }.forEach { saveSamples("$k-${it.id}", listOf(it)) } }
        }
        return if (Build.VERSION.SDK_INT >= 30)
            MediaStore.createTrashRequest(getApplication<Application>().contentResolver, markedItems().map { Uri.parse(it.uri) }, true).intentSender
        else null
    }

    /** Called with the outcome of the system dialog. Logs approved or cancelled, then refreshes. */
    fun deleteFinished(ok: Boolean) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            pending.forEach { training.logDecision(Decision(now(), it.kind, it.groupId, if (ok) "approved" else "cancelled", it.items, it.deleted, it.kept, it.overrides)) }
        }
        pending = emptyList(); stats = training.stats()
        if (ok) scan()
    }

    /** Android 8-10 fallback: move files to the app trash. Called only after the user confirms. */
    fun legacyTrash() = viewModelScope.launch {
        withContext(Dispatchers.IO) { markedItems().forEach { i -> i.path?.let { trash.moveToTrash(File(it)) } } }
        deleteFinished(true)
    }

    private fun logGroup(kind: String, g: SimilarGroup) {
        training.logGroup(MiniJson.obj(
            "ts" to now(), "kind" to kind, "groupId" to g.id, "keeper" to g.keeper.uri,
            "items" to g.items.map { i ->
                mapOf("uri" to i.uri, "name" to i.name, "size" to i.sizeBytes, "w" to i.width, "h" to i.height, "takenMs" to i.dateTakenMs,
                    "durationMs" to i.durationMs, "hashes" to i.hashes, "blur" to (if (i.blur.isNaN()) null else i.blur),
                    "faces" to i.faces.map { f -> mapOf("cx" to f.cx, "cy" to f.cy, "w" to f.w, "h" to f.h, "blink" to f.blink, "smile" to f.smile, "frown" to f.frown, "sharp" to f.sharpness) },
                    "objects" to i.objects.map { o -> mapOf("kind" to o.kind.name, "label" to o.label, "cx" to o.cx, "cy" to o.cy, "w" to o.w, "h" to o.h) },
                    "markedForDelete" to (marked[i.uri] == true))
            },
        ))
        saveSamples(g.id, g.items)
    }

    private fun saveSamples(groupId: String, items: List<MediaItem>) {
        if (!training.samplesEnabled || training.hasSample(groupId)) return
        items.forEachIndexed { n, i -> scanner.thumbnailJpeg(i)?.let { training.saveSample(groupId, n, it) } }
    }

    private fun now() = System.currentTimeMillis()
}

@Composable
fun CleanupScreen(vm: CleanupViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.deleteFinished(it.resultCode == android.app.Activity.RESULT_OK) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Photo & video cleanup", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { vm.scan() }, enabled = !vm.scanning) { Text(if (vm.scanning) "Analyzing… ${vm.analyzedCount}" else "Analyze photos and videos") }
            if (vm.scanning) TextButton(onClick = { vm.cancelScan() }) { Text("Stop") }
        }
        if (vm.scanning) {
            LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            Text("Results below update while the scan runs.", style = MaterialTheme.typography.bodySmall)
        }
        ScrollableTabRow(tab, edgePadding = 0.dp) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Similar (${vm.photoGroups.size})") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Blurry (${vm.blurry.size})") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Videos (${vm.videoGroups.size})") })
            Tab(tab == 3, { tab = 3 }, text = { Text("Eyes closed (${vm.eyesClosed.size})") })
            Tab(tab == 4, { tab = 4 }, text = { Text("Blurry face (${vm.blurryFace.size})") })
            Tab(tab == 5, { tab = 5 }, text = { Text("Sad face (${vm.sadFace.size})") })
            Tab(tab == 6, { tab = 6 }, text = { Text("Training") })
        }
        if (tab == 6) TrainingPanel(vm) else LazyColumn(Modifier.weight(1f)) {
            when (tab) {
                0 -> items(vm.photoGroups) { GroupCard(it, "photo", vm, loader) }
                2 -> items(vm.videoGroups) { GroupCard(it, "video", vm, loader) }
                3 -> items(vm.eyesClosed) { FaceRow(it, "eyes", "Eyes are fine, keep", vm, loader) }
                4 -> items(vm.blurryFace) { FaceRow(it, "faceblur", "Face is fine, keep", vm, loader) }
                5 -> items(vm.sadFace) { FaceRow(it, "sad", "Not sad, keep", vm, loader) }
                else -> items(vm.blurry) { i ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
                        Thumb(i, loader)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(i.name); Text("${formatSize(i.sizeBytes)} · sharpness ${i.blur.toInt()}", style = MaterialTheme.typography.bodySmall)
                            TextButton({ vm.keepBlurry(i) }) { Text("Not blurry, keep") }
                        }
                    }
                }
            }
        }
        val n = vm.markedItems().size
        if (n > 0 && tab != 6) {
            Button(onClick = { confirm = true }, enabled = vm.safe(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("Review & delete $n items (${formatSize(vm.reclaimable())})")
            }
            if (!vm.safe()) Text("Keep at least one item in every group.", color = MaterialTheme.colorScheme.error)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Move ${vm.markedItems().size} items to trash?") },
        text = { Text("Frees ${formatSize(vm.reclaimable())}. Items go to the trash and can be restored. One item in every group is kept. Your choices are saved to improve the analysis.") },
        confirmButton = {
            TextButton({
                confirm = false
                vm.requestDelete()?.let { launcher.launch(IntentSenderRequest.Builder(it).build()) } ?: vm.legacyTrash()
            }) { Text("Continue") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun FaceRow(i: MediaItem, kind: String, keepLabel: String, vm: CleanupViewModel, loader: ImageLoader) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
        Thumb(i, loader)
        Column(Modifier.padding(start = 8.dp)) {
            Text(i.name)
            Text(formatSize(i.sizeBytes) + " · " + FaceRules.describe(i.faces), style = MaterialTheme.typography.bodySmall)
            TextButton({ vm.keepFace(kind, i) }) { Text(keepLabel) }
        }
    }
}

@Composable
private fun TrainingPanel(vm: CleanupViewModel) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("Your feedback improves the analysis.", style = MaterialTheme.typography.titleSmall)
        Text("Saved in: ${vm.training.dir.path}", style = MaterialTheme.typography.bodySmall)
        Text("This folder stays after uninstalling and is reloaded when you reinstall.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.padding(vertical = 8.dp)) {
            Switch(vm.samplesEnabled, { vm.setSamples(it) })
            Text("  Save small sample thumbnails of reviewed photos and videos")
        }
        if (vm.stats.byKind.isEmpty()) Text("No feedback yet. Approve or reject groups to start.")
        vm.stats.byKind.forEach { (kind, s) ->
            Text("$kind: ${s.approved} approved, ${s.rejected} rejected, ${s.cancelled} cancelled" +
                (s.accuracy?.let { " · accuracy ${(it * 100).toInt()}%" } ?: "") +
                (s.overrideRate?.let { " · you changed ${(it * 100).toInt()}% of suggestions" } ?: ""))
        }
    }
}

@Composable
private fun GroupCard(g: SimilarGroup, kind: String, vm: CleanupViewModel, loader: ImageLoader) {
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
                        SceneMatcher.describe(i.objects).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FaceRules.describe(i.faces).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Text("Checked items will be moved to trash", style = MaterialTheme.typography.labelSmall)
            TextButton({ vm.reject(g, kind) }) { Text("Not similar, keep all") }
        }
    }
}

@Composable
private fun Thumb(i: MediaItem, loader: ImageLoader) =
    AsyncImage(Uri.parse(i.uri), i.name, loader, Modifier.size(64.dp), contentScale = ContentScale.Crop)
