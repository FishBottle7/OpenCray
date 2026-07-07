package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import java.io.File
import kotlinx.serialization.Serializable

private const val DEFAULT_SANDBOX_SETTINGS_PREFERENCES = "opencray.sandbox-settings"
private const val SANDBOX_SETTINGS_FILE_NAME = "sandbox-settings.json"

internal object SandboxSettingsStoreKeys {
  const val ENABLED = "enabled"
  const val PROVIDER_ID = "provider_id"
  const val DEFAULT_BACKEND = "default_backend"
  const val SESSION_MODE = "session_mode"
  const val AUTO_RESUME = "auto_resume"
  const val IDLE_TIMEOUT_MINUTES = "idle_timeout_minutes"
  const val STARTUP_TIMEOUT_MS = "startup_timeout_ms"
  const val REQUEST_TIMEOUT_MS = "request_timeout_ms"
  const val TIMEOUT_ACTION = "timeout_action"
  const val TEMPLATE_ID = "template_id"
  const val E2B_API_KEY_CREDENTIAL_REF = "e2b_api_key_credential_ref"
}

internal enum class SandboxProviderId(
  val wireValue: String,
) {
  E2B("e2b"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SandboxProviderId? =
      entries.firstOrNull { provider ->
        provider.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal enum class SandboxExecutionBackendPreference(
  val wireValue: String,
) {
  LOCAL("local"),
  AUTO("auto"),
  SANDBOX("sandbox"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SandboxExecutionBackendPreference? =
      entries.firstOrNull { preference ->
        preference.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal enum class SandboxSessionMode(
  val wireValue: String,
) {
  EPHEMERAL("ephemeral"),
  STICKY("sticky"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SandboxSessionMode? =
      entries.firstOrNull { mode ->
        mode.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal enum class SandboxTimeoutAction(
  val wireValue: String,
) {
  KILL("kill"),
  PAUSE("pause"),
  ;

  companion object {
    fun fromWireValue(rawValue: String): SandboxTimeoutAction? =
      entries.firstOrNull { action ->
        action.wireValue == rawValue.trim().lowercase()
      }
  }
}

internal data class SandboxSettingsState(
  val enabled: Boolean = false,
  val providerId: String = SandboxProviderId.E2B.wireValue,
  val defaultBackend: String = SandboxExecutionBackendPreference.LOCAL.wireValue,
  val sessionMode: String = SandboxSessionMode.EPHEMERAL.wireValue,
  val autoResume: Boolean = false,
  val idleTimeoutMinutes: Int = DEFAULT_IDLE_TIMEOUT_MINUTES,
  val startupTimeoutMs: Long = DEFAULT_STARTUP_TIMEOUT_MS,
  val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
  val timeoutAction: String = SandboxTimeoutAction.KILL.wireValue,
  val templateId: String = "",
  val e2bApiKeyCredentialRef: String = "",
) {
  fun sanitized(): SandboxSettingsState = copy(
    providerId = SandboxProviderId.fromWireValue(providerId)?.wireValue
      ?: SandboxProviderId.E2B.wireValue,
    defaultBackend = SandboxExecutionBackendPreference.fromWireValue(defaultBackend)?.wireValue
      ?: SandboxExecutionBackendPreference.LOCAL.wireValue,
    sessionMode = SandboxSessionMode.fromWireValue(sessionMode)?.wireValue
      ?: SandboxSessionMode.EPHEMERAL.wireValue,
    autoResume = autoResume,
    idleTimeoutMinutes = idleTimeoutMinutes.coerceIn(MIN_IDLE_TIMEOUT_MINUTES, MAX_IDLE_TIMEOUT_MINUTES),
    startupTimeoutMs = startupTimeoutMs.coerceIn(MIN_STARTUP_TIMEOUT_MS, MAX_STARTUP_TIMEOUT_MS),
    requestTimeoutMs = requestTimeoutMs.coerceIn(MIN_REQUEST_TIMEOUT_MS, MAX_REQUEST_TIMEOUT_MS),
    timeoutAction = SandboxTimeoutAction.fromWireValue(timeoutAction)?.wireValue
      ?: SandboxTimeoutAction.KILL.wireValue,
    templateId = templateId.trim(),
    e2bApiKeyCredentialRef = e2bApiKeyCredentialRef.trim()
      .takeIf(String::isNotBlank)
      ?.let { rawRef ->
        runCatching { CredentialRef(rawRef) }.getOrNull()?.uri
      }
      .orEmpty(),
  )

  fun e2bApiKeyCredentialRefOrNull(): CredentialRef? =
    e2bApiKeyCredentialRef.trim()
      .takeIf(String::isNotBlank)
      ?.let(::CredentialRef)

  companion object {
    internal const val DEFAULT_IDLE_TIMEOUT_MINUTES: Int = 15
    internal const val DEFAULT_STARTUP_TIMEOUT_MS: Long = 30_000L
    internal const val DEFAULT_REQUEST_TIMEOUT_MS: Long = 300_000L
    internal const val MIN_IDLE_TIMEOUT_MINUTES: Int = 1
    internal const val MAX_IDLE_TIMEOUT_MINUTES: Int = 24 * 60
    internal const val MIN_STARTUP_TIMEOUT_MS: Long = 5_000L
    internal const val MAX_STARTUP_TIMEOUT_MS: Long = 120_000L
    internal const val MIN_REQUEST_TIMEOUT_MS: Long = 5_000L
    internal const val MAX_REQUEST_TIMEOUT_MS: Long = 24 * 60 * 60 * 1000L
  }
}

internal interface SandboxSettingsKeyValueStore {
  fun getBoolean(key: String): Boolean?

  fun putBoolean(key: String, value: Boolean)

  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun clear()

  fun loadState(defaults: SandboxSettingsState = SandboxSettingsState()): SandboxSettingsState =
    defaults.copy(
      enabled = getBoolean(SandboxSettingsStoreKeys.ENABLED) ?: defaults.enabled,
      providerId = getString(SandboxSettingsStoreKeys.PROVIDER_ID) ?: defaults.providerId,
      defaultBackend = getString(SandboxSettingsStoreKeys.DEFAULT_BACKEND)
        ?: defaults.defaultBackend,
      sessionMode = getString(SandboxSettingsStoreKeys.SESSION_MODE) ?: defaults.sessionMode,
      autoResume = getBoolean(SandboxSettingsStoreKeys.AUTO_RESUME) ?: defaults.autoResume,
      idleTimeoutMinutes = getString(SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES)
        ?.toIntOrNull()
        ?: defaults.idleTimeoutMinutes,
      startupTimeoutMs = getString(SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS)
        ?.toLongOrNull()
        ?: defaults.startupTimeoutMs,
      requestTimeoutMs = getString(SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS)
        ?.toLongOrNull()
        ?: defaults.requestTimeoutMs,
      timeoutAction = getString(SandboxSettingsStoreKeys.TIMEOUT_ACTION) ?: defaults.timeoutAction,
      templateId = getString(SandboxSettingsStoreKeys.TEMPLATE_ID) ?: defaults.templateId,
      e2bApiKeyCredentialRef = getString(SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF)
        ?: defaults.e2bApiKeyCredentialRef,
    ).sanitized()

  fun saveState(state: SandboxSettingsState) {
    val sanitized = state.sanitized()
    putBoolean(SandboxSettingsStoreKeys.ENABLED, sanitized.enabled)
    putString(SandboxSettingsStoreKeys.PROVIDER_ID, sanitized.providerId)
    putString(SandboxSettingsStoreKeys.DEFAULT_BACKEND, sanitized.defaultBackend)
    putString(SandboxSettingsStoreKeys.SESSION_MODE, sanitized.sessionMode)
    putBoolean(SandboxSettingsStoreKeys.AUTO_RESUME, sanitized.autoResume)
    putString(
      SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES,
      sanitized.idleTimeoutMinutes.toString(),
    )
    putString(
      SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS,
      sanitized.startupTimeoutMs.toString(),
    )
    putString(
      SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS,
      sanitized.requestTimeoutMs.toString(),
    )
    putString(SandboxSettingsStoreKeys.TIMEOUT_ACTION, sanitized.timeoutAction)
    putString(SandboxSettingsStoreKeys.TEMPLATE_ID, sanitized.templateId)
    putString(
      SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF,
      sanitized.e2bApiKeyCredentialRef,
    )
  }
}

internal class InMemorySandboxSettingsKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : SandboxSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) {
    values[key] = value.toString()
  }

  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun clear() {
    values.clear()
  }
}

internal class SharedPreferencesSandboxSettingsKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : SandboxSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? =
    if (sharedPreferences.contains(key)) sharedPreferences.getBoolean(key, false) else null

  override fun putBoolean(key: String, value: Boolean) {
    sharedPreferences.edit().putBoolean(key, value).apply()
  }

  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun clear() {
    sharedPreferences.edit().clear().apply()
  }

  fun hasAnyPersistedSetting(): Boolean =
    SANDBOX_SETTING_KEYS.any(sharedPreferences::contains)
}

internal class FileBackedSandboxSettingsKeyValueStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : SandboxSettingsKeyValueStore {
  private val lock = Any()

  override fun getBoolean(key: String): Boolean? =
    getString(key)?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) {
    putString(key, value.toString())
  }

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

  override fun loadState(defaults: SandboxSettingsState): SandboxSettingsState = synchronized(lock) {
    loadRecord().toState(defaults)
  }

  override fun saveState(state: SandboxSettingsState) {
    synchronized(lock) {
      val values = valuesForState(state.sanitized())
      updateValues { values }
    }
  }

  override fun clear() {
    synchronized(lock) {
      storage.delete(SANDBOX_SETTINGS_FILE_NAME)
    }
  }

  fun migrateFromLegacyIfEmpty(
    legacyStore: SandboxSettingsKeyValueStore,
    defaults: SandboxSettingsState = SandboxSettingsState(),
  ) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      updateValues { valuesForState(legacyStore.loadState(defaults)) }
    }
  }

  private fun hasPersistedRecord(): Boolean = storage.updateRecord(
    name = SANDBOX_SETTINGS_FILE_NAME,
    serializer = PersistedSandboxSettingsRecord.serializer(),
  ) { persisted ->
    RecordStorageUpdate(
      value = persisted,
      result = persisted != null,
      write = false,
    )
  }

  private fun loadRecord(): PersistedSandboxSettingsRecord =
    storage.updateRecord(
      name = SANDBOX_SETTINGS_FILE_NAME,
      serializer = PersistedSandboxSettingsRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedSandboxSettingsRecord()
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
      name = SANDBOX_SETTINGS_FILE_NAME,
      serializer = PersistedSandboxSettingsRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedSandboxSettingsRecord()).normalized()
      val updatedValues = update(existing.values)
        .filterKeys(SANDBOX_SETTING_KEYS::contains)
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

internal class SandboxSettingsStore(
  private val keyValueStore: SandboxSettingsKeyValueStore,
) {
  fun load(defaults: SandboxSettingsState = SandboxSettingsState()): SandboxSettingsState =
    keyValueStore.loadState(defaults)

  fun save(state: SandboxSettingsState) {
    keyValueStore.saveState(state)
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_SANDBOX_SETTINGS_PREFERENCES,
    ): SandboxSettingsStore {
      val appContext = context.applicationContext
      val fileBackedStore = FileBackedSandboxSettingsKeyValueStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
      val legacyStore = SharedPreferencesSandboxSettingsKeyValueStore(
        appContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      )
      if (legacyStore.hasAnyPersistedSetting()) {
        fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
      }
      return SandboxSettingsStore(keyValueStore = fileBackedStore)
    }
  }
}

@Serializable
private data class PersistedSandboxSettingsRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val values: Map<String, String> = emptyMap(),
) {
  fun normalized(): PersistedSandboxSettingsRecord = copy(
    values = values.filterKeys(SANDBOX_SETTING_KEYS::contains),
  )

  fun toState(defaults: SandboxSettingsState): SandboxSettingsState {
    val legacy = PersistedSandboxSettingsKeyValueStore(values)
    return legacy.loadState(defaults)
  }
}

private class PersistedSandboxSettingsKeyValueStore(
  private val values: Map<String, String>,
) : SandboxSettingsKeyValueStore {
  override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

  override fun putBoolean(key: String, value: Boolean) = Unit

  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) = Unit

  override fun clear() = Unit
}

private fun valuesForState(state: SandboxSettingsState): Map<String, String> = linkedMapOf(
  SandboxSettingsStoreKeys.ENABLED to state.enabled.toString(),
  SandboxSettingsStoreKeys.PROVIDER_ID to state.providerId,
  SandboxSettingsStoreKeys.DEFAULT_BACKEND to state.defaultBackend,
  SandboxSettingsStoreKeys.SESSION_MODE to state.sessionMode,
  SandboxSettingsStoreKeys.AUTO_RESUME to state.autoResume.toString(),
  SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES to state.idleTimeoutMinutes.toString(),
  SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS to state.startupTimeoutMs.toString(),
  SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS to state.requestTimeoutMs.toString(),
  SandboxSettingsStoreKeys.TIMEOUT_ACTION to state.timeoutAction,
  SandboxSettingsStoreKeys.TEMPLATE_ID to state.templateId,
  SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF to state.e2bApiKeyCredentialRef,
)

private val SANDBOX_SETTING_KEYS: Set<String> = setOf(
  SandboxSettingsStoreKeys.ENABLED,
  SandboxSettingsStoreKeys.PROVIDER_ID,
  SandboxSettingsStoreKeys.DEFAULT_BACKEND,
  SandboxSettingsStoreKeys.SESSION_MODE,
  SandboxSettingsStoreKeys.AUTO_RESUME,
  SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES,
  SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS,
  SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS,
  SandboxSettingsStoreKeys.TIMEOUT_ACTION,
  SandboxSettingsStoreKeys.TEMPLATE_ID,
  SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF,
)
