package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.SettingsChangeDelayUtils
import neth.iecal.curbox.utils.ViewUtils

/**
 * Settings screen for the change delay. Weaker settings changes wait out a countdown before
 * they apply, and this screen is where the user watches or cancels them. The screen's own
 * switch and timer go through the same gate, so weakening the delay is itself delayed.
 */
class SettingsChangeDelayFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "settings_change_delay"
    }

    private lateinit var dataStore: DataStoreManager

    private lateinit var switchDelay: MaterialSwitch
    private lateinit var groupDuration: View
    private lateinit var chipsDelay: ChipGroup
    private lateinit var switchTamperLock: MaterialSwitch
    private lateinit var containerPending: LinearLayout
    private lateinit var textPendingEmpty: TextView

    private var isEnabled = false
    private var delayMinutes = 0
    private var requireTamperProtectionOff = false
    private var liveRequireTamperProtectionOff = false
    private var antiUninstallEnabled = false
    private var pendingChanges: List<PendingSettingsChange> = emptyList()
    private var renderedPendingKey = ""
    private val countdownViews = mutableMapOf<String, TextView>()
    private var isRendering = false

    private val chipMinutes = mapOf(
        R.id.chip_none to 0,
        R.id.chip_10m to 10,
        R.id.chip_1h to 60,
        R.id.chip_4h to 240,
        R.id.chip_12h to 720,
        R.id.chip_1d to 1440,
        R.id.chip_2d to 2880
    )

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_settings_change_delay, container, false)
        dataStore = DataStoreManager(requireContext())

        switchDelay = view.findViewById(R.id.switch_change_delay)
        groupDuration = view.findViewById(R.id.group_duration)
        chipsDelay = view.findViewById(R.id.chips_delay)
        switchTamperLock = view.findViewById(R.id.switch_tamper_lock)
        containerPending = view.findViewById(R.id.container_pending)
        textPendingEmpty = view.findViewById(R.id.text_pending_empty)

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(
                it,
                getString(R.string.change_delay_help_popup),
                "https://curbox.app/docs/reducers/settings-change-delay/"
            )
        }

        switchDelay.setOnClickListener {
            requestUpdate(
                switchDelay.isChecked,
                delayMinutes,
                requireTamperProtectionOff
            )
        }
        chipsDelay.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isRendering) return@setOnCheckedStateChangeListener
            val minutes = checkedIds.firstOrNull()?.let { chipMinutes[it] } ?: return@setOnCheckedStateChangeListener
            if (minutes != delayMinutes) {
                requestUpdate(
                    isEnabled,
                    minutes,
                    requireTamperProtectionOff
                )
            }
        }
        switchTamperLock.setOnClickListener {
            requestUpdate(
                isEnabled,
                delayMinutes,
                switchTamperLock.isChecked
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    dataStore.settings.collect { settings ->
                        liveRequireTamperProtectionOff = settings.settingsChangeDelayConfig2.requireTamperProtectionOff
                        antiUninstallEnabled = settings.antiUninstallConfig2.isEnabled
                        tick()
                    }
                }
                dataStore.settingsForEditing.collect { settings ->
                    val config = settings.settingsChangeDelayConfig2
                    isEnabled = config.isEnabled
                    delayMinutes = config.delayMinutes
                    requireTamperProtectionOff = config.requireTamperProtectionOff
                    antiUninstallEnabled = settings.antiUninstallConfig2.isEnabled
                    pendingChanges = config.pendingChanges.sortedBy { it.appliesAtMs }
                    render()
                }
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    /**
     * Asks the gate to change the delay itself. Enabling or picking a longer wait applies
     * right away; anything weaker is shown as requested while enforcement keeps using the
     * current live value until the pending deadline.
     */
    private fun requestUpdate(
        enable: Boolean,
        minutes: Int,
        tamperLock: Boolean
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateSettingsChangeDelay(
                enable,
                minutes,
                tamperLock
            )
        }
    }

    private fun render() {
        if (!isAdded) return
        isRendering = true

        switchDelay.isChecked = isEnabled
        groupDuration.isVisible = isEnabled
        switchTamperLock.isChecked = requireTamperProtectionOff
        val chipId = chipMinutes.entries.find { it.value == delayMinutes }?.key
        if (chipId != null) chipsDelay.check(chipId) else chipsDelay.clearCheck()

        renderPendingList()
        isRendering = false
    }

    private fun renderPendingList() {
        textPendingEmpty.isVisible = pendingChanges.isEmpty()

        // Rows are only rebuilt when the set of waiting changes moves; the ticker
        // just refreshes the countdown text in place
        val key = pendingChanges.joinToString { "${it.field}@${it.appliesAtMs}" }
        if (key == renderedPendingKey) return
        renderedPendingKey = key

        containerPending.removeAllViews()
        countdownViews.clear()
        pendingChanges.forEach { change ->
            val row = layoutInflater.inflate(R.layout.item_pending_settings_change, containerPending, false)
            row.findViewById<TextView>(R.id.text_pending_name).text =
                SettingsChangeDelayUtils.fieldLabel(requireContext(), change.field)
            countdownViews[change.field] = row.findViewById(R.id.text_pending_countdown)
            row.findViewById<MaterialButton>(R.id.btn_cancel_pending).setOnClickListener {
                cancelPending(change)
            }
            containerPending.addView(row)
        }
        tick()
    }

    private fun tick() {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        val tamperBlocked = liveRequireTamperProtectionOff && antiUninstallEnabled
        var anyDue = false
        pendingChanges.forEach { change ->
            val remaining = change.appliesAtMs - now
            if (remaining <= 0 && !tamperBlocked) anyDue = true
            countdownViews[change.field]?.text = if (tamperBlocked) {
                getString(R.string.change_delay_blocked_by_tamper)
            } else {
                getString(
                    R.string.change_delay_applies_in,
                    SettingsChangeDelayUtils.formatRemaining(requireContext(), remaining)
                )
            }
        }
        if (anyDue) {
            viewLifecycleOwner.lifecycleScope.launch {
                dataStore.applyDuePendingChanges()
            }
        }
    }

    private fun cancelPending(change: PendingSettingsChange) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.cancelPendingSettingsChange(change.field, change.appliesAtMs)
            if (isAdded) {
                Toast.makeText(requireContext(), R.string.change_delay_cancelled_toast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        ticker.removeCallbacks(tickRunnable)
        renderedPendingKey = ""
        countdownViews.clear()
        super.onDestroyView()
    }

}
