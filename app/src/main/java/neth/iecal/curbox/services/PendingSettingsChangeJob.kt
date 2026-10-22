package neth.iecal.curbox.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Durable deadline runner for deferred settings. UI countdowns only display state; this job
 * applies it even after the app and accessibility service become idle.
 */
class PendingSettingsChangeJob : JobService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var worker: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        worker = scope.launch {
            var retry = false
            try {
                DataStoreManager(applicationContext).applyDuePendingChanges()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(applicationContext).logNonFatalError(e)
                retry = true
            } finally {
                if (currentCoroutineContext().isActive) jobFinished(params, retry)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        worker?.cancel()
        worker = null
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 7713

        fun schedule(context: Context, config: SettingsChangeDelayConfig, tamperProtectionEnabled: Boolean) {
            val appContext = context.applicationContext
            val scheduler =
                appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            val now = System.currentTimeMillis()
            val nextDeadline = config.nextCheckAtMs(now, tamperProtectionEnabled) ?: return
            val delayMs = nextDeadline - now
            scheduler.schedule(
                JobInfo.Builder(
                    JOB_ID,
                    ComponentName(appContext, PendingSettingsChangeJob::class.java)
                )
                    .setMinimumLatency(delayMs)
                    .setOverrideDeadline(delayMs)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}
