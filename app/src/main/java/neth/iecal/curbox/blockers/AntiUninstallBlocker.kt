package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.models.AntiUninstallConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.ServiceProtectionManager
import java.util.Locale


/**
 * Keeps Curbox installed and running by bouncing the user away from the screens that could undo
 * protection while it is locked: the device admin deactivation screen and the accessibility service
 * disable screens. When a timed or cooldown unlock finishes, the admin is removed so the user can
 * leave freely.
 */
class AntiUninstallBlocker : BaseBlocker() {

    private companion object {
        // The AOSP activity that activates or deactivates a device admin.
        const val DEVICE_ADMIN_SCREEN_CLASS = "DeviceAdminAdd"
        const val SETTINGS_PACKAGE = "com.android.settings"
        const val SCREEN_SCAN_INTERVAL_MS = 300L
        val SERVICE_LABELS = listOf("App Blocker")
    }

    @Volatile private var config: AntiUninstallConfig = AntiUninstallConfig()
    private var lastScreenScan = 0L
    private lateinit var service: AppBlockerService
    private var settingsJob: kotlinx.coroutines.Job? = null

    private var foundCount = 0
    fun setupBlocker(service: BaseBlockingService) {
        if (service !is AppBlockerService) return
        this.service = service
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { config = it.antiUninstallConfig2 }
        }
    }

    fun onDestroy() {
        settingsJob?.cancel()
    }

    fun doAntiUninstallCheck(event: AccessibilityEvent?) {
        event ?: return
        val current = config
        if (!current.isEnabled) return

        // A finished timed or cooldown unlock lifts protection for good.
        if (AntiUninstallManager.isUnlockComplete(current)) {
            finishUnlock()
            return
        }

        // isAdminActive is a binder call, so only pay for it on the screens we may bounce from.
        val onAdminScreen = event.className?.toString()?.contains(DEVICE_ADMIN_SCREEN_CLASS) == true
        val inSettings = event.packageName?.toString() == SETTINGS_PACKAGE
        if (!onAdminScreen && !inSettings) return
        if (!AntiUninstallManager.isAdminActive(service)) return

        if (onAdminScreen) {
            bounce()
            return
        }

        val appNameMatch = service.rootInActiveWindow.findAccessibilityNodeInfosByText(service.getString(R.string.app_name))
        if(appNameMatch!=null){
            //Device admin check
            val admMatch = service.rootInActiveWindow.findAccessibilityNodeInfosByText("device admin app")
            if(!admMatch.isNullOrEmpty()) bounce()
        }

        //Accessibility Service Block
        val matches = service.rootInActiveWindow.findAccessibilityNodeInfosByText("Curbox App Blocker shortcut")
        if(!matches.isNullOrEmpty()){
            bounce()
            return
        }



        // Bounce the moment the user taps Curbox's row in accessibility settings.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && clickHitsOurService(event.source)) {
            bounce()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastScreenScan < SCREEN_SCAN_INTERVAL_MS) return
        lastScreenScan = now

        val root = service.rootInActiveWindow ?: return
        val nodes = root.findAccessibilityNodeInfosByText(
            service.getString(R.string.accessibility_permission_app_blocker)
        )
        if (!nodes.isNullOrEmpty()) {
            nodes.forEach { NodeFinder.recycle(it) }
            bounce()
        }
    }

    private fun bounce() {
        service.pressBack()
        service.pressHome()
    }

    /** True if the clicked node, or anything under it, is one of Curbox's accessibility services. */
    private fun clickHitsOurService(source: AccessibilityNodeInfo?): Boolean {
        source ?: return false
        try {
            return SERVICE_LABELS.any { label ->
                val matches = source.findAccessibilityNodeInfosByText(label)
                val found = !matches.isNullOrEmpty()
                matches?.forEach { NodeFinder.recycle(it) }
                found
            }
        } finally {
            NodeFinder.recycle(source)
        }
    }
    private fun traverseNodesForKeywords(node: AccessibilityNodeInfo?) {
        if (node == null) {
            return
        }

        if (node.getClassName() != null && node.getClassName() == "android.widget.TextView") {
            val nodeText = node.text.toString() + node.contentDescription.toString()
            val textContent = nodeText.toString().lowercase(Locale.getDefault())
            if(textContent.contains("curbox")){
                foundCount++
            }
        }

        for (i in 0..<node.getChildCount()) {
            val childNode = node.getChild(i)
            traverseNodesForKeywords(childNode)
        }
    }

    private fun finishUnlock() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.dataStoreManager.updateAntiUninstallConfig {
                    it.copy(isEnabled = false, unlockRequestedAtMs = 0L)
                }
                ServiceProtectionManager.removeDeviceAdmin(service)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                CrashLogger(service).logNonFatalError(e)
            }
        }
    }
}
