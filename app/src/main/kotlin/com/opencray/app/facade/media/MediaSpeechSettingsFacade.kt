package com.opencray.app.facade.media

import android.content.Context
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.MediaProviderSettings
import com.opencray.app.MediaSpeechSettingsState
import com.opencray.app.MediaSpeechSettingsStore
import com.opencray.app.OnDeviceSttSettings
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.ProviderAuthProtocols
import com.opencray.app.VoiceProviderSettings
import org.opencray.app.R

data class MediaProviderSnapshot(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
)

data class VoiceProviderSnapshot(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String = VoiceProviderSettings.DEFAULT_MODEL,
  val voicePreset: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
)

data class OnDeviceSttSnapshot(
  val modelPackage: String,
  val downloadStatus: String,
)

data class MediaSpeechConfigSnapshot(
  val localeTag: String,
  val title: String,
  val subtitle: String,
  val imageGeneration: MediaProviderSnapshot,
  val videoGeneration: MediaProviderSnapshot = MediaProviderSnapshot(
    provider = "",
    baseUrl = "",
    endpoint = "",
    model = "",
  ),
  val voiceGeneration: VoiceProviderSnapshot,
  val sttRouteId: String,
  val externalStt: MediaProviderSnapshot,
  val onDeviceModel: OnDeviceSttSnapshot,
)

data class SaveMediaProviderRequest(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
)

data class SaveVoiceProviderRequest(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String = VoiceProviderSettings.DEFAULT_MODEL,
  val voicePreset: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
)

data class SaveOnDeviceSttRequest(
  val modelPackage: String,
  val downloadStatus: String,
)

data class SaveMediaSpeechConfigRequest(
  val imageGeneration: SaveMediaProviderRequest,
  val videoGeneration: SaveMediaProviderRequest = SaveMediaProviderRequest(
    provider = "",
    baseUrl = "",
    endpoint = "",
    model = "",
  ),
  val voiceGeneration: SaveVoiceProviderRequest,
  val sttRouteId: String,
  val externalStt: SaveMediaProviderRequest,
  val onDeviceModel: SaveOnDeviceSttRequest,
)

interface MediaSpeechSettingsFacade {
  fun load(): MediaSpeechConfigSnapshot

  fun save(request: SaveMediaSpeechConfigRequest): MediaSpeechConfigSnapshot
}

internal class LocalMediaSpeechSettingsFacade private constructor(
  private val store: MediaSpeechSettingsStore,
  private val strings: MediaSpeechSettingsStrings,
) : MediaSpeechSettingsFacade {
  override fun load(): MediaSpeechConfigSnapshot = snapshotFor(store.load())

  override fun save(request: SaveMediaSpeechConfigRequest): MediaSpeechConfigSnapshot {
    store.save(
      MediaSpeechSettingsState(
        imageGeneration = MediaProviderSettings(
          provider = request.imageGeneration.provider,
          baseUrl = request.imageGeneration.baseUrl,
          endpoint = request.imageGeneration.endpoint,
          model = request.imageGeneration.model,
          authProtocol = request.imageGeneration.authProtocol,
          apiKey = request.imageGeneration.apiKey,
        ),
        videoGeneration = MediaProviderSettings(
          provider = request.videoGeneration.provider,
          baseUrl = request.videoGeneration.baseUrl,
          endpoint = request.videoGeneration.endpoint,
          model = request.videoGeneration.model,
          authProtocol = request.videoGeneration.authProtocol,
          apiKey = request.videoGeneration.apiKey,
        ),
        voiceGeneration = VoiceProviderSettings(
          provider = request.voiceGeneration.provider,
          baseUrl = request.voiceGeneration.baseUrl,
          endpoint = request.voiceGeneration.endpoint,
          model = request.voiceGeneration.model,
          voicePreset = request.voiceGeneration.voicePreset,
          authProtocol = request.voiceGeneration.authProtocol,
          apiKey = request.voiceGeneration.apiKey,
        ),
        sttRouteId = request.sttRouteId,
        externalStt = MediaProviderSettings(
          provider = request.externalStt.provider,
          baseUrl = request.externalStt.baseUrl,
          endpoint = request.externalStt.endpoint,
          model = request.externalStt.model,
          authProtocol = request.externalStt.authProtocol,
          apiKey = request.externalStt.apiKey,
        ),
        onDeviceModel = OnDeviceSttSettings(
          modelPackage = request.onDeviceModel.modelPackage,
          downloadStatus = request.onDeviceModel.downloadStatus,
        ),
      ),
    )
    return load()
  }

  private fun snapshotFor(state: MediaSpeechSettingsState): MediaSpeechConfigSnapshot =
    MediaSpeechConfigSnapshot(
      localeTag = strings.localeTag,
      title = strings.title,
      subtitle = strings.subtitle,
      imageGeneration = MediaProviderSnapshot(
        provider = state.imageGeneration.provider,
        baseUrl = state.imageGeneration.baseUrl,
        endpoint = state.imageGeneration.endpoint,
        model = state.imageGeneration.model,
        authProtocol = state.imageGeneration.authProtocol,
        apiKey = state.imageGeneration.apiKey,
      ),
      videoGeneration = MediaProviderSnapshot(
        provider = state.videoGeneration.provider,
        baseUrl = state.videoGeneration.baseUrl,
        endpoint = state.videoGeneration.endpoint,
        model = state.videoGeneration.model,
        authProtocol = state.videoGeneration.authProtocol,
        apiKey = state.videoGeneration.apiKey,
      ),
      voiceGeneration = VoiceProviderSnapshot(
        provider = state.voiceGeneration.provider,
        baseUrl = state.voiceGeneration.baseUrl,
        endpoint = state.voiceGeneration.endpoint,
        model = state.voiceGeneration.model,
        voicePreset = state.voiceGeneration.voicePreset,
        authProtocol = state.voiceGeneration.authProtocol,
        apiKey = state.voiceGeneration.apiKey,
      ),
      sttRouteId = state.sttRouteId,
      externalStt = MediaProviderSnapshot(
        provider = state.externalStt.provider,
        baseUrl = state.externalStt.baseUrl,
        endpoint = state.externalStt.endpoint,
        model = state.externalStt.model,
        authProtocol = state.externalStt.authProtocol,
        apiKey = state.externalStt.apiKey,
      ),
      onDeviceModel = OnDeviceSttSnapshot(
        modelPackage = state.onDeviceModel.modelPackage,
        downloadStatus = state.onDeviceModel.downloadStatus,
      ),
    )

  companion object {
    fun fromContext(context: Context): MediaSpeechSettingsFacade {
      val localizedContext = OpenCrayLocaleManager.wrap(context.applicationContext)
      val localeTag = LocaleSettingsStore.fromContext(context.applicationContext)
        .loadLanguage()
        .tag
      return LocalMediaSpeechSettingsFacade(
        store = MediaSpeechSettingsStore.fromContext(context.applicationContext),
        strings = MediaSpeechSettingsStrings(
          localeTag = localeTag,
          title = localizedContext.getString(R.string.settings_media_speech_title),
          subtitle = localizedContext.getString(R.string.settings_media_speech_subtitle),
        ),
      )
    }

    internal fun createForTest(
      store: MediaSpeechSettingsStore,
    ): MediaSpeechSettingsFacade = LocalMediaSpeechSettingsFacade(
      store = store,
      strings = MediaSpeechSettingsStrings(
        localeTag = "en",
        title = "Media & Speech",
        subtitle = "Configure media APIs and STT routing.",
      ),
    )
  }
}

internal data class MediaSpeechSettingsStrings(
  val localeTag: String,
  val title: String,
  val subtitle: String,
)

internal object EmptyMediaSpeechSettingsFacade : MediaSpeechSettingsFacade {
  override fun load(): MediaSpeechConfigSnapshot {
    val defaults = MediaSpeechSettingsState.defaults()
    return MediaSpeechConfigSnapshot(
      localeTag = "en",
      title = "Media & Speech",
      subtitle = "Host support is unavailable.",
      imageGeneration = MediaProviderSnapshot(
        provider = defaults.imageGeneration.provider,
        baseUrl = defaults.imageGeneration.baseUrl,
        endpoint = defaults.imageGeneration.endpoint,
        model = defaults.imageGeneration.model,
        authProtocol = defaults.imageGeneration.authProtocol,
        apiKey = defaults.imageGeneration.apiKey,
      ),
      videoGeneration = MediaProviderSnapshot(
        provider = defaults.videoGeneration.provider,
        baseUrl = defaults.videoGeneration.baseUrl,
        endpoint = defaults.videoGeneration.endpoint,
        model = defaults.videoGeneration.model,
        authProtocol = defaults.videoGeneration.authProtocol,
        apiKey = defaults.videoGeneration.apiKey,
      ),
      voiceGeneration = VoiceProviderSnapshot(
        provider = defaults.voiceGeneration.provider,
        baseUrl = defaults.voiceGeneration.baseUrl,
        endpoint = defaults.voiceGeneration.endpoint,
        model = defaults.voiceGeneration.model,
        voicePreset = defaults.voiceGeneration.voicePreset,
        authProtocol = defaults.voiceGeneration.authProtocol,
        apiKey = defaults.voiceGeneration.apiKey,
      ),
      sttRouteId = defaults.sttRouteId,
      externalStt = MediaProviderSnapshot(
        provider = defaults.externalStt.provider,
        baseUrl = defaults.externalStt.baseUrl,
        endpoint = defaults.externalStt.endpoint,
        model = defaults.externalStt.model,
        authProtocol = defaults.externalStt.authProtocol,
        apiKey = defaults.externalStt.apiKey,
      ),
      onDeviceModel = OnDeviceSttSnapshot(
        modelPackage = defaults.onDeviceModel.modelPackage,
        downloadStatus = defaults.onDeviceModel.downloadStatus,
      ),
    )
  }

  override fun save(request: SaveMediaSpeechConfigRequest): MediaSpeechConfigSnapshot =
    throw IllegalStateException("Media and speech settings host support is unavailable.")
}
