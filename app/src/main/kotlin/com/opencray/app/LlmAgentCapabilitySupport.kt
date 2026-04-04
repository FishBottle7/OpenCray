package com.opencray.app

import org.json.JSONObject

data class LlmAgentCapabilitySnapshot(
  val routeFingerprint: String = "",
  val verifiedAtEpochMs: Long? = null,
  val contextWindowTokens: Int? = null,
  val visionInputSupported: Boolean = false,
  val pdfInputSupported: Boolean = false,
  val nativeToolCallingAvailable: Boolean = false,
  val toolChoiceSupported: Boolean = false,
  val parallelToolCallsSupported: Boolean = false,
  val strictToolSchemaSupported: Boolean = false,
  val responsesContinuationSupported: Boolean = false,
  val builtinWebSearchSupported: Boolean = false,
  val assistantPhaseSupported: Boolean = false,
  val citationIncludeSupported: Boolean = false,
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
    .put("contextWindowTokens", contextWindowTokens)
    .put("visionInputSupported", visionInputSupported)
    .put("pdfInputSupported", pdfInputSupported)
    .put("nativeToolCallingAvailable", nativeToolCallingAvailable)
    .put("toolChoiceSupported", toolChoiceSupported)
    .put("parallelToolCallsSupported", parallelToolCallsSupported)
    .put("strictToolSchemaSupported", strictToolSchemaSupported)
    .put("responsesContinuationSupported", responsesContinuationSupported)
    .put("builtinWebSearchSupported", builtinWebSearchSupported)
    .put("assistantPhaseSupported", assistantPhaseSupported)
    .put("citationIncludeSupported", citationIncludeSupported)

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
        contextWindowTokens = payload.optInt("contextWindowTokens")
          .takeIf { value -> value > 0 },
        visionInputSupported = payload.optBoolean("visionInputSupported", false),
        pdfInputSupported = payload.optBoolean("pdfInputSupported", false),
        nativeToolCallingAvailable = payload.optBoolean("nativeToolCallingAvailable", false),
        toolChoiceSupported = payload.optBoolean("toolChoiceSupported", false),
        parallelToolCallsSupported = payload.optBoolean("parallelToolCallsSupported", false),
        strictToolSchemaSupported = payload.optBoolean("strictToolSchemaSupported", false),
        responsesContinuationSupported = payload.optBoolean("responsesContinuationSupported", false),
        builtinWebSearchSupported = payload.optBoolean("builtinWebSearchSupported", false),
        assistantPhaseSupported = payload.optBoolean("assistantPhaseSupported", false),
        citationIncludeSupported = payload.optBoolean("citationIncludeSupported", false),
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
  contextWindowTokens?.let { resolvedContextWindowTokens ->
    put("context_window_tokens", resolvedContextWindowTokens.toString())
  }
  if (wasVerified) {
    put("visionInputSupported", visionInputSupported.toString())
    put("pdfInputSupported", pdfInputSupported.toString())
  }
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
    put("parallelToolCalls", "true")
  }
  if (strictToolSchemaSupported) {
    put("toolSchemaStrict", "true")
  }
  if (wasVerified) {
    put("responsesContinuationSupported", responsesContinuationSupported.toString())
    put("nativeWebSearchEnabled", builtinWebSearchSupported.toString())
    put("assistantPhaseSupported", assistantPhaseSupported.toString())
    put("citationIncludeSupported", citationIncludeSupported.toString())
  }
}
