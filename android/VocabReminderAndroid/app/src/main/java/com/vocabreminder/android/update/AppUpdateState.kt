package com.vocabreminder.android.update

/** Where the self-update flow stands, as the settings panel and banner show it. */
sealed class AppUpdateState {
    /** Debug and deviceQa builds: no panel, no network calls. */
    object Disabled : AppUpdateState()
    object Idle : AppUpdateState()
    object Checking : AppUpdateState()
    object UpToDate : AppUpdateState()
    data class Available(val release: AppRelease) : AppUpdateState()

    /** The user has not allowed this app under "Install unknown apps" yet. */
    data class NeedsPermission(val release: AppRelease) : AppUpdateState()
    data class Downloading(val release: AppRelease, val percent: Int) : AppUpdateState()

    /** Handed to the system installer; waiting for the user to confirm there. */
    data class Installing(val release: AppRelease) : AppUpdateState()
    data class Failed(val message: String, val release: AppRelease? = null) : AppUpdateState()

    val busy: Boolean get() = this is Checking || this is Downloading
}
