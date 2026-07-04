package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import java.util.Locale
import kotlinx.serialization.Serializable

private const val DEFAULT_LOCALE_SETTINGS_PREFERENCES = "opencray.locale-settings"
private const val LOCALE_SETTINGS_FILE_NAME = "locale-settings.json"

internal enum class AppLanguage(
  val tag: String,
) {
  ENGLISH("en"),
  SIMPLIFIED_CHINESE("zh-CN"),
  ;

  companion object {
    val default: AppLanguage = ENGLISH

    fun fromRaw(rawValue: String?): AppLanguage = entries.firstOrNull { language ->
      language.tag.equals(rawValue?.trim(), ignoreCase = true) ||
        language.name.equals(rawValue?.trim(), ignoreCase = true)
    } ?: default
  }
}

private object LocaleSettingsStoreKeys {
  const val APP_LANGUAGE = "app_language"
}

internal interface LocaleSettingsKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun clear()
}

internal class InMemoryLocaleSettingsKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : LocaleSettingsKeyValueStore {
  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesLocaleSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : LocaleSettingsKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }

  fun hasAnyPersistedSetting(): Boolean =
    LOCALE_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedLocaleSettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : LocaleSettingsKeyValueStore {
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
      storage.delete(LOCALE_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(legacyStore: LocaleSettingsKeyValueStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyLanguage = legacyStore.getString(LocaleSettingsStoreKeys.APP_LANGUAGE)
        ?: return
      updateValues { values ->
        values + (LocaleSettingsStoreKeys.APP_LANGUAGE to legacyLanguage)
      }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(LOCALE_SETTINGS_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedLocaleSettingsRecord =
    storage.updateRecord(
      name = LOCALE_SETTINGS_FILE_NAME,
      serializer = PersistedLocaleSettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedLocaleSettingsRecord()
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
      name = LOCALE_SETTINGS_FILE_NAME,
      serializer = PersistedLocaleSettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedLocaleSettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(LOCALE_SETTING_KEYS::contains)
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

internal class LocaleSettingsStore(
  private val keyValueStore: LocaleSettingsKeyValueStore,
) {
  fun loadLanguage(): AppLanguage = AppLanguage.fromRaw(
    keyValueStore.getString(LocaleSettingsStoreKeys.APP_LANGUAGE) ?: AppLanguage.default.tag,
  )

  fun saveLanguage(language: AppLanguage) {
    keyValueStore.putString(LocaleSettingsStoreKeys.APP_LANGUAGE, language.tag)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_LOCALE_SETTINGS_PREFERENCES,
    ): LocaleSettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedLocaleSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesLocaleSettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return LocaleSettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedLocaleSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedLocaleSettingsRecord = copy(
    values = values.filterKeys(LOCALE_SETTING_KEYS::contains),
  )
}

private val LOCALE_SETTING_KEYS: Set<String> = setOf(
  LocaleSettingsStoreKeys.APP_LANGUAGE,
)

internal object OpenCrayLocaleManager {
  fun wrap(base: Context): Context {
    val language = LocaleSettingsStore.fromContext(base).loadLanguage()
    val locale = when (language) {
      AppLanguage.ENGLISH -> Locale.ENGLISH
      AppLanguage.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    }
    Locale.setDefault(locale)

    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return base.createConfigurationContext(configuration)
  }
}
