package com.example.looma.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.looma.LoomaApp
import com.example.looma.MainActivity
import com.example.looma.R
import com.example.looma.data.s3.S3ErrorKind
import com.example.looma.data.s3.S3Exception
import com.example.looma.data.sync.SyncWork.Companion.COMPLETED
import com.example.looma.data.sync.SyncWork.Companion.PHASE
import com.example.looma.data.sync.SyncWork.Companion.TOTAL
import com.example.looma.data.sync.SyncWork.Companion.VAULT_CHANGED
import com.example.looma.data.sync.SyncWork.Companion.VAULT_ID
import com.example.looma.data.sync.SyncWork.Companion.VAULT_PATH
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.sync.SyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    private val repository get() = (applicationContext as LoomaApp).syncRepository
    private var foreground = false
    private var lastUpdateAt: Long? = null
    private var lastPhase: SyncPhase? = null

    override suspend fun doWork(): Result {
        val vaultId = inputData.getString(VAULT_ID) ?: return Result.failure()
        val vaultPath = inputData.getString(VAULT_PATH) ?: return Result.failure()
        val target = SyncVaultTarget(VaultId(vaultId), vaultPath)
        return try {
            foreground = publishForeground(SyncPhase.SCANNING_LOCAL, 0, 0)
            repository.runSync(target, ::updateProgress)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: SyncVaultChangedException) {
            Result.failure(workDataOf(VAULT_CHANGED to true))
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Log.w("SyncWorker", "Vault sync failed", error)
            val transient = error is S3Exception && (
                error.kind == S3ErrorKind.Network ||
                    error.kind == S3ErrorKind.RateLimited ||
                    error.kind == S3ErrorKind.ServerError
                )
            if (transient && runAttemptCount < 2) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun publishForeground(phase: SyncPhase, completed: Int, total: Int): Boolean = try {
        setForeground(foregroundInfo(phase, completed, total))
        true
    } catch (error: IllegalStateException) {
        currentCoroutineContext().ensureActive()
        Log.w("SyncWorker", "Foreground start unavailable, continuing as scheduled work", error)
        false
    }

    private suspend fun updateProgress(phase: SyncPhase, completed: Int, total: Int) {
        val now = SystemClock.elapsedRealtime()
        val previous = lastUpdateAt
        val phaseChanged = phase != lastPhase
        val finished = total > 0 && completed >= total
        if (!phaseChanged && !finished && previous != null && now - previous < PROGRESS_THROTTLE_MS) return
        setProgress(
            workDataOf(
                PHASE to phase.name,
                COMPLETED to completed,
                TOTAL to total,
            ),
        )
        if (foreground) foreground = publishForeground(phase, completed, total)
        lastUpdateAt = now
        lastPhase = phase
    }

    private fun foregroundInfo(phase: SyncPhase, completed: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_work_channel), NotificationManager.IMPORTANCE_LOW),
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
        val contentText = when (phase) {
            SyncPhase.SCANNING_LOCAL -> context.getString(R.string.settings_sync_phase_local)
            SyncPhase.LISTING_REMOTE -> context.getString(R.string.settings_sync_phase_remote)
            SyncPhase.SYNCING -> if (total > 0) {
                context.getString(R.string.settings_sync_phase_syncing, completed, total)
            } else {
                context.getString(R.string.sync_work_title)
            }
            SyncPhase.UPDATING_INDEX -> context.getString(R.string.settings_sync_phase_indexing)
        }
        val indeterminate = phase != SyncPhase.SYNCING || total == 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_sync)
            .setContentTitle(context.getString(R.string.sync_work_title))
            .setContentText(contentText)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, completed, indeterminate)
            .addAction(0, context.getString(R.string.sync_work_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "vault_sync_tasks"
        private const val PROGRESS_THROTTLE_MS = 400L
    }
}
