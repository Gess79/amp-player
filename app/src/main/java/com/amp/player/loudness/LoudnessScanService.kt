package com.amp.player.loudness

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.amp.player.MainActivity
import com.amp.player.R
import com.amp.player.util.AmpLog
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LoudnessScanService : Service() {

    @Inject lateinit var analyzer: LoudnessAnalyzer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sourceId = intent?.getStringExtra(EXTRA_SOURCE_ID)
        if (sourceId.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        startAsForeground(getString(R.string.rg_notification_analyzing))
        acquireWakeLock()
        AmpLog.i("ReplayGain", "scan service start source=$sourceId")
        job = scope.launch {
            val progressJob = launch {
                analyzer.state.collect { state ->
                    notifyProgress(state)
                }
            }
            try {
                analyzer.runScan(sourceId)
            } finally {
                progressJob.cancel()
                notifyFinished(analyzer.state.value)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.rg_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text, 0, 0), type)
    }

    private fun notifyProgress(state: LoudnessScanState) {
        val text = state.message ?: getString(R.string.playlist_analyzing)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text, state.done, state.total, ongoing = true),
        )
    }

    private fun notifyFinished(state: LoudnessScanState) {
        val text = state.message ?: getString(R.string.rg_notification_done)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text, state.done, state.total, ongoing = false),
        )
    }

    private fun buildNotification(
        text: String,
        done: Int,
        total: Int,
        ongoing: Boolean = true,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_play)
            .setContentTitle(getString(R.string.rg_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setSilent(true)
        if (total > 0) {
            builder.setProgress(total, done, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "amp:loudness").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    companion object {
        const val EXTRA_SOURCE_ID = "source_id"
        private const val CHANNEL_ID = "amp_loudness"
        private const val NOTIFICATION_ID = 1002
    }
}
