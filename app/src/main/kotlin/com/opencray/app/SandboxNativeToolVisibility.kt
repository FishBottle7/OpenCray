package com.opencray.app

internal object SandboxNativeToolVisibility {
  const val TOOL_NAME_PREFIX: String = "sandbox_"

  fun hiddenToolNamePrefixes(
    settings: ResolvedSandboxSettings,
  ): Set<String> = if (shouldExposeToolDefinitions(settings)) {
    emptySet()
  } else {
    setOf(TOOL_NAME_PREFIX)
  }

  fun shouldExposeToolDefinitions(
    settings: ResolvedSandboxSettings,
  ): Boolean {
    val requestedBackend = SandboxExecutionBackendPreference.fromWireValue(
      settings.state.sanitized().defaultBackend,
    ) ?: SandboxExecutionBackendPreference.LOCAL
    // Keep sandbox-native tools hidden unless the user explicitly selected cloud execution
    // and the configured sandbox backend is actually usable for this run.
    return requestedBackend == SandboxExecutionBackendPreference.SANDBOX &&
      settings.canUseSandbox()
  }
}
