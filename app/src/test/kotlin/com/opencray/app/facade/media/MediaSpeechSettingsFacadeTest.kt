package com.opencray.app.facade.media

import com.opencray.app.InMemoryMediaSpeechSettingsKeyValueStore
import com.opencray.app.MediaSpeechSettingsStore
import com.opencray.app.ProviderAuthProtocols
import com.opencray.app.toGatewayMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSpeechSettingsFacadeTest {
  private companion object {
    const val PERSISTED_IMAGE_KEY = "image-secret-key-1234"
    const val MASKED_IMAGE_KEY = "••••1234"
  }

  private fun facade(): MediaSpeechSettingsFacade =
    LocalMediaSpeechSettingsFacade.create(
      store = MediaSpeechSettingsStore(InMemoryMediaSpeechSettingsKeyValueStore()),
    )

  private fun saveRequest(
    imageApiKey: String?,
    videoApiKey: String? = "video-secret-key-5678",
    voiceApiKey: String? = "voice-secret-key-9012",
    externalSttApiKey: String? = "stt-secret-key-3456",
  ): SaveMediaSpeechConfigRequest = SaveMediaSpeechConfigRequest(
    imageGeneration = SaveMediaProviderRequest(
      provider = "openrouter",
      baseUrl = "https://image.example.com",
      endpoint = "/images",
      model = "gpt-image-1",
      authProtocol = ProviderAuthProtocols.BEARER,
      apiKey = imageApiKey,
    ),
    videoGeneration = SaveMediaProviderRequest(
      provider = "runway",
      baseUrl = "https://video.example.com",
      endpoint = "/videos",
      model = "gen4",
      authProtocol = ProviderAuthProtocols.BEARER,
      apiKey = videoApiKey,
    ),
    voiceGeneration = SaveVoiceProviderRequest(
      provider = "elevenlabs",
      baseUrl = "https://voice.example.com",
      endpoint = "/speech",
      model = "tts-omni",
      voicePreset = "alloy",
      authProtocol = ProviderAuthProtocols.BEARER,
      apiKey = voiceApiKey,
    ),
    sttRouteId = "external_api",
    externalStt = SaveMediaProviderRequest(
      provider = "deepgram",
      baseUrl = "https://stt.example.com",
      endpoint = "/listen",
      model = "nova-3",
      authProtocol = ProviderAuthProtocols.BEARER,
      apiKey = externalSttApiKey,
    ),
    onDeviceModel = SaveOnDeviceSttRequest(
      modelPackage = "",
      downloadStatus = "",
    ),
  )

  @Test
  fun loadGatewayMapDoesNotExposePlaintextApiKeys() {
    val facade = facade()
    facade.save(saveRequest(imageApiKey = PERSISTED_IMAGE_KEY))

    val payload = facade.load().toGatewayMap()

    val image = payload["imageGeneration"] as Map<*, *>
    val video = payload["videoGeneration"] as Map<*, *>
    val voice = payload["voiceGeneration"] as Map<*, *>
    val externalStt = payload["externalStt"] as Map<*, *>

    assertEquals(MASKED_IMAGE_KEY, image["apiKey"])
    assertEquals(true, image["hasCredential"])
    assertEquals("1234", image["credentialHint"])
    assertEquals("••••5678", video["apiKey"])
    assertEquals(true, video["hasCredential"])
    assertEquals("5678", video["credentialHint"])
    assertEquals("••••9012", voice["apiKey"])
    assertEquals(true, voice["hasCredential"])
    assertEquals("9012", voice["credentialHint"])
    assertEquals("••••3456", externalStt["apiKey"])
    assertEquals(true, externalStt["hasCredential"])
    assertEquals("3456", externalStt["credentialHint"])
    assertFalse(payload.toString().contains(PERSISTED_IMAGE_KEY))
    assertFalse(payload.toString().contains("video-secret-key-5678"))
    assertFalse(payload.toString().contains("voice-secret-key-9012"))
    assertFalse(payload.toString().contains("stt-secret-key-3456"))
  }

  @Test
  fun loadGatewayMapReportsMissingCredentialWithoutHint() {
    val facade = facade()
    facade.save(saveRequest(imageApiKey = ""))

    val payload = facade.load().toGatewayMap()
    val image = payload["imageGeneration"] as Map<*, *>

    assertEquals("", image["apiKey"])
    assertEquals(false, image["hasCredential"])
    assertEquals("", image["credentialHint"])
  }

  @Test
  fun saveWithEchoedMaskedApiKeysPreservesStoredCredentials() {
    val facade = facade()
    facade.save(saveRequest(imageApiKey = PERSISTED_IMAGE_KEY))

    val saved = facade.save(
      saveRequest(
        imageApiKey = MASKED_IMAGE_KEY,
        videoApiKey = "••••5678",
        voiceApiKey = "••••9012",
        externalSttApiKey = "••••3456",
      ),
    )

    assertEquals(PERSISTED_IMAGE_KEY, saved.imageGeneration.apiKey)
    assertEquals("video-secret-key-5678", saved.videoGeneration.apiKey)
    assertEquals("voice-secret-key-9012", saved.voiceGeneration.apiKey)
    assertEquals("stt-secret-key-3456", saved.externalStt.apiKey)
  }

  @Test
  fun saveWithoutApiKeyFieldsPreservesStoredCredentials() {
    val facade = facade()
    facade.save(saveRequest(imageApiKey = PERSISTED_IMAGE_KEY))

    val saved = facade.save(
      saveRequest(
        imageApiKey = null,
        videoApiKey = null,
        voiceApiKey = null,
        externalSttApiKey = null,
      ),
    )

    assertEquals(PERSISTED_IMAGE_KEY, saved.imageGeneration.apiKey)
    assertEquals("video-secret-key-5678", saved.videoGeneration.apiKey)
    assertEquals("voice-secret-key-9012", saved.voiceGeneration.apiKey)
    assertEquals("stt-secret-key-3456", saved.externalStt.apiKey)
  }

  @Test
  fun saveWithExplicitEmptyApiKeysClearsStoredCredentials() {
    val facade = facade()
    facade.save(saveRequest(imageApiKey = PERSISTED_IMAGE_KEY))

    val saved = facade.save(
      saveRequest(
        imageApiKey = "",
        videoApiKey = "",
        voiceApiKey = "",
        externalSttApiKey = "",
      ),
    )

    assertEquals("", saved.imageGeneration.apiKey)
    assertEquals("", saved.videoGeneration.apiKey)
    assertEquals("", saved.voiceGeneration.apiKey)
    assertEquals("", saved.externalStt.apiKey)
  }
}
