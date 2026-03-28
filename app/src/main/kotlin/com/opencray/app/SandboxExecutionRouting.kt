package com.opencray.app

internal enum class ResolvedExecutionBackend(
  val wireValue: String,
) {
  LOCAL_HOST("local_host"),
  SANDBOX_REMOTE("sandbox_remote"),
  UNAVAILABLE("unavailable"),
  ;
}

internal data class SandboxExecutionRouteSelection(
  val requestedBackend: SandboxExecutionBackendPreference,
  val resolvedBackend: ResolvedExecutionBackend,
  val providerId: String,
  val sandboxEnabled: Boolean,
  val sandboxCredentialConfigured: Boolean,
  val sessionMode: String,
  val timeoutAction: String,
  val autoResume: Boolean,
  val reasonCode: String,
  val detail: String,
  val errorCode: String? = null,
) {
  fun metadata(): Map<String, String> = mapOf(
    "executionBackendRequested" to requestedBackend.wireValue,
    "executionBackend" to resolvedBackend.wireValue,
    "sandboxProvider" to providerId,
    "sandboxEnabled" to sandboxEnabled.toString(),
    "sandboxCredentialConfigured" to sandboxCredentialConfigured.toString(),
    "sandboxSessionMode" to sessionMode,
    "sandboxTimeoutAction" to timeoutAction,
    "sandboxAutoResume" to autoResume.toString(),
    "sandboxRouteReasonCode" to reasonCode,
  )
}

internal object SandboxExecutionRouting {
  const val ERROR_SANDBOX_BACKEND_UNAVAILABLE: String = "SANDBOX_BACKEND_UNAVAILABLE"
  const val ERROR_SANDBOX_PROVIDER_DISABLED: String = "SANDBOX_PROVIDER_DISABLED"
  const val ERROR_SANDBOX_CREDENTIALS_MISSING: String = "SANDBOX_CREDENTIALS_MISSING"
  const val ERROR_SANDBOX_PROVIDER_UNSUPPORTED: String = "SANDBOX_PROVIDER_UNSUPPORTED"
  const val ERROR_SANDBOX_RUNTIME_UNAVAILABLE: String = "SANDBOX_RUNTIME_UNAVAILABLE"

  fun shouldResolveSandboxBackend(settings: ResolvedSandboxSettings): Boolean {
    val requestedBackend = SandboxExecutionBackendPreference.fromWireValue(
      settings.state.sanitized().defaultBackend,
    ) ?: SandboxExecutionBackendPreference.LOCAL
    return when (requestedBackend) {
      SandboxExecutionBackendPreference.LOCAL -> false
      SandboxExecutionBackendPreference.AUTO,
      SandboxExecutionBackendPreference.SANDBOX,
      -> settings.canUseSandbox()
    }
  }

  fun resolveSelection(
    settings: ResolvedSandboxSettings,
    sandboxRuntimeAvailable: Boolean,
  ): SandboxExecutionRouteSelection {
    val state = settings.state.sanitized()
    val requestedBackend = SandboxExecutionBackendPreference.fromWireValue(state.defaultBackend)
      ?: SandboxExecutionBackendPreference.LOCAL
    val providerId = SandboxProviderId.fromWireValue(state.providerId)?.wireValue
      ?: SandboxProviderId.E2B.wireValue
    val sandboxCredentialConfigured = settings.hasResolvedE2bApiKey()
    val commonSelection: (
      ResolvedExecutionBackend,
      String,
      String,
      String?,
    ) -> SandboxExecutionRouteSelection = { resolvedBackend, reasonCode, detail, errorCode ->
      SandboxExecutionRouteSelection(
        requestedBackend = requestedBackend,
        resolvedBackend = resolvedBackend,
        providerId = providerId,
        sandboxEnabled = state.enabled,
        sandboxCredentialConfigured = sandboxCredentialConfigured,
        sessionMode = state.sessionMode,
        timeoutAction = state.timeoutAction,
        autoResume = state.autoResume,
        reasonCode = reasonCode,
        detail = detail,
        errorCode = errorCode,
      )
    }

    return when (requestedBackend) {
      SandboxExecutionBackendPreference.LOCAL -> commonSelection(
        ResolvedExecutionBackend.LOCAL_HOST,
        "local_preference",
        "Using local execution backend by sandbox preference.",
        null,
      )

      SandboxExecutionBackendPreference.AUTO -> when {
        !state.enabled -> commonSelection(
          ResolvedExecutionBackend.LOCAL_HOST,
          "auto_fallback_provider_disabled",
          "Sandbox provider is disabled; auto selection fell back to the local execution backend.",
          null,
        )

        !sandboxCredentialConfigured -> commonSelection(
          ResolvedExecutionBackend.LOCAL_HOST,
          "auto_fallback_credentials_missing",
          "Sandbox provider credentials are missing; auto selection fell back to the local execution backend.",
          null,
        )

        !sandboxRuntimeAvailable -> commonSelection(
          ResolvedExecutionBackend.LOCAL_HOST,
          "auto_fallback_runtime_unavailable",
          "Sandbox runtime backend is not wired yet; auto selection fell back to the local execution backend.",
          null,
        )

        else -> commonSelection(
          ResolvedExecutionBackend.SANDBOX_REMOTE,
          "auto_sandbox_selected",
          "Using sandbox execution backend through auto selection.",
          null,
        )
      }

      SandboxExecutionBackendPreference.SANDBOX -> when {
        !state.enabled -> commonSelection(
          ResolvedExecutionBackend.UNAVAILABLE,
          "sandbox_requested_provider_disabled",
          "Sandbox execution was requested but the sandbox provider is disabled.",
          ERROR_SANDBOX_PROVIDER_DISABLED,
        )

        !sandboxCredentialConfigured -> commonSelection(
          ResolvedExecutionBackend.UNAVAILABLE,
          "sandbox_requested_credentials_missing",
          "Sandbox execution was requested but the E2B API key is not configured.",
          ERROR_SANDBOX_CREDENTIALS_MISSING,
        )

        providerId != SandboxProviderId.E2B.wireValue -> commonSelection(
          ResolvedExecutionBackend.UNAVAILABLE,
          "sandbox_requested_provider_unsupported",
          "Sandbox provider '$providerId' is not supported yet.",
          ERROR_SANDBOX_PROVIDER_UNSUPPORTED,
        )

        !sandboxRuntimeAvailable -> commonSelection(
          ResolvedExecutionBackend.UNAVAILABLE,
          "sandbox_requested_runtime_unavailable",
          "Sandbox execution was requested but no sandbox runtime backend is wired yet.",
          ERROR_SANDBOX_RUNTIME_UNAVAILABLE,
        )

        else -> commonSelection(
          ResolvedExecutionBackend.SANDBOX_REMOTE,
          "sandbox_preference",
          "Using sandbox execution backend by explicit preference.",
          null,
        )
      }
    }
  }
}
