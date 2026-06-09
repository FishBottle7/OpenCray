package com.opencray.app

import com.opencray.app.facade.media.MediaProviderSnapshot
import com.opencray.app.facade.media.MediaSpeechConfigSnapshot
import com.opencray.app.facade.media.OnDeviceSttSnapshot
import com.opencray.app.facade.media.SaveMediaProviderRequest
import com.opencray.app.facade.media.SaveMediaSpeechConfigRequest
import com.opencray.app.facade.media.SaveOnDeviceSttRequest
import com.opencray.app.facade.media.SaveVoiceProviderRequest
import com.opencray.app.facade.media.VoiceProviderSnapshot

internal fun MediaSpeechConfigSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "localeTag" to localeTag,
  "title" to title,
  "subtitle" to subtitle,
  "imageGeneration" to imageGeneration.toGatewayMap(),
  "videoGeneration" to videoGeneration.toGatewayMap(),
  "voiceGeneration" to voiceGeneration.toGatewayMap(),
  "sttRouteId" to sttRouteId,
  "externalStt" to externalStt.toGatewayMap(),
  "onDeviceModel" to onDeviceModel.toGatewayMap(),
)

internal fun Map<String, Any?>.toSaveMediaSpeechConfigRequest(): SaveMediaSpeechConfigRequest {
  val imageGeneration = this["imageGeneration"] as? Map<String, Any?> ?: emptyMap()
  val videoGeneration = this["videoGeneration"] as? Map<String, Any?> ?: emptyMap()
  val voiceGeneration = this["voiceGeneration"] as? Map<String, Any?> ?: emptyMap()
  val externalStt = this["externalStt"] as? Map<String, Any?> ?: emptyMap()
  val onDeviceModel = this["onDeviceModel"] as? Map<String, Any?> ?: emptyMap()
  return SaveMediaSpeechConfigRequest(
    imageGeneration = imageGeneration.toSaveMediaProviderRequest(),
    videoGeneration = videoGeneration.toSaveMediaProviderRequest(),
    voiceGeneration = voiceGeneration.toSaveVoiceProviderRequest(),
    sttRouteId = this["sttRouteId"]?.toString().orEmpty(),
    externalStt = externalStt.toSaveMediaProviderRequest(),
    onDeviceModel = onDeviceModel.toSaveOnDeviceSttRequest(),
  )
}

private fun MediaProviderSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

private fun VoiceProviderSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "provider" to provider,
  "baseUrl" to baseUrl,
  "endpoint" to endpoint,
  "model" to model,
  "voicePreset" to voicePreset,
  "authProtocol" to authProtocol,
  "apiKey" to apiKey,
)

private fun OnDeviceSttSnapshot.toGatewayMap(): Map<String, Any?> = mapOf(
  "modelPackage" to modelPackage,
  "downloadStatus" to downloadStatus,
)

private fun Map<String, Any?>.toSaveMediaProviderRequest(): SaveMediaProviderRequest =
  SaveMediaProviderRequest(
    provider = this["provider"]?.toString().orEmpty(),
    baseUrl = this["baseUrl"]?.toString().orEmpty(),
    endpoint = this["endpoint"]?.toString().orEmpty(),
    model = this["model"]?.toString().orEmpty(),
    authProtocol = this["authProtocol"]?.toString().orEmpty(),
    apiKey = this["apiKey"]?.toString().orEmpty(),
  )

private fun Map<String, Any?>.toSaveVoiceProviderRequest(): SaveVoiceProviderRequest =
  SaveVoiceProviderRequest(
    provider = this["provider"]?.toString().orEmpty(),
    baseUrl = this["baseUrl"]?.toString().orEmpty(),
    endpoint = this["endpoint"]?.toString().orEmpty(),
    model = this["model"]?.toString().orEmpty(),
    voicePreset = this["voicePreset"]?.toString().orEmpty(),
    authProtocol = this["authProtocol"]?.toString().orEmpty(),
    apiKey = this["apiKey"]?.toString().orEmpty(),
  )

private fun Map<String, Any?>.toSaveOnDeviceSttRequest(): SaveOnDeviceSttRequest =
  SaveOnDeviceSttRequest(
    modelPackage = this["modelPackage"]?.toString().orEmpty(),
    downloadStatus = this["downloadStatus"]?.toString().orEmpty(),
  )
