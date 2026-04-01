package com.opencray.app.agent

import kotlin.math.max

internal fun AgentDescriptor.toHostMap(
  config: AgentConfig? = null,
  isActive: Boolean = false,
): Map<String, Any?> = buildMap {
  put("agentId", agentId)
  put("displayName", displayName)
  put("presetName", config?.presetName ?: presetName)
  put("plasticity", config?.plasticity ?: plasticity)
  put("mode", config?.mode ?: "full")
  config?.callsYou?.let { put("callsYou", it) }
  config?.addressStyle?.let { put("addressStyle", it) }
  config?.voiceSummary?.let { put("voiceSummary", it) }
  config?.verbosity?.let { put("verbosity", it) }
  config?.relationshipStyle?.let { put("relationshipStyle", it) }
  config?.riskTolerance?.let { put("riskTolerance", it) }
  config?.toolUseBias?.let { put("toolUseBias", it) }
  config?.baseDescription?.let { put("baseDescription", it) }
  config?.collaborationGuidance?.let { put("collaborationGuidance", it) }
  config?.escalationRules?.let { put("escalationRules", it) }
  config?.forbiddenBehaviors?.let { put("forbiddenBehaviors", it) }
  config?.llm?.toHostMap()?.let { put("llm", it) }
  config?.avatar?.toHostMap()?.let { put("avatar", it) }
  if (config != null) {
    put(
      "imageReferences",
      config.imageReferences.map(AgentImageReferenceConfig::toHostMap),
    )
  }
  activeSessionId?.let { put("activeSessionId", it) }
  avatarSeed?.let { put("avatarSeed", it) }
  put("createdAtEpochMs", max(createdAtEpochMs, config?.createdAtEpochMs ?: createdAtEpochMs))
  put("updatedAtEpochMs", max(updatedAtEpochMs, config?.updatedAtEpochMs ?: updatedAtEpochMs))
  put("isArchived", isArchived)
  put("isActive", isActive)
}

internal fun parseAgentCreateRequestPayload(
  payload: Map<String, Any?>,
): AgentCreateRequest {
  val normalized = payload.toStringAnyMap()
  return AgentCreateRequest(
    displayName = normalized.stringValue("displayName", fallback = "Untitled agent"),
    presetName = normalized.stringValue("presetName", fallback = "steady"),
    plasticity = normalized.stringValue("plasticity", fallback = "medium"),
    callsYou = normalized.stringValue("callsYou"),
    addressStyle = normalized.stringValue("addressStyle"),
    mode = normalized.stringValue("mode", fallback = "full"),
    voiceSummary = normalized.stringValue("voiceSummary"),
    verbosity = normalized.stringValue("verbosity"),
    relationshipStyle = normalized.stringValue("relationshipStyle"),
    riskTolerance = normalized.stringValue("riskTolerance"),
    toolUseBias = normalized.stringValue("toolUseBias"),
    baseDescription = normalized.stringValue("baseDescription"),
    collaborationGuidance = normalized.stringValue("collaborationGuidance"),
    escalationRules = normalized.stringValue("escalationRules"),
    forbiddenBehaviors = normalized.stringValue("forbiddenBehaviors"),
    llm = normalized.mapValue("llm")?.parseAgentLlmConfigPayload(),
    avatar = normalized.mapValue("avatar")?.parseAgentAvatarConfigPayload(),
    imageReferences = normalized.listValue("imageReferences")
      .mapNotNull { value -> value.toStringAnyMap().parseAgentImageReferenceConfigPayload() },
    activateOnCreate = normalized.booleanValue("activateOnCreate", fallback = true),
  )
}

private fun AgentLlmConfig.toHostMap(): Map<String, Any?> = buildMap {
  put("provider", provider)
  put("protocol", protocol)
  baseUrl?.let { put("baseUrl", it) }
  put("model", model)
  reasoningEffort?.let { put("reasoningEffort", it) }
}

private fun AgentAvatarConfig.toHostMap(): Map<String, Any?> = buildMap {
  put("source", source)
  settingsAssetId?.let { put("settingsAssetId", it) }
}

private fun AgentImageReferenceConfig.toHostMap(): Map<String, Any?> = buildMap {
  put("referenceId", referenceId)
  put("label", label)
  settingsAssetId?.let { put("settingsAssetId", it) }
}

private fun Map<String, Any?>.parseAgentLlmConfigPayload(): AgentLlmConfig? {
  val provider = stringValue("provider")
  val protocol = stringValue("protocol")
  val model = stringValue("model")
  if (provider.isBlank() || protocol.isBlank() || model.isBlank()) {
    return null
  }
  return AgentLlmConfig(
    provider = provider,
    protocol = protocol,
    baseUrl = stringValue("baseUrl").ifBlank { null },
    apiKey = stringValue("apiKey").ifBlank { null },
    model = model,
    reasoningEffort = stringValue("reasoningEffort").ifBlank { null },
  )
}

private fun Map<String, Any?>.parseAgentAvatarConfigPayload(): AgentAvatarConfig? {
  val source = stringValue("source")
  if (source.isBlank()) {
    return null
  }
  return AgentAvatarConfig(
    source = source,
    settingsAssetId = stringValue("settingsAssetId").ifBlank { null },
  )
}

private fun Map<String, Any?>.parseAgentImageReferenceConfigPayload(): AgentImageReferenceConfig? {
  val referenceId = stringValue("referenceId")
  val label = stringValue("label")
  if (referenceId.isBlank() || label.isBlank()) {
    return null
  }
  return AgentImageReferenceConfig(
    referenceId = referenceId,
    label = label,
    settingsAssetId = stringValue("settingsAssetId").ifBlank { null },
  )
}

private fun Any?.toStringAnyMap(): Map<String, Any?> = when (this) {
  is Map<*, *> -> entries.mapNotNull { (key, value) ->
    (key as? String)?.let { stringKey -> stringKey to value }
  }.toMap()
  else -> emptyMap()
}

private fun Map<String, Any?>.stringValue(
  key: String,
  fallback: String = "",
): String = (this[key] as? String)?.trim().orEmpty().ifBlank { fallback }

private fun Map<String, Any?>.booleanValue(
  key: String,
  fallback: Boolean,
): Boolean = this[key] as? Boolean ?: fallback

private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?>? =
  this[key].toStringAnyMap().takeIf { candidate -> candidate.isNotEmpty() }

private fun Map<String, Any?>.listValue(key: String): List<Any?> = when (val value = this[key]) {
  is List<*> -> value.toList()
  else -> emptyList()
}
