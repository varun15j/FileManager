package com.varun.filemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.varun.filemanager.ui.*
import android.os.Bundle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var granted by remember { mutableStateOf(hasAllFilesAccess()) }
                if (!granted) PermissionScreen(
                    onGrant = { requestAllFilesAccess() },
                    onRefresh = { granted = hasAllFilesAccess() },
                ) else AppRoot()
            }
        }
    }

    private fun hasAllFilesAccess() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Storage access needed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("To browse and clean up your files, allow access to all files. Nothing is deleted without your approval.")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Grant access") }
        TextButton(onClick = onRefresh) { Text("I've granted it") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Folder, null) }, label = { Text("Files") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.ContentCopy, null) }, label = { Text("Duplicates") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text("Photos") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> BrowserScreen(viewModel())
                1 -> DuplicatesScreen(viewModel())
                2 -> CleanupScreen(viewModel())
                else -> SettingsScreen(viewModel())
            }
        }
    }
}
