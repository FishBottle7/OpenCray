package com.opencray.app.facade.media

import android.content.Context
import com.opencray.app.LocaleSettingsStore
import com.opencray.app.MediaProviderSettings
import com.opencray.app.MediaSpeechSettingsState
import com.opencray.app.MediaSpeechSettingsStore
import com.opencray.app.OnDeviceSttSettings
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.app.VoiceProviderSettings
import org.opencray.app.R

data class MediaProviderSnapshot(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
)

data class VoiceProviderSnapshot(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val voicePreset: String,
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
)

data class SaveVoiceProviderRequest(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val voicePreset: String,
)

data class SaveOnDeviceSttRequest(
  val modelPackage: String,
  val downloadStatus: String,
)

data class SaveMediaSpeechConfigRequest(
  val imageGeneration: SaveMediaProviderRequest,
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
        ),
        voiceGeneration = VoiceProviderSettings(
          provider = request.voiceGeneration.provider,
          baseUrl = request.voiceGeneration.baseUrl,
          endpoint = request.voiceGeneration.endpoint,
          voicePreset = request.voiceGeneration.voicePreset,
        ),
        sttRouteId = request.sttRouteId,
        externalStt = MediaProviderSettings(
          provider = request.externalStt.provider,
          baseUrl = request.externalStt.baseUrl,
          endpoint = request.externalStt.endpoint,
          model = request.externalStt.model,
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
      ),
      voiceGeneration = VoiceProviderSnapshot(
        provider = state.voiceGeneration.provider,
        baseUrl = state.voiceGeneration.baseUrl,
        endpoint = state.voiceGeneration.endpoint,
        voicePreset = state.voiceGeneration.voicePreset,
      ),
      sttRouteId = state.sttRouteId,
      externalStt = MediaProviderSnapshot(
        provider = state.externalStt.provider,
        baseUrl = state.externalStt.baseUrl,
        endpoint = state.externalStt.endpoint,
        model = state.externalStt.model,
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
      ),
      voiceGeneration = VoiceProviderSnapshot(
        provider = defaults.voiceGeneration.provider,
        baseUrl = defaults.voiceGeneration.baseUrl,
        endpoint = defaults.voiceGeneration.endpoint,
        voicePreset = defaults.voiceGeneration.voicePreset,
      ),
      sttRouteId = defaults.sttRouteId,
      externalStt = MediaProviderSnapshot(
        provider = defaults.externalStt.provider,
        baseUrl = defaults.externalStt.baseUrl,
        endpoint = defaults.externalStt.endpoint,
        model = defaults.externalStt.model,
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
