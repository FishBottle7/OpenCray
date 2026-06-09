package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmCompactResult
import com.opencray.llm.LiteLlmProviderCompactRequest
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult

internal class AppConfiguredLiteLlmProviderClient(
  private val cloudProviderClient: LiteLlmProviderClient,
  private val onDeviceProviderClient: LiteLlmProviderClient,
) : LiteLlmProviderClient {
  override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult =
    if (isOnDeviceRequest(request)) {
      onDeviceProviderClient.execute(request)
    } else {
      cloudProviderClient.execute(request)
    }

  override fun compactConversation(request: LiteLlmProviderCompactRequest): LiteLlmCompactResult =
    if (isOnDeviceRoute(request.route.baseUrl, request.route.model, request.route.metadata)) {
      onDeviceProviderClient.compactConversation(request)
    } else {
      cloudProviderClient.compactConversation(request)
    }

  private fun isOnDeviceRequest(request: LiteLlmProviderRequest): Boolean {
    return isOnDeviceRoute(
      baseUrl = request.route.baseUrl,
      model = request.route.model,
      metadata = request.route.metadata,
    )
  }

  private fun isOnDeviceRoute(
    baseUrl: String?,
    model: String,
    metadata: Map<String, String>,
  ): Boolean {
    val explicitProviderMode = metadata[LiteRtOnDeviceMetadataKeys.PROVIDER_MODE]
      ?.trim()
      ?.lowercase()
    if (explicitProviderMode == LlmProviderModes.ON_DEVICE_MODEL) {
      return true
    }
    val runtimeId = metadata[LiteRtOnDeviceMetadataKeys.RUNTIME]
      ?.trim()
      ?.lowercase()
    if (runtimeId == OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM) {
      return true
    }
    return baseUrl.isNullOrBlank() && OnDeviceLlmCatalog.hasModel(model)
  }
}
