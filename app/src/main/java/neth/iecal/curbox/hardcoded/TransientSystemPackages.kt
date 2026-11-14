package neth.iecal.curbox.hardcoded

/**
 * System screens that open on top of an app for a moment and then return to it: the share
 * sheet and the permission dialog. The app blocker does not treat switching to one of these as
 * leaving the app. The file picker is left out because it is also a full Files app.
 */
val TRANSIENT_SYSTEM_PACKAGES: Set<String> = setOf(
    // Share sheet and "open with" chooser (the system resolver before Android 14)
    "android",
    "com.android.intentresolver",
    // Runtime permission dialog
    "com.google.android.permissioncontroller",
    "com.android.permissioncontroller",
)
