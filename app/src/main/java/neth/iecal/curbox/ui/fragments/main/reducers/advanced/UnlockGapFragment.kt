package neth.iecal.curbox.ui.fragments.main.reducers.advanced

import android.os.Bundle
import android.text.format.DateFormat
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.EmergencyUnlockPrefs
import neth.iecal.curbox.data.models.UnlockGapConfig
import neth.iecal.curbox.utils.AntiUninstallManager
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.ViewUtils
import java.text.DateFormatSymbols
import java.util.Calendar

/**
 * Shared settings for the growing wait between app group unlocks: when each day ends, and the
 * emergency password. Changes go through the settings change delay like other restrictions.
 */
class UnlockGapFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "unlock_gap"
    }

    private lateinit var dataStore: DataStoreManager
    private lateinit var containerDayEnds: LinearLayout
    private lateinit var textEmergencyStatus: TextView
    private lateinit var btnEmergencySet: MaterialButton
    private lateinit var btnEmergencyRemove: MaterialButton
    private lateinit var groupEmergencyPerDay: View
    private lateinit var textEmergencyPerDay: TextView
    private lateinit var sliderEmergencyPerDay: Slider

    private var config = UnlockGapConfig()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_unlock_gap, container, false)
        dataStore = DataStoreManager(requireContext())

        containerDayEnds = view.findViewById(R.id.container_day_ends)
        textEmergencyStatus = view.findViewById(R.id.text_emergency_status)
        btnEmergencySet = view.findViewById(R.id.btn_emergency_set)
        btnEmergencyRemove = view.findViewById(R.id.btn_emergency_remove)
        groupEmergencyPerDay = view.findViewById(R.id.group_emergency_per_day)
        textEmergencyPerDay = view.findViewById(R.id.text_emergency_per_day)
        sliderEmergencyPerDay = view.findViewById(R.id.slider_emergency_per_day)

        view.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<MaterialButton>(R.id.btn_help).setOnClickListener {
            ViewUtils.showHelpPopup(it, getString(R.string.unlock_gap_help_popup), null)
        }

        btnEmergencySet.setOnClickListener {
            showSetPasswordDialog { hash -> requestEmergencyUpdate { it.copy(emergencyPasswordHash = hash) } }
        }
        btnEmergencyRemove.setOnClickListener {
            requestEmergencyUpdate { it.copy(emergencyPasswordHash = "") }
        }
        sliderEmergencyPerDay.addOnChangeListener { _, value, _ ->
            textEmergencyPerDay.text = getString(R.string.unlock_gap_emergency_per_day, value.toInt())
        }
        sliderEmergencyPerDay.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                val count = slider.value.toInt()
                if (count != config.emergencyUnlocksPerDay) {
                    requestEmergencyUpdate { it.copy(emergencyUnlocksPerDay = count) }
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dataStore.settingsForEditing.collect { settings ->
                    config = settings.unlockGapConfig
                    render()
                }
            }
        }

        return view
    }

    private fun requestDayEndsUpdate(transform: (List<Int>) -> List<Int>) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateUnlockGapDayEnds(transform)
        }
    }

    private fun requestEmergencyUpdate(transform: (EmergencyUnlockPrefs) -> EmergencyUnlockPrefs) {
        viewLifecycleOwner.lifecycleScope.launch {
            dataStore.updateEmergencyUnlock(transform)
        }
    }

    private fun render() {
        if (!isAdded) return
        renderDayEnds()

        val hasPassword = config.emergencyPasswordHash.isNotEmpty()
        textEmergencyStatus.setText(
            if (hasPassword) R.string.unlock_gap_emergency_on else R.string.unlock_gap_emergency_off
        )
        btnEmergencySet.setText(
            if (hasPassword) R.string.unlock_gap_emergency_change else R.string.unlock_gap_emergency_set
        )
        btnEmergencyRemove.isVisible = hasPassword
        groupEmergencyPerDay.isVisible = hasPassword
        val perDay = config.emergencyUnlocksPerDay.coerceIn(1, UnlockGapConfig.MAX_EMERGENCY_UNLOCKS_PER_DAY)
        sliderEmergencyPerDay.value = perDay.toFloat()
        textEmergencyPerDay.text = getString(R.string.unlock_gap_emergency_per_day, perDay)
    }

    private fun renderDayEnds() {
        containerDayEnds.removeAllViews()
        val weekdayNames = DateFormatSymbols.getInstance().weekdays
        for (weekday in 0..6) {
            val minutes = config.dayEndMinutes.getOrElse(weekday) { 0 }
            val row = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = getString(
                    R.string.unlock_gap_day_end_row,
                    weekdayNames[weekday + Calendar.SUNDAY],
                    formatTime(minutes)
                )
                setOnClickListener { showDayEndPicker(weekday, minutes) }
            }
            containerDayEnds.addView(row)
        }
    }

    private fun showDayEndPicker(weekday: Int, currentMinutes: Int) {
        val clockFormat = if (DateFormat.is24HourFormat(requireContext())) {
            TimeFormat.CLOCK_24H
        } else {
            TimeFormat.CLOCK_12H
        }
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(currentMinutes / 60)
            .setMinute(currentMinutes % 60)
            .setTitleText(R.string.unlock_gap_day_end_title)
            .build()
        picker.addOnPositiveButtonClickListener {
            val minutes = picker.hour * 60 + picker.minute
            if (minutes > UnlockGapConfig.MAX_DAY_END_MINUTES) {
                Toast.makeText(requireContext(), R.string.unlock_gap_day_end_too_late, Toast.LENGTH_SHORT).show()
                return@addOnPositiveButtonClickListener
            }
            requestDayEndsUpdate { current ->
                val dayEnds = current.toMutableList()
                while (dayEnds.size < 7) dayEnds.add(0)
                dayEnds[weekday] = minutes
                dayEnds.take(7)
            }
        }
        picker.show(childFragmentManager, "unlock_gap_day_end")
    }

    private fun formatTime(minutesAfterMidnight: Int): String {
        val time = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesAfterMidnight / 60)
            set(Calendar.MINUTE, minutesAfterMidnight % 60)
        }
        return DateFormat.getTimeFormat(requireContext()).format(time.time)
    }

    private fun showSetPasswordDialog(onPassword: (hash: String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_anti_uninstall_password, null)
        val passwordField = dialogView.findViewById<TextInputEditText>(R.id.input_password)
        val confirmField = dialogView.findViewById<TextInputEditText>(R.id.input_confirm)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.anti_uninstall_set_password_title)
            .setView(dialogView)
            .setNegativeButton(R.string.anti_uninstall_cancel, null)
            .setPositiveButton(R.string.anti_uninstall_save) { _, _ ->
                val password = passwordField.text?.toString().orEmpty()
                val confirm = confirmField.text?.toString().orEmpty()
                when {
                    password.length < 4 ->
                        Toast.makeText(requireContext(), R.string.anti_uninstall_password_too_short, Toast.LENGTH_SHORT).show()
                    password != confirm ->
                        Toast.makeText(requireContext(), R.string.anti_uninstall_password_mismatch, Toast.LENGTH_SHORT).show()
                    else -> onPassword(AntiUninstallManager.hashPassword(password))
                }
            }
            .show()
    }
}
