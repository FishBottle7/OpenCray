package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult

internal fun LlmConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "enabled" to enabled,
  "streamingEnabled" to streamingEnabled,
  "providerId" to providerId,
  "selectedProviderOptionId" to selectedProviderOptionId,
  "protocol" to protocol,
  "providerOptions" to providerOptions.map { option -> option.toGatewayMap() },
  "providerName" to providerName,
  "providerNotes" to providerNotes,
  "baseUrl" to baseUrl,
  "apiKey" to apiKey,
  "model" to model,
  "reasoningEffort" to reasoningEffort,
  "systemPrompt" to systemPrompt,
  "openAiPromptCacheKeyStrategy" to openAiPromptCacheKeyStrategy,
  "openAiPromptCacheRetention" to openAiPromptCacheRetention,
  "anthropicPromptCachingEnabled" to anthropicPromptCachingEnabled,
  "anthropicPromptCacheTtl" to anthropicPromptCacheTtl,
  "contextBudgetPreset" to contextBudgetPreset,
  "contextBudgetReservedOutputTokens" to contextBudgetReservedOutputTokens,
  "contextBudgetSafetyMarginTokens" to contextBudgetSafetyMarginTokens,
  "contextBudgetEffectiveInputPercent" to contextBudgetEffectiveInputPercent,
  "helperText" to helperText,
  "agentCapability" to agentCapability.toGatewayMap(),
)

internal fun LlmValidationResult.toGatewayMap(): Map<String, Any?> = mapOf(
  "isSuccess" to isSuccess,
  "message" to message,
  "agentCapability" to agentCapability?.toGatewayMap(),
)

private fun LlmProviderOptionSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "title" to title,
  "subtitle" to subtitle,
  "defaultBaseUrl" to defaultBaseUrl,
  "defaultModel" to defaultModel,
  "protocol" to protocol,
  "apiKey" to apiKey,
  "isCustom" to isCustom,
)

private fun LlmAgentCapabilitySnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "routeFingerprint" to routeFingerprint,
  "verifiedAtEpochMs" to verifiedAtEpochMs,
  "wasVerified" to wasVerified,
  "contextWindowTokens" to contextWindowTokens,
  "visionInputSupported" to visionInputSupported,
  "nativeToolCallingAvailable" to nativeToolCallingAvailable,
  "toolChoiceSupported" to toolChoiceSupported,
  "parallelToolCallsSupported" to parallelToolCallsSupported,
  "strictToolSchemaSupported" to strictToolSchemaSupported,
  "responsesContinuationSupported" to responsesContinuationSupported,
  "builtinWebSearchSupported" to builtinWebSearchSupported,
  "assistantPhaseSupported" to assistantPhaseSupported,
  "citationIncludeSupported" to citationIncludeSupported,
)
