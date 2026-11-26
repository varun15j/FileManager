package com.varun.filemanager.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
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

    val training: TrainingStore = TrainingStore(TrainingDir.resolve(app))
    val categoryStore = CategoryStore(training.dir)
    /** Separate scanner for sample photos, so picking samples never disturbs a running scan. */
    private val sampleScanner = MediaScanner(app)

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
    /** Custom categories the user taught the app, and the photos that match each (by category id). */
    var categories by mutableStateOf<List<Category>>(emptyList()); private set
    var categoryMatches by mutableStateOf<Map<String, List<MediaItem>>>(emptyMap()); private set
    /** Sample photos picked for the next category. */
    val basket = mutableStateListOf<Pair<String, String>>()
    var creating by mutableStateOf(false); private set
    /** Folders in Documents/FileManagerTraining/dataset, each a category the user can learn from. */
    var datasetFolders by mutableStateOf<List<DatasetFolder>>(emptyList()); private set
    private var lastItems: List<MediaItem> = emptyList()
    private fun faceLists() = listOf("eyes" to eyesClosed, "faceblur" to blurryFace, "sad" to sadFace) +
        categories.map { "cat_${it.id}" to (categoryMatches[it.id] ?: emptyList()) }
    var stats by mutableStateOf(training.stats()); private set
    var reports by mutableStateOf<List<ScanReport>>(emptyList()); private set
    var samplesEnabled by mutableStateOf(training.samplesEnabled); private set
    /** URIs marked for deletion. Set once per item, so the user's own changes survive live updates. */
    val marked = mutableStateMapOf<String, Boolean>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            reports = engine.reports()
            categories = categoryStore.list()
            refreshDataset()
            // Photos added to a folder that was learned before are picked up without any button.
            val changed = categories.mapNotNull { c -> datasetFolders.firstOrNull { DatasetFolders.source(it.name) == c.source && it.signature != c.signature } }
            if (changed.isNotEmpty()) { changed.forEach { learnFolderNow(it) }; categories = categoryStore.list() }
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
                embeddings = categories.isNotEmpty(),
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
        lastItems = items
        computeMatches()
        val photos = items.filter { !it.isVideo && it.faces.isNotEmpty() }
        eyesClosed = photos.filter { FaceRules.hasEyesClosed(it.faces) && "eyes:${it.uri}" !in dismissed }
        blurryFace = photos.filter { FaceRules.hasBlurryFace(it.faces) && "faceblur:${it.uri}" !in dismissed }
        sadFace = photos.filter { FaceRules.hasSadMouth(it.faces) && "sad:${it.uri}" !in dismissed }
        (photoGroups + videoGroups).forEach { g -> g.items.forEach { marked.putIfAbsent(it.uri, it !== g.keeper) } }
    }

    /** Photos that look like each custom category's samples, best match first. Needs embeddings, which a scan adds. */
    private fun computeMatches() {
        val items = lastItems
        categoryMatches = categories.associate { c ->
            val model = CategoryModel(c.centroid, c.threshold, c.negatives)
            c.id to items.filter { i -> !i.isVideo && i.embedding != null && "cat_${c.id}:${i.uri}" !in dismissed && model.matches(i.embedding) }
                .sortedByDescending { model.score(it.embedding!!) }
        }
    }

    fun addToBasket(i: MediaItem) { if (basket.none { it.first == i.uri }) basket += i.uri to i.name }
    fun addPicked(uris: List<Uri>) { uris.forEach { u -> if (basket.none { it.first == u.toString() }) basket += u.toString() to (u.lastPathSegment ?: "photo") } }
    fun removeFromBasket(uri: String) { basket.removeAll { it.first == uri } }

    /** Learns a category from the basket: embeds each sample, keeps a small copy in Documents, then searches all saved photos. */
    fun createCategory(label: String, description: String) = viewModelScope.launch {
        val picked = basket.toList()
        if (label.isBlank() || picked.isEmpty() || creating) return@launch
        creating = true
        val ok = withContext(Dispatchers.IO) {
            sampleScanner.openTagger(faces = false, embeddings = true)
            try {
                val id = categoryStore.newId(label)
                val embeddings = ArrayList<FloatArray>()
                picked.forEachIndexed { n, (uri, _) ->
                    sampleScanner.sampleFor(uri)?.let { (emb, jpeg) -> embeddings += emb; categoryStore.saveSample(id, n, jpeg) }
                }
                if (embeddings.isEmpty()) { categoryStore.delete(id); return@withContext false }
                val model = CategoryModel.build(embeddings)
                val known = lastItems.filter { i -> picked.any { it.first == i.uri } }
                categoryStore.save(Category(id, label.trim(), description.trim(), now(), model.centroid, model.threshold, emptyList(), embeddings.size, CommonTraits.of(known)))
                true
            } finally { sampleScanner.closeTagger() }
        }
        creating = false
        if (ok) { basket.clear(); categories = categoryStore.list(); computeMatches(); scan() }
    }

    fun refreshDataset() { datasetFolders = DatasetFolders.scan(File(training.dir, "dataset")) }

    /** Learns (or updates) the category named after a dataset folder, then searches all photos. */
    fun learnFolder(f: DatasetFolder) = viewModelScope.launch {
        if (creating) return@launch
        creating = true
        val ok = withContext(Dispatchers.IO) { learnFolderNow(f) }
        creating = false
        if (ok) { categories = categoryStore.list(); computeMatches(); scan() }
    }

    private fun learnFolderNow(f: DatasetFolder): Boolean {
        sampleScanner.openTagger(faces = false, embeddings = true)
        try {
            val embeddings = f.files.mapNotNull { sampleScanner.sampleFor(Uri.fromFile(it).toString())?.first }
            if (embeddings.isEmpty()) return false
            val id = DatasetFolders.categoryId(f.name)
            val old = categoryStore.list().firstOrNull { it.id == id }
            val model = CategoryModel.build(embeddings)
            categoryStore.save(Category(
                id, f.label, f.description.ifBlank { "Learned from the folder dataset/${f.name}." }, old?.createdMs ?: now(),
                model.centroid, model.threshold, old?.negatives ?: emptyList(), embeddings.size, emptyList(),
                source = DatasetFolders.source(f.name), signature = f.signature,
            ))
            return true
        } finally { sampleScanner.closeTagger() }
    }

    fun setSensitivity(c: Category, threshold: Float) {
        categoryStore.setThreshold(c.id, threshold)
        categories = categoryStore.list(); computeMatches()
    }

    fun deleteCategory(c: Category) { categoryStore.delete(c.id); categories = categoryStore.list(); computeMatches() }

    /** "Not this category": the photo becomes a negative example, so similar photos stop matching. Logged as feedback. */
    fun notAMatch(c: Category, i: MediaItem) = viewModelScope.launch {
        dismissed += "cat_${c.id}:${i.uri}"
        withContext(Dispatchers.IO) {
            i.embedding?.let { categoryStore.addNegative(c.id, Embeddings.dequantize(it)) }
            training.logDecision(Decision(now(), "cat_${c.id}", "cat_${c.id}-${i.id}", "rejected", 1, 0, 1, 0))
        }
        categories = categoryStore.list(); computeMatches(); stats = training.stats()
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
    var tabKey by remember { mutableStateOf("sim") }
    var confirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { vm.addPicked(it) }
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
        val tabs = listOf("sim", "blur", "vid", "eyes", "fblur", "sad") + vm.categories.map { "cat_${it.id}" } + listOf("cats", "train")
        val current = if (tabKey in tabs) tabKey else "sim"
        fun title(k: String) = when (k) {
            "sim" -> "Similar (${vm.photoGroups.size})"
            "blur" -> "Blurry (${vm.blurry.size})"
            "vid" -> "Videos (${vm.videoGroups.size})"
            "eyes" -> "Eyes closed (${vm.eyesClosed.size})"
            "fblur" -> "Blurry face (${vm.blurryFace.size})"
            "sad" -> "Sad face (${vm.sadFace.size})"
            "cats" -> "Categories"
            "train" -> "Training"
            else -> vm.categories.firstOrNull { "cat_${it.id}" == k }?.let { "${it.label} (${vm.categoryMatches[it.id]?.size ?: 0})" } ?: k
        }
        if (vm.basket.isNotEmpty()) Text("${vm.basket.size} sample photos picked. Name them in the Categories tab.", style = MaterialTheme.typography.bodySmall)
        ScrollableTabRow(tabs.indexOf(current), edgePadding = 0.dp) {
            tabs.forEach { k -> Tab(current == k, { tabKey = k }, text = { Text(title(k)) }) }
        }
        when (current) {
            "train" -> TrainingPanel(vm)
            "cats" -> CategoriesPanel(vm, onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            else -> LazyColumn(Modifier.weight(1f)) {
                when (current) {
                    "sim" -> items(vm.photoGroups) { GroupCard(it, "photo", vm, loader) }
                    "vid" -> items(vm.videoGroups) { GroupCard(it, "video", vm, loader) }
                    "eyes" -> items(vm.eyesClosed) { FaceRow(it, "Eyes are fine, keep", { i -> vm.keepFace("eyes", i) }, vm, loader) }
                    "fblur" -> items(vm.blurryFace) { FaceRow(it, "Face is fine, keep", { i -> vm.keepFace("faceblur", i) }, vm, loader) }
                    "sad" -> items(vm.sadFace) { FaceRow(it, "Not sad, keep", { i -> vm.keepFace("sad", i) }, vm, loader) }
                    "blur" -> items(vm.blurry) { i ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
                            Thumb(i, loader)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(i.name); Text("${formatSize(i.sizeBytes)} · sharpness ${i.blur.toInt()}", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton({ vm.keepBlurry(i) }) { Text("Not blurry, keep") }
                                    TextButton({ vm.addToBasket(i) }) { Text("+ Sample") }
                                }
                            }
                        }
                    }
                    else -> vm.categories.firstOrNull { "cat_${it.id}" == current }?.let { c ->
                        item { Text(c.description.ifBlank { "Photos that look like your ${c.label} samples." }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
                        items(vm.categoryMatches[c.id] ?: emptyList()) { i ->
                            FaceRow(i, "Not ${c.label}, keep", { vm.notAMatch(c, i) }, vm, loader)
                        }
                    }
                }
            }
        }
        val n = vm.markedItems().size
        if (n > 0 && tabKey != "train" && tabKey != "cats") {
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
private fun FaceRow(i: MediaItem, keepLabel: String, onKeep: (MediaItem) -> Unit, vm: CleanupViewModel, loader: ImageLoader) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Checkbox(vm.marked[i.uri] == true, { vm.toggle(i) })
        Thumb(i, loader)
        Column(Modifier.padding(start = 8.dp)) {
            Text(i.name)
            Text(formatSize(i.sizeBytes) + (FaceRules.describe(i.faces).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton({ onKeep(i) }) { Text(keepLabel) }
                TextButton({ vm.addToBasket(i) }) { Text("+ Sample") }
            }
        }
    }
}

@Composable
private fun CategoriesPanel(vm: CleanupViewModel, onPick: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshDataset() }
    LazyColumn(Modifier.padding(top = 12.dp)) {
        item {
            Text("Teach the app your own category", style = MaterialTheme.typography.titleSmall)
            Text("Pick sample photos (use + Sample on any photo, or pick from your gallery), give them a name and describe what they have in common. The app learns what the samples look like, adds a tab for the name and searches all your saved photos for matches. Review and delete from that tab.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(vertical = 8.dp)) {
                Button(onPick) { Text("Pick photos…") }
                Text("  ${vm.basket.size} samples", modifier = Modifier.padding(top = 10.dp))
            }
            vm.basket.forEach { (uri, name) ->
                Row { Text(name, modifier = Modifier.weight(1f).padding(top = 12.dp), maxLines = 1); TextButton({ vm.removeFromBasket(uri) }) { Text("Remove") } }
            }
            OutlinedTextField(label, { label = it }, label = { Text("Name, e.g. Sad face") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("What do they have in common? (saved with the samples)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Button(
                onClick = { vm.createCategory(label, description); label = ""; description = "" },
                enabled = label.isNotBlank() && vm.basket.isNotEmpty() && !vm.creating,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(if (vm.creating) "Learning…" else "Learn this category and search all photos") }
            Text("Dataset folders", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text("Each folder in ${vm.training.dir.path}/dataset is a category named after the folder. Add photos to a folder and the category updates itself.", style = MaterialTheme.typography.bodySmall)
            if (vm.datasetFolders.isEmpty()) Text("No dataset folders with photos yet.", style = MaterialTheme.typography.bodySmall)
            vm.datasetFolders.forEach { f ->
                val learned = vm.categories.firstOrNull { it.source == DatasetFolders.source(f.name) }
                Row {
                    Text("${f.label} · ${f.files.size} photos", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    TextButton({ vm.learnFolder(f) }, enabled = !vm.creating && learned?.signature != f.signature) {
                        Text(if (learned == null) "Learn" else if (learned.signature == f.signature) "Learned" else "Update")
                    }
                }
            }
            Text("Samples and the learned category are saved in ${vm.training.dir.path}/categories and are reloaded after reinstalling.", style = MaterialTheme.typography.bodySmall)
        }
        items(vm.categories) { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${c.label} · ${c.sampleCount} samples · ${vm.categoryMatches[c.id]?.size ?: 0} matches", style = MaterialTheme.typography.titleSmall)
                    if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodySmall)
                    if (c.traits.isNotEmpty()) Text("In common: " + c.traits.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    Text("Looser matches ← → stricter matches", style = MaterialTheme.typography.labelSmall)
                    var cut by remember(c.id, c.threshold) { mutableFloatStateOf(c.threshold) }
                    Slider(cut, { cut = it }, valueRange = CategoryModel.MIN_THRESHOLD..CategoryModel.MAX_THRESHOLD, onValueChangeFinished = { vm.setSensitivity(c, cut) })
                    TextButton({ vm.deleteCategory(c) }) { Text("Delete this category (photos are not touched)") }
                }
            }
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
                        TextButton({ vm.addToBasket(i) }) { Text("+ Sample") }
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
