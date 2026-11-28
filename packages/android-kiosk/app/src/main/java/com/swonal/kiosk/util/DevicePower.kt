package com.swonal.kiosk.util

/**
 * Restart / Shut Down for the Tonal V1 (Quick Preferences → Power), run as root via [RootShell].
 *
 * Callers must disengage resistance first (see `SwonalRepository.disableWeightAndAwait`).
 */
object DevicePower {

    enum class Action(
        /** Root shell command. Neither returns on success: the device goes down. */
        val command: String,
        val progressMessage: String,
        val failureVerb: String
    ) {
        /** Framework reboot (`PowerManager.reboot`), falling back to init's reboot. */
        RESTART(
            command = "sync; svc power reboot || reboot",
            progressMessage = "Restarting Tonal…",
            failureVerb = "restart"
        ),

        /**
         * Power off through init (`sys.powerctl`), which syncs and unmounts first. Android 6's
         * `svc power` has no `shutdown` subcommand. Turn it back on with the power button.
         */
        SHUT_DOWN(
            command = "sync; reboot -p",
            progressMessage = "Shutting down Tonal…",
            failureVerb = "shut down"
        )
    }

    suspend fun perform(action: Action): RootShell.CommandResult = RootShell.run(action.command)
}
