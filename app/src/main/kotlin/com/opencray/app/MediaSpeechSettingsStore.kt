package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable
import org.json.JSONObject

private const val DEFAULT_MEDIA_SPEECH_SETTINGS_PREFERENCES =
  "opencray.media-speech-settings"
private const val MEDIA_SPEECH_SETTINGS_FILE_NAME = "media-speech-settings.json"

internal object MediaSpeechSettingsStoreKeys {
  const val STATE = "state"
}

internal enum class SpeechToTextRouteId(
  val wireValue: String,
) {
  EXTERNAL_API("external_api"),
  ON_DEVICE_MODEL("on_device_model"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SpeechToTextRouteId? =
      entries.firstOrNull { routeId ->
        routeId.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal data class MediaProviderSettings(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
) {
  fun sanitized(
    defaults: MediaProviderSettings,
  ): MediaProviderSettings = MediaProviderSettings(
    provider = provider.trim().ifBlank { defaults.provider },
    baseUrl = baseUrl.trim(),
    endpoint = endpoint.trim(),
    model = model.trim(),
    authProtocol = ProviderAuthProtocols.normalize(authProtocol.ifBlank { defaults.authProtocol }),
    apiKey = apiKey.trim(),
  )

  fun toJson(): JSONObject = JSONObject()
    .put("provider", provider)
    .put("baseUrl", baseUrl)
    .put("endpoint", endpoint)
    .put("model", model)
    .put("authProtocol", authProtocol)
    .put("apiKey", apiKey)

  companion object {
    fun fromJson(
      payload: JSONObject?,
      defaults: MediaProviderSettings,
    ): MediaProviderSettings = payload?.let {
      MediaProviderSettings(
        provider = it.optString("provider"),
        baseUrl = it.optString("baseUrl"),
        endpoint = it.optString("endpoint"),
        model = it.optString("model"),
        authProtocol = it.optString("authProtocol"),
        apiKey = it.optString("apiKey"),
      ).sanitized(defaults)
    } ?: defaults
  }
}

internal data class VoiceProviderSettings(
  val provider: String,
  val baseUrl: String,
  val endpoint: String,
  val model: String = DEFAULT_MODEL,
  val voicePreset: String,
  val authProtocol: String = ProviderAuthProtocols.BEARER,
  val apiKey: String = "",
) {
  fun sanitized(
    defaults: VoiceProviderSettings,
  ): VoiceProviderSettings = VoiceProviderSettings(
    provider = provider.trim().ifBlank { defaults.provider },
    baseUrl = baseUrl.trim(),
    endpoint = endpoint.trim(),
    model = model.trim().ifBlank { defaults.model },
    voicePreset = voicePreset.trim(),
    authProtocol = ProviderAuthProtocols.normalize(authProtocol.ifBlank { defaults.authProtocol }),
    apiKey = apiKey.trim(),
  )

  fun toJson(): JSONObject = JSONObject()
    .put("provider", provider)
    .put("baseUrl", baseUrl)
    .put("endpoint", endpoint)
    .put("model", model)
    .put("voicePreset", voicePreset)
    .put("authProtocol", authProtocol)
    .put("apiKey", apiKey)

  companion object {
    const val DEFAULT_MODEL: String = "tts-1"

    fun fromJson(
      payload: JSONObject?,
      defaults: VoiceProviderSettings,
    ): VoiceProviderSettings = payload?.let {
      VoiceProviderSettings(
        provider = it.optString("provider"),
        baseUrl = it.optString("baseUrl"),
        endpoint = it.optString("endpoint"),
        model = it.optString("model"),
        voicePreset = it.optString("voicePreset"),
        authProtocol = it.optString("authProtocol"),
        apiKey = it.optString("apiKey"),
      ).sanitized(defaults)
    } ?: defaults
  }
}

internal data class OnDeviceSttSettings(
  val modelPackage: String,
  val downloadStatus: String,
) {
  fun sanitized(
    defaults: OnDeviceSttSettings,
  ): OnDeviceSttSettings = OnDeviceSttSettings(
    modelPackage = modelPackage.trim().ifBlank { defaults.modelPackage },
    downloadStatus = downloadStatus.trim().ifBlank { defaults.downloadStatus },
  )

  fun toJson(): JSONObject = JSONObject()
    .put("modelPackage", modelPackage)
    .put("downloadStatus", downloadStatus)

  companion object {
    fun fromJson(
      payload: JSONObject?,
      defaults: OnDeviceSttSettings,
    ): OnDeviceSttSettings = payload?.let {
      OnDeviceSttSettings(
        modelPackage = it.optString("modelPackage"),
        downloadStatus = it.optString("downloadStatus"),
      ).sanitized(defaults)
    } ?: defaults
  }
}

internal data class MediaSpeechSettingsState(
  val imageGeneration: MediaProviderSettings,
  val videoGeneration: MediaProviderSettings,
  val voiceGeneration: VoiceProviderSettings,
  val sttRouteId: String,
  val externalStt: MediaProviderSettings,
  val onDeviceModel: OnDeviceSttSettings,
) {
  fun sanitized(): MediaSpeechSettingsState {
    val defaults = defaults()
    return MediaSpeechSettingsState(
      imageGeneration = imageGeneration.sanitized(defaults.imageGeneration),
      videoGeneration = videoGeneration.sanitized(defaults.videoGeneration),
      voiceGeneration = voiceGeneration.sanitized(defaults.voiceGeneration),
      sttRouteId = SpeechToTextRouteId.fromWireValue(sttRouteId)?.wireValue
        ?: defaults.sttRouteId,
      externalStt = externalStt.sanitized(defaults.externalStt),
      onDeviceModel = onDeviceModel.sanitized(defaults.onDeviceModel),
    )
  }

  fun toJson(): JSONObject = JSONObject()
    .put("imageGeneration", imageGeneration.toJson())
    .put("videoGeneration", videoGeneration.toJson())
    .put("voiceGeneration", voiceGeneration.toJson())
    .put("sttRouteId", sttRouteId)
    .put("externalStt", externalStt.toJson())
    .put("onDeviceModel", onDeviceModel.toJson())

  companion object {
    fun defaults(): MediaSpeechSettingsState = MediaSpeechSettingsState(
      imageGeneration = MediaProviderSettings(
        provider = "Fal AI",
        baseUrl = "https://api.fal.ai",
        endpoint = "/v1/images",
        model = "flux-pro",
        authProtocol = ProviderAuthProtocols.BEARER,
      ),
      videoGeneration = MediaProviderSettings(
        provider = "Runway",
        baseUrl = "https://api.runwayml.com",
        endpoint = "/v1/videos",
        model = "gen4_turbo",
        authProtocol = ProviderAuthProtocols.BEARER,
      ),
      voiceGeneration = VoiceProviderSettings(
        provider = "OpenAI TTS",
        baseUrl = "https://api.openai.com",
        endpoint = "/v1/audio/speech",
        model = VoiceProviderSettings.DEFAULT_MODEL,
        voicePreset = "alloy · calm",
        authProtocol = ProviderAuthProtocols.BEARER,
      ),
      sttRouteId = SpeechToTextRouteId.ON_DEVICE_MODEL.wireValue,
      externalStt = MediaProviderSettings(
        provider = "OpenAI Whisper",
        baseUrl = "https://api.openai.com",
        endpoint = "/v1/audio/transcriptions",
        model = "whisper-1",
        authProtocol = ProviderAuthProtocols.BEARER,
      ),
      onDeviceModel = OnDeviceSttSettings(
        modelPackage = "Whisper Small",
        downloadStatus = "Not downloaded · 1.4 GB",
      ),
    )

    fun fromJson(payload: JSONObject): MediaSpeechSettingsState {
      val defaults = defaults()
      return MediaSpeechSettingsState(
        imageGeneration = MediaProviderSettings.fromJson(
          payload.optJSONObject("imageGeneration"),
          defaults.imageGeneration,
        ),
        videoGeneration = MediaProviderSettings.fromJson(
          payload.optJSONObject("videoGeneration"),
          defaults.videoGeneration,
        ),
        voiceGeneration = VoiceProviderSettings.fromJson(
          payload.optJSONObject("voiceGeneration"),
          defaults.voiceGeneration,
        ),
        sttRouteId = payload.optString("sttRouteId", defaults.sttRouteId),
        externalStt = MediaProviderSettings.fromJson(
          payload.optJSONObject("externalStt"),
          defaults.externalStt,
        ),
        onDeviceModel = OnDeviceSttSettings.fromJson(
          payload.optJSONObject("onDeviceModel"),
          defaults.onDeviceModel,
        ),
      ).sanitized()
    }
  }
}

internal interface MediaSpeechSettingsKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun clear()
}

internal class InMemoryMediaSpeechSettingsKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : MediaSpeechSettingsKeyValueStore {
  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesMediaSpeechSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : MediaSpeechSettingsKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }

  fun hasAnyPersistedSetting(): Boolean =
    MEDIA_SPEECH_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedMediaSpeechSettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : MediaSpeechSettingsKeyValueStore {
  private val lock = Any()

  override fun getString(key: String): String? = synchronized(lock) {
    loadRecord().values[key]
  }

  override fun putString(key: String, value: String) {
    synchronized(lock) {
      updateValues { values ->
        values + (key to value)
      }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(MEDIA_SPEECH_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: MediaSpeechSettingsKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyState = legacyStore.getString(MediaSpeechSettingsStoreKeys.STATE)
        ?: return
      updateValues { values ->
        values + (MediaSpeechSettingsStoreKeys.STATE to legacyState)
      }
    }
  }

  private fun hasPersistedRecord(): Boolean = storage.updateRecord(
    name = MEDIA_SPEECH_SETTINGS_FILE_NAME,
    serializer = PersistedMediaSpeechSettingsRecord.serializer(),
  ) { persisted ->
    RecordStorageUpdate(
      value = persisted,
      result = persisted != null,
      write = false,
    )
  }

  private fun loadRecord(): PersistedMediaSpeechSettingsRecord =
    storage.updateRecord(
      name = MEDIA_SPEECH_SETTINGS_FILE_NAME,
      serializer = PersistedMediaSpeechSettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedMediaSpeechSettingsRecord()
      val repaired = existing.normalized()
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = persisted != null && repaired != existing,
      )
    }

  private fun updateValues(
    update: (Map<String, String>) -> Map<String, String>,
  ) {
    val now = clock()
    storage.updateRecord(
      name = MEDIA_SPEECH_SETTINGS_FILE_NAME,
      serializer = PersistedMediaSpeechSettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedMediaSpeechSettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(MEDIA_SPEECH_SETTING_KEYS::contains)
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = now,
          values = updatedValues,
        ),
        result = Unit,
      )
    }
  }
}

internal class MediaSpeechSettingsStore(
  private val keyValueStore: MediaSpeechSettingsKeyValueStore,
) {
  fun load(): MediaSpeechSettingsState {
    val rawPayload = keyValueStore.getString(MediaSpeechSettingsStoreKeys.STATE).orEmpty()
    if (rawPayload.isBlank()) {
      return MediaSpeechSettingsState.defaults()
    }
    return runCatching {
      MediaSpeechSettingsState.fromJson(JSONObject(rawPayload))
    }.getOrElse {
      MediaSpeechSettingsState.defaults()
    }
  }

  fun save(state: MediaSpeechSettingsState) {
    keyValueStore.putString(
      MediaSpeechSettingsStoreKeys.STATE,
      state.sanitized().toJson().toString(),
    )
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_MEDIA_SPEECH_SETTINGS_PREFERENCES,
    ): MediaSpeechSettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedMediaSpeechSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesMediaSpeechSettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return MediaSpeechSettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedMediaSpeechSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedMediaSpeechSettingsRecord = copy(
    values = values.filterKeys(MEDIA_SPEECH_SETTING_KEYS::contains),
  )
}

private val MEDIA_SPEECH_SETTING_KEYS: Set<String> = setOf(
  MediaSpeechSettingsStoreKeys.STATE,
)
