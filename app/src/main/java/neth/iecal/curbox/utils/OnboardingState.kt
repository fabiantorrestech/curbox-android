package neth.iecal.curbox.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object OnboardingState {
    private val gate = OnboardingRestoreGate()
    private fun prefs(context: Context) =
        context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

    fun canRestore(context: Context): Boolean =
        !prefs(context).getBoolean("isFirstLaunchComplete", false)

    fun hasRestoredBackup(context: Context): Boolean =
        prefs(context).getBoolean("onboardingBackupRestored", false)

    // Serialize completion and restore so a file picker cannot outlive the restore window.
    suspend fun restore(context: Context, writeSettings: suspend () -> Unit) = gate.restore({ canRestore(context) }) {
        writeSettings()
        withContext(Dispatchers.IO) {
            check(prefs(context).edit().putBoolean("onboardingBackupRestored", true).commit())
        }
    }

    suspend fun complete(context: Context, createStarterGroup: suspend () -> Unit) = gate.complete {
        if (!canRestore(context)) return@complete
        if (!hasRestoredBackup(context)) createStarterGroup()
        withContext(Dispatchers.IO) {
            check(prefs(context).edit().putBoolean("isFirstLaunchComplete", true).commit())
        }
    }
}

internal class OnboardingRestoreGate {
    private val mutex = Mutex()

    suspend fun restore(canRestore: () -> Boolean, write: suspend () -> Unit) = mutex.withLock {
        check(canRestore()) { "Restore is only available during onboarding" }
        write()
    }

    suspend fun complete(finish: suspend () -> Unit) = mutex.withLock { finish() }
}
