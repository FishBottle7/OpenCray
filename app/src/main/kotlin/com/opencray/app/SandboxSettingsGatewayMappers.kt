package com.opencray.app

internal data class ParsedSandboxSettingsPayload(
  val state: SandboxSettingsState,
  val e2bApiKey: String?,
)

internal fun ResolvedSandboxSettings.toGatewayMap(
  localeTag: String,
): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "enabled" to state.enabled,
  "providerId" to state.providerId,
  "defaultBackend" to state.defaultBackend,
  "sessionMode" to state.sessionMode,
  "autoResume" to state.autoResume,
  "idleTimeoutMinutes" to state.idleTimeoutMinutes,
  "startupTimeoutMs" to state.startupTimeoutMs,
  "requestTimeoutMs" to state.requestTimeoutMs,
  "timeoutAction" to state.timeoutAction,
  "templateId" to state.templateId,
  "e2bApiKey" to e2bApiKey.orEmpty(),
  "apiKeyConfigured" to hasResolvedE2bApiKey(),
)

internal fun parseSandboxSettingsPayload(
  payload: Map<String, Any?>,
  existingState: SandboxSettingsState = SandboxSettingsState(),
): ParsedSandboxSettingsPayload {
  fun readInt(key: String, fallback: Int): Int {
    val rawValue = payload[key] ?: return fallback
    return when (rawValue) {
      is Int -> rawValue
      is Number -> rawValue.toInt()
      else -> rawValue.toString().toIntOrNull() ?: fallback
    }
  }

  fun readLong(key: String, fallback: Long): Long {
    val rawValue = payload[key] ?: return fallback
    return when (rawValue) {
      is Long -> rawValue
      is Number -> rawValue.toLong()
      else -> rawValue.toString().toLongOrNull() ?: fallback
    }
  }

  val state = SandboxSettingsState(
    enabled = payload["enabled"] as? Boolean ?: existingState.enabled,
    providerId = payload["providerId"]?.toString() ?: existingState.providerId,
    defaultBackend = payload["defaultBackend"]?.toString() ?: existingState.defaultBackend,
    sessionMode = payload["sessionMode"]?.toString() ?: existingState.sessionMode,
    autoResume = payload["autoResume"] as? Boolean ?: existingState.autoResume,
    idleTimeoutMinutes = readInt("idleTimeoutMinutes", existingState.idleTimeoutMinutes),
    startupTimeoutMs = readLong("startupTimeoutMs", existingState.startupTimeoutMs),
    requestTimeoutMs = readLong("requestTimeoutMs", existingState.requestTimeoutMs),
    timeoutAction = payload["timeoutAction"]?.toString() ?: existingState.timeoutAction,
    templateId = payload["templateId"]?.toString() ?: existingState.templateId,
    e2bApiKeyCredentialRef = existingState.e2bApiKeyCredentialRef,
  ).sanitized()
  val e2bApiKey = if (payload.containsKey("e2bApiKey")) {
    payload["e2bApiKey"]?.toString().orEmpty()
  } else {
    null
  }
  return ParsedSandboxSettingsPayload(
    state = state,
    e2bApiKey = e2bApiKey,
  )
}
