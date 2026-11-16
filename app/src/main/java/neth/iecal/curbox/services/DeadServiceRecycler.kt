package neth.iecal.curbox.services

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Restarts the blocker process after a few blocker services have ended in it.
 *
 * Android keeps every ended accessibility service in memory: its binder wrapper
 * (AccessibilityService.IAccessibilityServiceClientWrapper) holds the service, and the system
 * keeps that wrapper alive after unbinding. Each ended AppBlockerService keeps all of its blockers
 * and trackers, so frequent restarts run the process out of memory. Android restarts the service
 * every time a UiAutomation client connects (uiautomator dump, test and automation tools), so a
 * few hundred of those crashed Curbox with an OutOfMemoryError.
 *
 * Only a new process frees them. Once [MAX_ENDED_SERVICES] have ended, the process stops itself
 * right after the latest one is cleaned up. Nothing is lost: the trackers save synchronously in
 * onDestroy, and Android writes pending SharedPreferences to disk after onDestroy returns, before
 * the posted stop runs. When the system binds the service again it starts a fresh process.
 *
 * Normal use rarely restarts the service, so most processes never get here.
 */
internal object DeadServiceRecycler {

    private const val TAG = "DeadServiceRecycler"
    private const val MAX_ENDED_SERVICES = 3

    private var endedServices = 0 // main thread only

    /** Call at the end of AppBlockerService.onDestroy, on the main thread. */
    fun onServiceDestroyed() {
        endedServices++
        if (endedServices < MAX_ENDED_SERVICES) return
        Log.w(TAG, "$endedServices blocker services ended in this process; restarting it to free them")
        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
    }
}
