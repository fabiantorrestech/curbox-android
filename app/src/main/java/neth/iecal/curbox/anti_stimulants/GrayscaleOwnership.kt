package neth.iecal.curbox.anti_stimulants

/**
 * Decides when Curbox may switch the system grayscale filter, so it never fights a filter the
 * user or another app (e.g. GrayScaler+) switched on.
 *
 * Rules:
 * - Curbox only switches the filter on when it is off, and only owns it once it is seen on.
 * - Curbox only switches the filter off when it owns it and it is still on.
 * - One command at a time: Shizuku runs each command on its own thread, so a later "off" could
 *   otherwise land before an earlier "on". Requests made while a command is pending are queued
 *   (latest wins) and replayed once the command lands or times out.
 * - If the filter goes off while Curbox owns it and nothing is pending, someone else switched it
 *   off, so Curbox gives up ownership and will not touch a later "on".
 *
 * Pure logic with no Android dependencies; [GrayscaleOwner] feeds it what the system reports and
 * executes the returned commands.
 */
internal class GrayscaleOwnership(owned: Boolean = false) {

    enum class Command { ENABLE, DISABLE, NONE }

    var owned: Boolean = owned
        private set

    /** Target state of the command in flight, or null when nothing is pending. */
    var pending: Boolean? = null
        private set

    private var queued: Boolean? = null

    /** Curbox wants the filter on ([wants] = true) or off for the app in front. */
    fun request(wants: Boolean, systemOn: Boolean): Command {
        if (pending != null) {
            queued = wants
            return Command.NONE
        }
        if (owned && !systemOn) owned = false

        return when {
            wants && !systemOn -> {
                pending = true
                Command.ENABLE
            }
            !wants && owned -> {
                pending = false
                Command.DISABLE
            }
            else -> Command.NONE
        }
    }

    /** The system filter state changed, or was read right after a command was issued. */
    fun observed(systemOn: Boolean): Command {
        val target = pending
        if (target == null) {
            if (owned && !systemOn) owned = false
            return Command.NONE
        }
        if (systemOn != target) return Command.NONE

        owned = target
        pending = null
        return replayQueued(systemOn)
    }

    /** The pending command did not show up in time. */
    fun timedOut(systemOn: Boolean): Command {
        val target = pending ?: return Command.NONE
        pending = null
        // A failed enable leaves nothing of ours on; a failed disable leaves our filter on.
        owned = if (target) false else systemOn
        return replayQueued(systemOn)
    }

    private fun replayQueued(systemOn: Boolean): Command {
        val next = queued ?: return Command.NONE
        queued = null
        return request(next, systemOn)
    }
}
