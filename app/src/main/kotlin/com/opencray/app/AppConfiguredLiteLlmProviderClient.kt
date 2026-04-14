package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
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

  private fun isOnDeviceRequest(request: LiteLlmProviderRequest): Boolean {
    val explicitProviderMode = request.route.metadata[LiteRtOnDeviceMetadataKeys.PROVIDER_MODE]
      ?.trim()
      ?.lowercase()
    if (explicitProviderMode == LlmProviderModes.ON_DEVICE_MODEL) {
      return true
    }
    val runtimeId = request.route.metadata[LiteRtOnDeviceMetadataKeys.RUNTIME]
      ?.trim()
      ?.lowercase()
    if (runtimeId == OnDeviceLlmCatalog.RUNTIME_ID_LITERT_LM) {
      return true
    }
    return request.route.baseUrl.isNullOrBlank() && OnDeviceLlmCatalog.hasModel(request.route.model)
  }
}
