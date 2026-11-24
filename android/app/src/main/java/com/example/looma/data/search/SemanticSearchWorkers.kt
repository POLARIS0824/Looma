package com.example.looma.data.search

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.looma.LoomaApp
import com.example.looma.MainActivity
import com.example.looma.R
import com.example.looma.data.search.SemanticSearchWork.Companion.COMPLETED
import com.example.looma.data.search.SemanticSearchWork.Companion.TOTAL
import com.example.looma.data.search.SemanticSearchWork.Companion.VAULT_CHANGED
import com.example.looma.data.search.SemanticSearchWork.Companion.VAULT_ID
import com.example.looma.data.search.SemanticSearchWork.Companion.VAULT_PATH
import com.example.looma.domain.model.VaultId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SemanticModelDownloadWorker(context: Context, parameters: WorkerParameters) :
    SemanticSearchWorker(context, parameters, R.string.semantic_work_download) {

    override suspend fun performWork() {
        repository.downloadModel { completed -> updateProgress(completed, 100) }
    }
}

class SemanticVaultIndexWorker(context: Context, parameters: WorkerParameters) :
    SemanticSearchWorker(context, parameters, R.string.semantic_work_index) {

    override suspend fun performWork() {
        val target = SemanticVaultTarget(
            VaultId(checkNotNull(inputData.getString(VAULT_ID))),
            checkNotNull(inputData.getString(VAULT_PATH)),
        )
        repository.rebuildIndex(target, inputData.getBoolean(SemanticSearchWork.INCREMENTAL, false), ::updateProgress)
    }
}

abstract class SemanticSearchWorker(
    context: Context,
    parameters: WorkerParameters,
    @StringRes private val title: Int,
) : CoroutineWorker(context, parameters) {
    protected val repository get() = (applicationContext as LoomaApp).semanticSearchRepository
    private var foreground = false
    private var lastUpdateAt: Long? = null

    final override suspend fun doWork(): Result = try {
        check(repository.supported)
        foreground = publishForeground(0, 0)
        performWork()
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: SemanticVaultChangedException) {
        Result.failure(workDataOf(VAULT_CHANGED to true))
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w("SemanticSearchWorker", "Semantic work failed", error)
        if (error is IOException && error !is SemanticModelMissingException && runAttemptCount < 2) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    protected abstract suspend fun performWork()

    private suspend fun publishForeground(completed: Int, total: Int): Boolean = try {
        setForeground(foregroundInfo(completed, total))
        true
    } catch (error: IllegalStateException) {
        currentCoroutineContext().ensureActive()
        Log.w("SemanticSearchWorker", "Foreground start unavailable, continuing as scheduled work", error)
        false
    }

    protected suspend fun updateProgress(completed: Int, total: Int) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastUpdateAt
        if (previous != null && completed < total && now - previous < 400) return
        setProgress(workDataOf(COMPLETED to completed, TOTAL to total))
        if (foreground) foreground = publishForeground(completed, total)
        lastUpdateAt = now
    }

    private fun foregroundInfo(completed: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.semantic_work_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_search)
            .setContentTitle(context.getString(title))
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, completed, total == 0)
            .addAction(0, context.getString(R.string.semantic_work_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "semantic_search_tasks"
    }
}
