package com.varun.filemanager.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.varun.filemanager.BuildConfig
import com.varun.filemanager.work.ScanScheduler
import com.varun.filemanager.media.Thresholds
import kotlin.math.roundToInt

/** Settings kept in SharedPreferences. Reads and writes are small and rare, so no async is needed. */
class AppSettings(app: Application) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var thresholds by mutableStateOf(load()); private set
    /** Nightly scan while charging and idle. */
    var backgroundScan by mutableStateOf(prefs.getBoolean("backgroundScan", true)); private set

    fun enableBackgroundScan(on: Boolean) { backgroundScan = on; prefs.edit().putBoolean("backgroundScan", on).apply() }

    private fun load() = Thresholds(
        prefs.getInt("maxHamming", 10), prefs.getInt("sceneHamming", 20),
        prefs.getInt("windowMinutes", 10), prefs.getFloat("blur", 60f).toDouble(),
    ).coerced()

    fun update(t: Thresholds) {
        thresholds = t.coerced()
        prefs.edit().putInt("maxHamming", thresholds.maxHamming).putInt("sceneHamming", thresholds.sceneHamming)
            .putInt("windowMinutes", thresholds.windowMinutes).putFloat("blur", thresholds.blurThreshold.toFloat()).apply()
    }

    fun reset() = update(Thresholds())
}

@Composable
fun SettingsScreen(vm: CleanupViewModel) {
    val s = vm.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val t = s.thresholds
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Similarity")
        Slide("Photo match strictness", "${t.maxHamming} (lower = stricter)", t.maxHamming.toFloat(), 0f..24f) { s.update(t.copy(maxHamming = it.roundToInt())) }
        Slide("Same-scene looseness", "${t.sceneHamming}", t.sceneHamming.toFloat(), 0f..32f) { s.update(t.copy(sceneHamming = it.roundToInt())) }
        Slide("Taken within", "${t.windowMinutes} min", t.windowMinutes.toFloat(), 1f..120f) { s.update(t.copy(windowMinutes = it.roundToInt())) }
        Slide("Blur limit", "${t.blurThreshold.toInt()} (higher = more photos flagged)", t.blurThreshold.toFloat(), 5f..300f) { s.update(t.copy(blurThreshold = it.toDouble())) }
        Text("Changes apply to the next analysis.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ s.reset() }, Modifier.padding(top = 8.dp)) { Text("Reset to defaults") }

        Section("Background scan")
        Row {
            Switch(s.backgroundScan, { s.enableBackgroundScan(it); ScanScheduler.apply(ctx, it) })
            Text("  Scan new photos and videos at night while charging and idle")
        }
        Text("Results are saved, so Analyze shows them straight away and only checks new or changed files.", style = MaterialTheme.typography.bodySmall)
        if (vm.reports.isEmpty()) Text("No scans yet.", style = MaterialTheme.typography.bodySmall)
        vm.reports.take(5).forEach { r ->
            Text("${fmtTime(r.finishedAt)} · ${r.source} · ${r.status}: ${r.analyzed} new of ${r.total}, ${r.photoGroups} photo groups, ${r.videoGroups} video groups, ${r.blurry} blurry", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }

        Section("Training data")
        Row {
            Switch(vm.samplesEnabled, { vm.setSamples(it) })
            Text("  Save small sample thumbnails of reviewed items")
        }
        Text("Folder: ${vm.training.dir.path}", style = MaterialTheme.typography.bodySmall)

        Section("About")
        Text("Build ${BuildConfig.BUILD_NUMBER} · version ${BuildConfig.VERSION_NAME}")
        Text("Commit ${BuildConfig.GIT_SHA}", style = MaterialTheme.typography.bodySmall)
        Text("The build number goes up by one with every pull request.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Slide(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
    Slider(v, onChange, valueRange = range)
}

private fun fmtTime(ms: Long): String = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
