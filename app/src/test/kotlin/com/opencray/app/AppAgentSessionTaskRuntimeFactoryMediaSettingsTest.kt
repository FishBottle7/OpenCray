package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAgentSessionTaskRuntimeFactoryMediaSettingsTest {
  @Test
  fun mediaToolSettingsReuseLlmAuthHeadersAndNormalizeVoicePreset() {
    val settings = mediaToolSettingsForTest(
      mediaSettings = MediaSpeechSettingsState(
        imageGeneration = MediaProviderSettings(
          provider = "Images",
          baseUrl = "https://media.example.com",
          endpoint = "/v1/images",
          model = "flux-pro",
        ),
        voiceGeneration = VoiceProviderSettings(
          provider = "Speech",
          baseUrl = "https://media.example.com",
          endpoint = "/v1/audio/speech",
          voicePreset = "alloy · calm",
        ),
        sttRouteId = SpeechToTextRouteId.ON_DEVICE_MODEL.wireValue,
        externalStt = MediaProviderSettings(
          provider = "STT",
          baseUrl = "",
          endpoint = "",
          model = "",
        ),
        onDeviceModel = OnDeviceSttSettings(
          modelPackage = "Whisper Small",
          downloadStatus = "ready",
        ),
      ),
      llmSettings = LlmSettingsState(
        protocol = LlmProviderProtocols.OPENAI,
        apiKey = "test-key",
      ),
    )

    assertEquals("Bearer test-key", settings.imageGeneration?.authHeaders?.get("Authorization"))
    assertEquals("Bearer test-key", settings.speechSynthesis?.authHeaders?.get("Authorization"))
    assertEquals("alloy", settings.speechSynthesis?.defaultVoice)
    assertEquals("tts-1", settings.speechSynthesis?.defaultModel)
  }
}
