package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.ActivityCrashLogBinding
import neth.iecal.curbox.utils.CrashLogPreview
import java.io.File

class CrashLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val logFile = File(filesDir, "crash_log.txt")
        var content = getString(R.string.crash_logs_none_available)
        lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching { if (logFile.exists()) CrashLogPreview.read(logFile) else null }
            }
            content = preview.fold(
                onSuccess = { it ?: getString(R.string.crash_logs_none_available) },
                onFailure = { getString(R.string.crash_logs_read_error) }
            )
            binding.tvCrashLogs.text = content
        }

        binding.btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Curbox Crash Logs")
                putExtra(Intent.EXTRA_TEXT, content)
            }
            startActivity(Intent.createChooser(intent, "Share Crash Logs"))
        }

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnRestart.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(intent)
            finish()
        }

        binding.btnClear.setOnClickListener {
            if (logFile.exists() && logFile.delete()) {
                binding.tvCrashLogs.text = getString(R.string.crash_logs_none_available)
            }
        }
    }
}
