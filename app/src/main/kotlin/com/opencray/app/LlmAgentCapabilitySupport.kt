package com.opencray.app

import org.json.JSONObject

data class LlmAgentCapabilitySnapshot(
  val routeFingerprint: String = "",
  val verifiedAtEpochMs: Long? = null,
  val nativeToolCallingAvailable: Boolean = false,
  val toolChoiceSupported: Boolean = false,
  val parallelToolCallsSupported: Boolean = false,
  val strictToolSchemaSupported: Boolean = false,
) {
  val wasVerified: Boolean
    get() = verifiedAtEpochMs != null

  fun matchesRoute(
    protocol: String,
    baseUrl: String,
    model: String,
  ): Boolean = routeFingerprint == llmRouteFingerprint(
    protocol = protocol,
    baseUrl = baseUrl,
    model = model,
  )

  fun normalizedForRoute(
    protocol: String,
    baseUrl: String,
    model: String,
  ): LlmAgentCapabilitySnapshot {
    val fingerprint = llmRouteFingerprint(
      protocol = protocol,
      baseUrl = baseUrl,
      model = model,
    )
    return if (routeFingerprint == fingerprint) {
      copy(routeFingerprint = fingerprint)
    } else {
      unknown(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
      )
    }
  }

  fun toJson(): JSONObject = JSONObject()
    .put("routeFingerprint", routeFingerprint)
    .put("verifiedAtEpochMs", verifiedAtEpochMs)
    .put("nativeToolCallingAvailable", nativeToolCallingAvailable)
    .put("toolChoiceSupported", toolChoiceSupported)
    .put("parallelToolCallsSupported", parallelToolCallsSupported)
    .put("strictToolSchemaSupported", strictToolSchemaSupported)

  companion object {
    fun unknown(
      protocol: String,
      baseUrl: String,
      model: String,
    ): LlmAgentCapabilitySnapshot = LlmAgentCapabilitySnapshot(
      routeFingerprint = llmRouteFingerprint(
        protocol = protocol,
        baseUrl = baseUrl,
        model = model,
      ),
    )

    fun fromJson(payload: JSONObject): LlmAgentCapabilitySnapshot? {
      val routeFingerprint = payload.optString("routeFingerprint").trim()
      if (routeFingerprint.isBlank()) {
        return null
      }
      return LlmAgentCapabilitySnapshot(
        routeFingerprint = routeFingerprint,
        verifiedAtEpochMs = payload.optLong("verifiedAtEpochMs")
          .takeIf { value -> value > 0L },
        nativeToolCallingAvailable = payload.optBoolean("nativeToolCallingAvailable", false),
        toolChoiceSupported = payload.optBoolean("toolChoiceSupported", false),
        parallelToolCallsSupported = payload.optBoolean("parallelToolCallsSupported", false),
        strictToolSchemaSupported = payload.optBoolean("strictToolSchemaSupported", false),
      )
    }
  }
}

internal fun llmRouteFingerprint(
  protocol: String,
  baseUrl: String,
  model: String,
): String {
  val normalizedProtocol = LlmProviderProtocols.normalize(protocol)
  val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
  val normalizedModel = model.trim()
  return listOf(normalizedProtocol, normalizedBaseUrl, normalizedModel).joinToString(separator = "|")
}

internal fun LlmAgentCapabilitySnapshot.runtimeMetadataOverrides(): Map<String, String> = buildMap {
  put(
    "nativeToolCallingAvailable",
    if (wasVerified) {
      nativeToolCallingAvailable.toString()
    } else {
      "true"
    },
  )
  verifiedAtEpochMs?.let { validatedAt -> put("agentCapabilityVerifiedAtEpochMs", validatedAt.toString()) }
  if (!nativeToolCallingAvailable) {
    return@buildMap
  }
  if (toolChoiceSupported) {
    put("toolChoiceMode", "auto")
  }
  if (parallelToolCallsSupported) {
    put("parallelToolCalls", "false")
  }
  if (strictToolSchemaSupported) {
    put("toolSchemaStrict", "true")
  }
}
