package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAgentSessionTaskRuntimeFactoryMediaSettingsTest {
  @Test
  fun mediaToolSettingsUseProviderScopedAuthAndExposeVideoAndSpeechModel() {
    val settings = mediaToolSettingsForTest(
      mediaSettings = MediaSpeechSettingsState(
        imageGeneration = MediaProviderSettings(
          provider = "Images",
          baseUrl = "https://media.example.com",
          endpoint = "/v1/images",
          model = "flux-pro",
          authProtocol = ProviderAuthProtocols.BEARER,
          apiKey = "image-key",
        ),
        videoGeneration = MediaProviderSettings(
          provider = "Videos",
          baseUrl = "https://video.example.com",
          endpoint = "/v1/videos",
          model = "gen4",
          authProtocol = ProviderAuthProtocols.BEARER,
          apiKey = "video-key",
        ),
        voiceGeneration = VoiceProviderSettings(
          provider = "Speech",
          baseUrl = "https://media.example.com",
          endpoint = "/v1/audio/speech",
          model = "tts-omni",
          voicePreset = "alloy · calm",
          authProtocol = ProviderAuthProtocols.BEARER,
          apiKey = "speech-key",
        ),
        sttRouteId = SpeechToTextRouteId.ON_DEVICE_MODEL.wireValue,
        externalStt = MediaProviderSettings(
          provider = "STT",
          baseUrl = "",
          endpoint = "",
          model = "",
          authProtocol = ProviderAuthProtocols.BEARER,
        ),
        onDeviceModel = OnDeviceSttSettings(
          modelPackage = "Whisper Small",
          downloadStatus = "ready",
        ),
      ),
      llmSettings = LlmSettingsState(
        protocol = LlmProviderProtocols.ANTHROPIC,
        apiKey = "llm-key",
      ),
    )

    assertEquals("Bearer image-key", settings.imageGeneration?.authHeaders?.get("Authorization"))
    assertEquals("Bearer video-key", settings.videoGeneration?.authHeaders?.get("Authorization"))
    assertEquals("Bearer speech-key", settings.speechSynthesis?.authHeaders?.get("Authorization"))
    assertEquals("gen4", settings.videoGeneration?.model)
    assertEquals("alloy", settings.speechSynthesis?.defaultVoice)
    assertEquals("tts-omni", settings.speechSynthesis?.defaultModel)
  }
}
