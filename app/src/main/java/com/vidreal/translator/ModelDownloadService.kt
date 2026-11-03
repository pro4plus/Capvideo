package com.vidreal.translator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Shared, UI-independent state for a model download.
 *
 * The download used to run in the screen's coroutine scope, so when
 * the user left the app for another app, Android could stop or kill
 * that work and the download simply stopped. Moving it into this
 * foreground service (with a visible notification) tells Android the
 * work is user-visible, so it keeps running when the user switches
 * apps. The screen only watches this state; it does not own the job.
 */
object ModelDownloadState {
    @Volatile var running: Boolean = false
    @Volatile var done: Boolean = false
    @Volatile var error: String? = null
    val progress = MutableStateFlow<Pair<Long, Long>?>(null)

    fun reset() {
        running = false
        done = false
        error = null
        progress.value = null
    }
}

/**
 * Downloads one Vosk model (English or Arabic) as a foreground
 * service, so switching to another app does not stop it. On a
 * restart the download still resumes from the partial zip, because
 * VoskTranscriber keeps partial zips and uses Range requests.
 */
class ModelDownloadService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lang = intent?.getStringExtra(EXTRA_LANG)
        if (lang == null) return START_NOT_STICKY
        // A second tap while a download is already running must not
        // start a second download; the screen will just wait for this one.
        if (ModelDownloadState.running) return START_STICKY
        ModelDownloadState.reset()
        ModelDownloadState.running = true
        startForeground(NOTIFICATION_ID, buildNotification(lang))
        scope.launch {
            try {
                val error = VoskTranscriber.ensureModelWithError(
                    applicationContext, lang
                ) { downloaded, total ->
                    ModelDownloadState.progress.value = downloaded to total
                }
                ModelDownloadState.error = error
            } catch (e: Exception) {
                ModelDownloadState.error =
                    "خطأ أثناء تحميل النموذج (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                ModelDownloadState.done = true
                ModelDownloadState.running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!ModelDownloadState.done) {
            // The service was killed before finishing: report it as an
            // interrupted download instead of leaving the screen waiting
            // forever. The partial zip is kept, so the next tap resumes.
            ModelDownloadState.error = "توقف تحميل النموذج لأن النظام أوقف الخدمة. أعد المحاولة وسيُستأنف من حيث توقف."
            ModelDownloadState.done = true
            ModelDownloadState.running = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(lang: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "تحميل نموذج Vosk",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        val name = if (lang == "ar") "العربي" else "الإنجليزي"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("جارٍ تحميل نموذج Vosk $name")
            .setContentText("يمكنك مغادرة التطبيق، وسيستمر التحميل. افتح التطبيق لمتابعة التقدم.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_LANG = "lang"
        const val CHANNEL_ID = "vosk_model_download"
        const val NOTIFICATION_ID = 1

        fun start(context: Context, lang: String) {
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_LANG, lang)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
