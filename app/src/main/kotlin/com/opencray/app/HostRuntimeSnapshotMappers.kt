package com.opencray.app

import com.opencray.app.facade.llm.LlmConfigSnapshot
import com.opencray.app.facade.llm.LlmProviderOptionSnapshot
import com.opencray.app.facade.llm.LlmValidationResult
import com.opencray.app.facade.llm.OnDeviceLlmModelOptionSnapshot
import com.opencray.app.facade.media.MediaProviderSnapshot
import com.opencray.app.facade.media.MediaSpeechConfigSnapshot
import com.opencray.app.facade.media.OnDeviceSttSnapshot
import com.opencray.app.facade.media.VoiceProviderSnapshot
import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsSnapshot
import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot
import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.safety.SafetySettingsLocationSnapshot
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot
import com.opencray.app.facade.settings.SettingsRowSnapshot

internal fun SettingsOverviewSnapshot.toMap(): Map<String, Any?> = mapOf(
  "eyebrow" to eyebrow,
  "title" to title,
  "subtitle" to subtitle,
  "deviceTitle" to deviceTitle,
  "deviceSummary" to deviceSummary,
  "entries" to entries.map { entry ->
    mapOf(
      "routeId" to entry.routeId.wireValue,
      "title" to entry.title,
    )
  },
)

internal fun SettingsDetailSnapshot.toMap(): Map<String, Any?> = mapOf(
  "routeId" to routeId.wireValue,
  "title" to title,
  "subtitle" to subtitle,
  "sections" to sections.map { section -> section.toMap() },
)

internal fun NetworkSearchConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "slots" to slots.map { slot -> slot.toMap() },
)

internal fun NetworkSearchSlotSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "label" to label,
  "baseUrl" to baseUrl,
  "model" to model,
  "apiKey" to apiKey,
  "enabled" to enabled,
)

internal fun MediaSpeechConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "imageGeneration" to imageGeneration.toMap(),
  "videoGeneration" to videoGeneration.toMap(),
  "voiceGeneration" to voiceGeneration.toMap(),
  "sttRouteId" to sttRouteId,
  "externalStt" to externalStt.toMap(),
  "onDeviceModel" to onDeviceModel.toMap(),
)

internal fun MediaProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

internal fun VoiceProviderSnapshot.toMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "voicePreset" to voicePreset,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

internal fun OnDeviceSttSnapshot.toMap(): Map<String, Any?> = mapOf(
  "modelPackage" to modelPackage,
  "downloadStatus" to downloadStatus,
)

internal fun SettingsSectionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "helperText" to helperText,
  "rows" to rows.map { row -> row.toMap() },
  "segmentedOptions" to segmentedOptions,
  "segmentedIndex" to segmentedIndex,
  "inlinePanelText" to inlinePanelText,
  "backgroundTone" to backgroundTone.wireValue,
)

internal fun SettingsRowSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "trailingKind" to trailingKind.wireValue,
  "toggleValue" to toggleValue,
  "valueLabel" to valueLabel,
)

internal fun LlmConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "enabled" to enabled,
  "streamingEnabled" to streamingEnabled,
  "providerMode" to providerMode,
  "providerId" to providerId,
  "selectedProviderOptionId" to selectedProviderOptionId,
  "protocol" to protocol,
  "providerOptions" to providerOptions.map { option -> option.toMap() },
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
  "onDeviceModels" to onDeviceModels.map { option -> option.toMap() },
  "selectedOnDeviceModelId" to selectedOnDeviceModelId,
  "onDeviceMaxContextWindow" to onDeviceMaxContextWindow,
  "onDeviceMaxTokens" to onDeviceMaxTokens,
  "onDeviceTopK" to onDeviceTopK,
  "onDeviceTopP" to onDeviceTopP,
  "onDeviceTemperature" to onDeviceTemperature,
  "onDeviceAccelerator" to onDeviceAccelerator,
  "onDeviceThinkingEnabled" to onDeviceThinkingEnabled,
  "onDeviceLiteModeEnabled" to onDeviceLiteModeEnabled,
  "helperText" to helperText,
  "agentCapability" to agentCapability.toMap(),
)

internal fun LlmProviderOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
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

internal fun OnDeviceLlmModelOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "subtitle" to subtitle,
  "sizeLabel" to sizeLabel,
  "fileSizeBytes" to fileSizeBytes,
  "installState" to installState,
  "downloadState" to installState,
  "downloadedBytes" to downloadedBytes,
  "downloadBytesPerSecond" to downloadBytesPerSecond,
  "sha256Verified" to sha256Verified,
  "isSelected" to isSelected,
  "lastError" to lastError,
)

internal fun LlmValidationResult.toMap(): Map<String, Any?> = mapOf(
  "isSuccess" to isSuccess,
  "message" to message,
  "agentCapability" to agentCapability?.toMap(),
)

internal fun LlmAgentCapabilitySnapshot.toMap(): Map<String, Any?> = mapOf(
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

internal fun PersonalizationConfigSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "introTitle" to introTitle,
  "introBody" to introBody,
  "introHelper" to introHelper,
  "presetsTitle" to presetsTitle,
  "presetsHelper" to presetsHelper,
  "presets" to presets.map { preset -> preset.toMap() },
  "selectedPresetId" to selectedPresetId,
  "customOverlayTitle" to customOverlayTitle,
  "customOverlayHelper" to customOverlayHelper,
  "customLabelHint" to customLabelHint,
  "customLabelHelper" to customLabelHelper,
  "customGuidanceHint" to customGuidanceHint,
  "customGuidanceHelper" to customGuidanceHelper,
  "customLabel" to customLabel,
  "customGuidance" to customGuidance,
  "behaviorDefaultsTitle" to behaviorDefaultsTitle,
  "appLanguageTitle" to appLanguageTitle,
  "appLanguageOptions" to appLanguageOptions.map { option -> option.toMap() },
  "selectedAppLanguageId" to selectedAppLanguageId,
  "livePreviewTitle" to livePreviewTitle,
  "livePreviewName" to livePreviewName,
  "livePreviewSummary" to livePreviewSummary,
  "queueTitle" to queueTitle,
  "queueBody" to queueBody,
  "queueIsIdle" to queueIsIdle,
  "lastResetTitle" to lastResetTitle,
  "lastResetMessage" to lastResetMessage,
  "resetActions" to resetActions.map { action -> action.toMap() },
)

internal fun PersonalizationPresetSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "summary" to summary,
  "voice" to voice,
  "status" to status,
  "isSelected" to isSelected,
)

internal fun PersonalizationLanguageOptionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "isSelected" to isSelected,
)

internal fun PersonalizationResetActionSnapshot.toMap(): Map<String, Any?> = mapOf(
  "scopeId" to scope.wireValue,
  "title" to title,
  "scopeBody" to scopeBody,
  "retainBody" to retainBody,
  "confirmationToken" to confirmationToken,
  "inputHint" to inputHint,
  "disabledGuidance" to disabledGuidance,
  "typeExactGuidance" to typeExactGuidance,
  "armedGuidance" to armedGuidance,
  "isInputEnabled" to isInputEnabled,
)

internal fun McpSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "masterTitle" to masterTitle,
  "masterSummary" to masterSummary,
  "masterEnabled" to masterEnabled,
  "summaryLine" to summaryLine,
  "serversTitle" to serversTitle,
  "serversHelper" to serversHelper,
  "masterDisabledTitle" to masterDisabledTitle,
  "masterDisabledBody" to masterDisabledBody,
  "servers" to servers.map { server -> server.toMap() },
)

internal fun McpServerSettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "statusLabel" to statusLabel,
  "statusTone" to statusTone,
  "trustLine" to trustLine,
  "authLine" to authLine,
  "readinessLine" to readinessLine,
  "transportLine" to transportLine,
  "exposureLine" to exposureLine,
  "guidance" to guidance,
  "actionLabel" to actionLabel,
  "actionTurnsOn" to actionTurnsOn,
  "isActionEnabled" to isActionEnabled,
)

internal fun SafetySettingsSnapshot.toMap(): Map<String, Any?> = mapOf(
  "automationModeId" to automationMode.wireValue,
  "rollbackJournalEnabled" to rollbackJournalEnabled,
  "maxFilesPerBatch" to maxFilesPerBatch,
  "maxAgentTurns" to maxAgentTurns,
  "maxToolCalls" to maxToolCalls,
  "undoWindowHours" to undoWindowHours,
  "fileChangesPolicyId" to fileChangesPolicy.wireValue,
  "fileDeletesPolicyId" to fileDeletesPolicy.wireValue,
  "shellCommandsPolicyId" to shellCommandsPolicy.wireValue,
  "externalAccessModeId" to externalAccessMode.wireValue,
  "locations" to locations.map { location -> location.toMap() },
  "workspaceAccessProfileId" to workspaceAccessProfile.wireValue,
  "readOnlyOutsideWorkspace" to readOnlyOutsideWorkspace,
  "liveContextModeId" to liveContextMode.wireValue,
  "memoryToolsEnabled" to memoryToolsEnabled,
)

internal fun SafetySettingsLocationSnapshot.toMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "enabled" to enabled,
)
