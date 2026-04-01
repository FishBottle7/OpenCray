package com.opencray.app

import com.opencray.app.facade.mcp.McpServerSettingsSnapshot
import com.opencray.app.facade.mcp.McpSettingsSnapshot

internal fun McpSettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
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
  "servers" to servers.map { server -> server.toGatewayMap() },
)

private fun McpServerSettingsSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
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
