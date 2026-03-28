package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import com.opencray.persistence.security.CredentialRef

private const val DEFAULT_SANDBOX_SETTINGS_PREFERENCES = "opencray.sandbox-settings"

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
}

internal class SandboxSettingsStore(
  private val keyValueStore: SandboxSettingsKeyValueStore,
) {
  fun load(defaults: SandboxSettingsState = SandboxSettingsState()): SandboxSettingsState =
    defaults.copy(
      enabled = keyValueStore.getBoolean(SandboxSettingsStoreKeys.ENABLED) ?: defaults.enabled,
      providerId = keyValueStore.getString(SandboxSettingsStoreKeys.PROVIDER_ID) ?: defaults.providerId,
      defaultBackend = keyValueStore.getString(SandboxSettingsStoreKeys.DEFAULT_BACKEND)
        ?: defaults.defaultBackend,
      sessionMode = keyValueStore.getString(SandboxSettingsStoreKeys.SESSION_MODE) ?: defaults.sessionMode,
      autoResume = keyValueStore.getBoolean(SandboxSettingsStoreKeys.AUTO_RESUME) ?: defaults.autoResume,
      idleTimeoutMinutes = keyValueStore.getString(SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES)
        ?.toIntOrNull()
        ?: defaults.idleTimeoutMinutes,
      startupTimeoutMs = keyValueStore.getString(SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS)
        ?.toLongOrNull()
        ?: defaults.startupTimeoutMs,
      requestTimeoutMs = keyValueStore.getString(SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS)
        ?.toLongOrNull()
        ?: defaults.requestTimeoutMs,
      timeoutAction = keyValueStore.getString(SandboxSettingsStoreKeys.TIMEOUT_ACTION) ?: defaults.timeoutAction,
      templateId = keyValueStore.getString(SandboxSettingsStoreKeys.TEMPLATE_ID) ?: defaults.templateId,
      e2bApiKeyCredentialRef = keyValueStore.getString(SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF)
        ?: defaults.e2bApiKeyCredentialRef,
    ).sanitized()

  fun save(state: SandboxSettingsState) {
    val sanitized = state.sanitized()
    keyValueStore.putBoolean(SandboxSettingsStoreKeys.ENABLED, sanitized.enabled)
    keyValueStore.putString(SandboxSettingsStoreKeys.PROVIDER_ID, sanitized.providerId)
    keyValueStore.putString(SandboxSettingsStoreKeys.DEFAULT_BACKEND, sanitized.defaultBackend)
    keyValueStore.putString(SandboxSettingsStoreKeys.SESSION_MODE, sanitized.sessionMode)
    keyValueStore.putBoolean(SandboxSettingsStoreKeys.AUTO_RESUME, sanitized.autoResume)
    keyValueStore.putString(
      SandboxSettingsStoreKeys.IDLE_TIMEOUT_MINUTES,
      sanitized.idleTimeoutMinutes.toString(),
    )
    keyValueStore.putString(
      SandboxSettingsStoreKeys.STARTUP_TIMEOUT_MS,
      sanitized.startupTimeoutMs.toString(),
    )
    keyValueStore.putString(
      SandboxSettingsStoreKeys.REQUEST_TIMEOUT_MS,
      sanitized.requestTimeoutMs.toString(),
    )
    keyValueStore.putString(SandboxSettingsStoreKeys.TIMEOUT_ACTION, sanitized.timeoutAction)
    keyValueStore.putString(SandboxSettingsStoreKeys.TEMPLATE_ID, sanitized.templateId)
    keyValueStore.putString(
      SandboxSettingsStoreKeys.E2B_API_KEY_CREDENTIAL_REF,
      sanitized.e2bApiKeyCredentialRef,
    )
  }

  fun clear() {
    keyValueStore.clear()
  }

  companion object {
    fun fromContext(
      context: Context,
      preferencesName: String = DEFAULT_SANDBOX_SETTINGS_PREFERENCES,
    ): SandboxSettingsStore = SandboxSettingsStore(
      keyValueStore = SharedPreferencesSandboxSettingsKeyValueStore(
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
      ),
    )
  }
}
