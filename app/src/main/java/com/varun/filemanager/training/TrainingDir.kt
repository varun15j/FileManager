package com.varun.filemanager.training

import android.content.Context
import android.os.Environment
import java.io.File

/** Documents/FileManagerTraining survives uninstall. Without all-files access we fall back to app storage (lost on uninstall). */
object TrainingDir {
    fun resolve(context: Context): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FileManagerTraining")
            .takeIf { runCatching { it.mkdirs(); it.canWrite() }.getOrDefault(false) }
            ?: File(context.getExternalFilesDir(null), "FileManagerTraining")
}
