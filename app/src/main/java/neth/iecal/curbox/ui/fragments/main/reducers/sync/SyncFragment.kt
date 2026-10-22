package neth.iecal.curbox.ui.fragments.main.reducers.sync

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import neth.iecal.curbox.R

/**
 * Standalone account and sync screen, reached from the Settings tab. Lives on its
 * own so the keyboard has room and never shoves the bottom bar around. Reuses
 * [AccountController] so it matches everywhere else. Never shown on F-Droid.
 */
class SyncFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "sync"
    }

    private lateinit var controller: AccountController

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { controller.pairWith(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.fragment_sync, container, false)
        v.findViewById<MaterialButton>(R.id.btn_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        controller = AccountController(v, this) {
            scanLauncher.launch(ScanOptions()
                .setCaptureActivity(neth.iecal.curbox.ui.activity.PortraitCaptureActivity::class.java)
                .setOrientationLocked(true)
                .setPrompt("Point at the pairing code"))
        }
        controller.bind()
        return v
    }
}
