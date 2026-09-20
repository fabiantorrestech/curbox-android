package neth.iecal.curbox.anti_stimulants

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.BaseBlocker
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.GrayscaleControl
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import java.util.Calendar

class GrayScaleFilter : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_GRAYSCALE = "neth.iecal.curbox.refresh.grayscale"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        private const val STATE_PREFS = "grayscale_filter_state"
        private const val KEY_ENABLED_BY_CURBOX = "enabled_by_curbox"
    }

    private lateinit var service: BaseBlockingService
    private val grayscaleControl = GrayscaleControl()
    private var ignoredGrayScalePackages: List<String> = listOf()
    private var lastPackageName: String? = null
    private var settingsJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var grayscaleGroups: List<GrayscaleGroup> = emptyList()

    private var enabledByCurbox = false

    private fun statePrefs() = service.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun rememberEnabledByCurbox(value: Boolean) {
        if (value == enabledByCurbox) return
        enabledByCurbox = value
        statePrefs().edit().putBoolean(KEY_ENABLED_BY_CURBOX, value).apply()
    }

    fun doGrayscaleCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val currentPackageName = event.packageName?.toString() ?: return
        
        // Skip check if it's the same package or system UI or keyboard
        // We don't skip Curbox here because we want to disable grayscale when user is in the app
        if (currentPackageName == lastPackageName || 
            currentPackageName == "com.android.systemui" ||
            ignoredGrayScalePackages.contains(currentPackageName)) return

        lastPackageName = currentPackageName

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        // Monday=0, ..., Sunday=6 mapping (matches GrayscaleTimeSettingsFragment)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val shouldGrayscale = GrayscaleDecision.shouldGrayscale(
            currentPackageName, grayscaleGroups, currentDay, currentMinutes
        )
        val systemGrayscaleOn = grayscaleControl.isGrayscaleEnabled(service)

        when (GrayscaleDecision.nextAction(shouldGrayscale, systemGrayscaleOn, enabledByCurbox)) {
            GrayscaleDecision.Action.ENABLE -> {
                Log.d("GrayScaleFilter", "Enabling monochrome for $currentPackageName")
                grayscaleControl.enableGrayscale(service)
                rememberEnabledByCurbox(true)
            }
            GrayscaleDecision.Action.DISABLE -> {
                Log.d("GrayScaleFilter", "Disabling monochrome for $currentPackageName")
                grayscaleControl.disableGrayscale(service)
                rememberEnabledByCurbox(false)
            }
            GrayscaleDecision.Action.NONE -> Unit
        }
    }

    fun setup(service: BaseBlockingService) {
        this.service = service
        enabledByCurbox = statePrefs().getBoolean(KEY_ENABLED_BY_CURBOX, false)
        ignoredGrayScalePackages = listOf(
            getCurrentKeyboardPackageName(service) ?: "com.google.android.inputmethod.latin",
            service.packageName,
            "com.google.android.apps.wellbeing"
        )
        
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                grayscaleGroups = settings.grayscaleGroups
                Log.d("GrayScaleFilter", "Grayscale Groups loaded: $grayscaleGroups")
                
                // Force a check for the current package to apply changes immediately
                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            lastPackageName = null // Reset to force re-check
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doGrayscaleCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("GrayScaleFilter", "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_GRAYSCALE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun unregisterReceivers() {
        try {
            service.unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        settingsJob?.cancel()
        handler.removeCallbacksAndMessages(null)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_GRAYSCALE -> setup(service)
            }
        }
    }
}
