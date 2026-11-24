package com.example.looma.feature.note

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.looma.R
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun rememberDraftExport(): (String, String) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    fun report(success: Boolean) {
        Toast.makeText(context, if (success) R.string.editor_exported else R.string.editor_export_failed, Toast.LENGTH_LONG).show()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val name = pending ?: return@rememberLauncherForActivityResult
        val stage = File(context.noBackupFilesDir, name)
        pending = null
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (uri != null) stage.inputStream().use { input ->
                            val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                            output.use { input.copyTo(it) }
                        }
                    } finally {
                        stage.delete()
                    }
                }
                if (uri != null) report(true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                report(false)
            }
        }
    }
    return { title, text ->
        if (!preparing && pending == null) {
            preparing = true
            scope.launch {
                val stage = File(context.noBackupFilesDir, "export-" + UUID.randomUUID() + ".md")
                try {
                    withContext(Dispatchers.IO) {
                        stage.outputStream().use { stream ->
                            val writer = stream.bufferedWriter(Charsets.UTF_8)
                            writer.write(text)
                            writer.flush()
                            stream.fd.sync()
                        }
                    }
                    pending = stage.name
                    picker.launch("$title (copy).md")
                } catch (cancel: CancellationException) {
                    stage.delete()
                    throw cancel
                } catch (_: Exception) {
                    stage.delete()
                    pending = null
                    report(false)
                } finally {
                    preparing = false
                }
            }
        }
    }
}
