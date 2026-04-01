package com.opencray.app

import com.opencray.app.facade.personalization.PersonalizationConfigSnapshot
import com.opencray.app.facade.personalization.PersonalizationLanguageOptionSnapshot
import com.opencray.app.facade.personalization.PersonalizationPresetSnapshot
import com.opencray.app.facade.personalization.PersonalizationResetActionSnapshot

internal fun PersonalizationConfigSnapshot.toPersonalizationGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "introTitle" to introTitle,
  "introBody" to introBody,
  "introHelper" to introHelper,
  "presetsTitle" to presetsTitle,
  "presetsHelper" to presetsHelper,
  "presets" to presets.map { preset -> preset.toPersonalizationGatewayMap() },
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
  "appLanguageOptions" to appLanguageOptions.map { option -> option.toPersonalizationGatewayMap() },
  "selectedAppLanguageId" to selectedAppLanguageId,
  "livePreviewTitle" to livePreviewTitle,
  "livePreviewName" to livePreviewName,
  "livePreviewSummary" to livePreviewSummary,
  "queueTitle" to queueTitle,
  "queueBody" to queueBody,
  "queueIsIdle" to queueIsIdle,
  "lastResetTitle" to lastResetTitle,
  "lastResetMessage" to lastResetMessage,
  "resetActions" to resetActions.map { action -> action.toPersonalizationGatewayMap() },
)

private fun PersonalizationPresetSnapshot.toPersonalizationGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "summary" to summary,
  "voice" to voice,
  "status" to status,
  "isSelected" to isSelected,
)

private fun PersonalizationLanguageOptionSnapshot.toPersonalizationGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "title" to title,
  "isSelected" to isSelected,
)

private fun PersonalizationResetActionSnapshot.toPersonalizationGatewayMap(): Map<String, Any?> = mapOf(
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
