package com.opencray.app

import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaSpeechSettingsStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

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
  fun fileBackedStoreSharesStateAcrossInstances() {
    val directory = temporaryFolder.newFolder("media-speech-settings-file-backed")
    val firstStore = MediaSpeechSettingsStore(
      FileBackedMediaSpeechSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 100L },
      ),
    )
    val saved = MediaSpeechSettingsState.defaults().copy(
      imageGeneration = MediaProviderSettings(
        provider = "Images",
        baseUrl = "https://images.example.com",
        endpoint = "/v1/images",
        model = "flux-pro",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "image-key",
      ),
      voiceGeneration = VoiceProviderSettings(
        provider = "Speech",
        baseUrl = "https://speech.example.com",
        endpoint = "/v1/audio/speech",
        model = "tts-omni",
        voicePreset = "nova",
        authProtocol = ProviderAuthProtocols.NONE,
        apiKey = "",
      ),
      sttRouteId = SpeechToTextRouteId.EXTERNAL_API.wireValue,
    )

    firstStore.save(saved)

    val secondStore = MediaSpeechSettingsStore(
      FileBackedMediaSpeechSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(directory),
        clock = { 200L },
      ),
    )
    assertEquals(saved.sanitized(), secondStore.load())

    secondStore.clear()

    assertEquals(MediaSpeechSettingsState.defaults(), firstStore.load())
  }

  @Test
  fun fileBackedStoreMigratesLegacyStateOnlyWhenEmpty() {
    val directory = temporaryFolder.newFolder("media-speech-settings-migration")
    val legacyKeyValueStore = InMemoryMediaSpeechSettingsKeyValueStore()
    val legacyStore = MediaSpeechSettingsStore(legacyKeyValueStore)
    val legacyState = MediaSpeechSettingsState.defaults().copy(
      videoGeneration = MediaProviderSettings(
        provider = "Legacy Video",
        baseUrl = "https://legacy-video.example.com",
        endpoint = "/v1/videos",
        model = "legacy-video-model",
        authProtocol = ProviderAuthProtocols.BEARER,
        apiKey = "legacy-video-key",
      ),
    )
    legacyStore.save(legacyState)
    val fileBackedKeyValueStore = FileBackedMediaSpeechSettingsKeyValueStore(
      storage = DirectoryDurableTextStorage(directory),
      clock = { 300L },
    )

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    val fileBackedStore = MediaSpeechSettingsStore(fileBackedKeyValueStore)
    assertEquals(legacyState.sanitized(), fileBackedStore.load())

    val durableState = MediaSpeechSettingsState.defaults().copy(
      videoGeneration = MediaProviderSettings(
        provider = "Durable Video",
        baseUrl = "https://durable-video.example.com",
        endpoint = "/v1/videos",
        model = "durable-video-model",
        authProtocol = ProviderAuthProtocols.ANTHROPIC,
        apiKey = "durable-video-key",
      ),
    )
    fileBackedStore.save(durableState)
    legacyStore.save(legacyState.copy(sttRouteId = SpeechToTextRouteId.EXTERNAL_API.wireValue))

    fileBackedKeyValueStore.migrateFromLegacyIfEmpty(legacyKeyValueStore)

    assertEquals(durableState.sanitized(), fileBackedStore.load())
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
