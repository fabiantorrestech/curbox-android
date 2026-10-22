package neth.iecal.curbox.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

class BackupManager(context: Context) {
    private val app = context.applicationContext
    private val dataStore = DataStoreManager(app)

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = SettingsBackupCodec.encode(dataStore.settings.first())
        val output = app.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Unable to open backup file")
        output.use { it.write(bytes) }
    }

    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        check(OnboardingState.canRestore(app)) { "Restore is only available during onboarding" }
        val input = app.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open backup file")
        val settings = input.use(SettingsBackupCodec::decode)
        dataStore.restoreSettingsDuringOnboarding(settings)
        if (settings.serviceProtectionConfig.isEnabled) {
            neth.iecal.curbox.services.ServiceWatchdogJob.schedule(app)
            ServiceProtectionManager.reinforceBackgroundExecution(app)
        }
    }
}
