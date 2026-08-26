package com.opencray.app

import com.opencray.app.facade.search.NetworkSearchConfigSnapshot
import com.opencray.app.facade.search.NetworkSearchSlotSnapshot
import com.opencray.app.facade.search.SaveNetworkSearchConfigRequest
import com.opencray.app.facade.search.SaveNetworkSearchSlotRequest

internal fun NetworkSearchConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "slots" to slots.map { slot -> slot.toGatewayMap() },
)

internal fun List<Map<String, Any?>>.toSaveNetworkSearchConfigRequest(): SaveNetworkSearchConfigRequest =
  SaveNetworkSearchConfigRequest(
    slots = map { slot ->
      SaveNetworkSearchSlotRequest(
        id = slot["id"]?.toString().orEmpty(),
        providerId = slot["providerId"]?.toString().orEmpty(),
        label = slot["label"]?.toString().orEmpty(),
        baseUrl = slot["baseUrl"]?.toString().orEmpty(),
        model = slot["model"]?.toString().orEmpty(),
        apiKey = slot["apiKey"]?.toString(),
        enabled = slot["enabled"] as? Boolean ?: true,
      )
    },
  )

private fun NetworkSearchSlotSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "id" to id,
  "providerId" to providerId,
  "label" to label,
  "baseUrl" to baseUrl,
  "model" to model,
  "apiKey" to maskCredential(apiKey),
  "hasCredential" to credentialHasValue(apiKey),
  "credentialHint" to credentialHint(apiKey),
  "enabled" to enabled,
)
