package com.opencray.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSpeechSettingsStoreTest {
  @Test
  fun defaultsIncludeVideoProviderAndStableVoiceModel() {
    val state = MediaSpeechSettingsStore(
      InMemoryMediaSpeechSettingsKeyValueStore(),
    ).load()

    assertEquals("Runway", state.videoGeneration.provider)
    assertEquals("gen4_turbo", state.videoGeneration.model)
    assertEquals(ProviderAuthProtocols.BEARER, state.videoGeneration.authProtocol)
    assertEquals("tts-1", state.voiceGeneration.model)
    assertEquals(ProviderAuthProtocols.BEARER, state.voiceGeneration.authProtocol)
  }

  @Test
  fun saveRoundTripsProviderAuthAndVideoSettings() {
    val store = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore())

    store.save(
      MediaSpeechSettingsState(
        imageGeneration = MediaProviderSettings(
          provider = "Images",
          baseUrl = "https://images.example.com",
          endpoint = "/v1/images",
          model = "flux-pro",
          authProtocol = ProviderAuthProtocols.BEARER,
          apiKey = "image-key",
        ),
        videoGeneration = MediaProviderSettings(
          provider = "Videos",
          baseUrl = "https://videos.example.com",
          endpoint = "/v1/videos",
          model = "gen4",
          authProtocol = ProviderAuthProtocols.ANTHROPIC,
          apiKey = "video-key",
        ),
        voiceGeneration = VoiceProviderSettings(
          provider = "Speech",
          baseUrl = "https://speech.example.com",
          endpoint = "/v1/audio/speech",
          model = "tts-omni",
          voicePreset = "nova · bright",
          authProtocol = ProviderAuthProtocols.NONE,
          apiKey = "",
        ),
        sttRouteId = SpeechToTextRouteId.EXTERNAL_API.wireValue,
        externalStt = MediaProviderSettings(
          provider = "STT",
          baseUrl = "https://stt.example.com",
          endpoint = "/v1/transcribe",
          model = "whisper-large-v3",
          authProtocol = ProviderAuthProtocols.BEARER,
          apiKey = "stt-key",
        ),
        onDeviceModel = OnDeviceSttSettings(
          modelPackage = "Whisper Small",
          downloadStatus = "ready",
        ),
      ),
    )

    val reloaded = store.load()

    assertEquals("video-key", reloaded.videoGeneration.apiKey)
    assertEquals(ProviderAuthProtocols.ANTHROPIC, reloaded.videoGeneration.authProtocol)
    assertEquals("tts-omni", reloaded.voiceGeneration.model)
    assertEquals(ProviderAuthProtocols.NONE, reloaded.voiceGeneration.authProtocol)
    assertEquals("stt-key", reloaded.externalStt.apiKey)
  }

  @Test
  fun loadLegacyPayloadWithoutVideoGenerationUsesFreshDefaults() {
    val keyValueStore = InMemoryMediaSpeechSettingsKeyValueStore()
    keyValueStore.putString(
      MediaSpeechSettingsStoreKeys.STATE,
      JSONObject()
        .put(
          "imageGeneration",
          JSONObject()
            .put("provider", "Fal AI")
            .put("baseUrl", "https://api.fal.ai")
            .put("endpoint", "/v1/images")
            .put("model", "flux-pro"),
        )
        .put(
          "voiceGeneration",
          JSONObject()
            .put("provider", "OpenAI TTS")
            .put("baseUrl", "https://api.openai.com")
            .put("endpoint", "/v1/audio/speech")
            .put("voicePreset", "alloy · calm"),
        )
        .put("sttRouteId", SpeechToTextRouteId.ON_DEVICE_MODEL.wireValue)
        .toString(),
    )

    val state = MediaSpeechSettingsStore(keyValueStore).load()

    assertEquals("Runway", state.videoGeneration.provider)
    assertEquals("https://api.runwayml.com", state.videoGeneration.baseUrl)
    assertEquals("/v1/videos", state.videoGeneration.endpoint)
    assertEquals("gen4_turbo", state.videoGeneration.model)
  }
}
