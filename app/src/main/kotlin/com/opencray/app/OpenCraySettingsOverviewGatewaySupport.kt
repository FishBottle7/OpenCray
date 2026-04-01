package com.opencray.app

import com.opencray.app.facade.settings.SettingsDetailSnapshot
import com.opencray.app.facade.settings.SettingsOverviewSnapshot
import com.opencray.app.facade.settings.SettingsRowSnapshot
import com.opencray.app.facade.settings.SettingsSectionSnapshot

internal fun SettingsOverviewSnapshot.toSettingsOverviewGatewayMap(): Map<String, Any?> = mapOf(
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

internal fun SettingsDetailSnapshot.toSettingsDetailGatewayMap(): Map<String, Any?> = mapOf(
  "routeId" to routeId.wireValue,
  "title" to title,
  "subtitle" to subtitle,
  "sections" to sections.map { section -> section.toSettingsDetailGatewayMap() },
)

private fun SettingsSectionSnapshot.toSettingsDetailGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "helperText" to helperText,
  "rows" to rows.map { row -> row.toSettingsDetailGatewayMap() },
  "segmentedOptions" to segmentedOptions,
  "segmentedIndex" to segmentedIndex,
  "inlinePanelText" to inlinePanelText,
  "backgroundTone" to backgroundTone.wireValue,
)

private fun SettingsRowSnapshot.toSettingsDetailGatewayMap(): Map<String, Any?> = mapOf(
  "title" to title,
  "subtitle" to subtitle,
  "trailingKind" to trailingKind.wireValue,
  "toggleValue" to toggleValue,
  "valueLabel" to valueLabel,
)
