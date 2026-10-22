package neth.iecal.curbox.ui.activity

import android.content.pm.PackageManager
import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityBarcodeCaptureBinding

class PortraitCaptureActivity : CaptureActivity() {
    private lateinit var binding: ActivityBarcodeCaptureBinding
    private var flashlightOn = false

    override fun initializeContent(): DecoratedBarcodeView {
        binding = ActivityBarcodeCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buttonFlashlight.visibility = if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        ) View.VISIBLE else View.GONE
        binding.barcodeScanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
            override fun onTorchOn() {
                flashlightOn = true
                binding.buttonFlashlight.setText(R.string.scanner_flashlight_off)
            }

            override fun onTorchOff() {
                flashlightOn = false
                binding.buttonFlashlight.setText(R.string.scanner_flashlight_on)
            }
        })
        binding.buttonFlashlight.setOnClickListener {
            if (flashlightOn) binding.barcodeScanner.setTorchOff()
            else binding.barcodeScanner.setTorchOn()
        }
        return binding.barcodeScanner
    }

    override fun onPause() {
        binding.barcodeScanner.setTorchOff()
        super.onPause()
    }
}
