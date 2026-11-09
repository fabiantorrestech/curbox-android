package neth.iecal.curbox.anti_stimulants

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.utils.GrayscaleControl
import java.io.File

/**
 * Switches the system grayscale filter for [GrayScaleFilter] without touching a filter the user or
 * another app switched on. Watches the system setting and applies [GrayscaleOwnership]'s rules.
 * Everything runs on the main thread.
 */
class GrayscaleOwner(private val control: GrayscaleControl) {

    companion object {
        private const val TAG = "GrayscaleOwner"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"

        // Kept in noBackupFilesDir so a backup restore can never bring back a stale "Curbox owns it".
        private const val OWNED_FILE = "grayscale_owned_by_curbox"
        private const val COMMAND_TIMEOUT_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var state: GrayscaleOwnership? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            update { context, state -> state.observed(control.isGrayscaleEnabled(context)) }
        }
    }

    private val timeout = Runnable {
        update { context, state -> state.timedOut(control.isGrayscaleEnabled(context)) }
    }

    /** Safe to call again; GrayScaleFilter.setup() runs on every refresh. */
    fun attach(context: Context) {
        if (this.context != null) return
        try {
            this.context = context
            state = GrayscaleOwnership(owned = readOwned(context))
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(DALTONIZER_ENABLED), false, observer
            )
            // Drops ownership if the filter was switched off while the service was not running.
            update { ctx, state -> state.observed(control.isGrayscaleEnabled(ctx)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach", e)
            CrashLogger(context).logNonFatalError(e)
        }
    }

    fun detach() {
        val context = this.context ?: return
        handler.removeCallbacks(timeout)
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach", e)
        }
        this.context = null
        state = null
    }

    /** Curbox wants the filter on ([wants] = true) or off for the app in front. */
    fun request(wants: Boolean) {
        update { context, state -> state.request(wants, control.isGrayscaleEnabled(context)) }
    }

    private fun update(change: (Context, GrayscaleOwnership) -> GrayscaleOwnership.Command) {
        val context = this.context ?: return
        val state = this.state ?: return
        try {
            val ownedBefore = state.owned
            val command = change(context, state)
            if (state.owned != ownedBefore) writeOwned(context, state.owned)
            if (state.pending == null) handler.removeCallbacks(timeout)

            when (command) {
                GrayscaleOwnership.Command.ENABLE -> {
                    Log.d(TAG, "Enabling monochrome")
                    control.enableGrayscale(context)
                }
                GrayscaleOwnership.Command.DISABLE -> {
                    Log.d(TAG, "Disabling monochrome")
                    control.disableGrayscale(context)
                }
                GrayscaleOwnership.Command.NONE -> return
            }

            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
            // The direct WRITE_SECURE_SETTINGS path lands immediately; Shizuku reports via the observer.
            update { ctx, s -> s.observed(control.isGrayscaleEnabled(ctx)) }
        } catch (e: Exception) {
            Log.e(TAG, "Grayscale update failed", e)
            CrashLogger(context).logNonFatalError(e)
        }
    }

    private fun ownedFile(context: Context) = File(context.noBackupFilesDir, OWNED_FILE)

    private fun readOwned(context: Context): Boolean {
        val file = ownedFile(context)
        return file.exists() && file.readText().trim() == "1"
    }

    private fun writeOwned(context: Context, owned: Boolean) {
        ownedFile(context).writeText(if (owned) "1" else "0")
    }
}
