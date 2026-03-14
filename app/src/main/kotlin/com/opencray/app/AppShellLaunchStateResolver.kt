package com.opencray.app

import com.opencray.app.shell.AppShellDestination

internal object AppShellLaunchStateResolver {
  fun resolve(
    restoredTabRaw: String?,
    restoredSettingsSubpageRaw: String?,
    hasRestoredState: Boolean,
    startTabRaw: String?,
    startSettingsSubpageRaw: String?,
    hasStartExtras: Boolean,
    persistedDestination: AppShellDestination,
  ): AppShellDestination = when {
    hasRestoredState -> AppShellDestination.fromRaw(restoredTabRaw, restoredSettingsSubpageRaw)
    hasStartExtras -> AppShellDestination.fromRaw(startTabRaw, startSettingsSubpageRaw)
    else -> persistedDestination
  }
}
