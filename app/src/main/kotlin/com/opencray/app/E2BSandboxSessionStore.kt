package com.opencray.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DEFAULT_E2B_SANDBOX_SESSION_PREFERENCES = "opencray.e2b-sandbox-session"
private const val KEY_ACTIVE_SESSION = "active_session"

@Serializable
internal data class E2BSandboxSessionSnapshot(
  val sandboxId: String,
  val sandboxDomain: String,
  val envdAccessToken: String? = null,
  val trafficAccessToken: String? = null,
  val workspaceRoot: String,
  val templateId: String,
  val updatedAtEpochMs: Long,
  val previewCandidatePorts: List<Int> = emptyList(),
  val remoteWorkspaceRoot: String? = null,
  val lastPreviewUrl: String? = null,
  val lastPreviewPort: Int? = null,
  val lastPreviewPath: String? = null,
  val lastPreviewProbeStatus: String? = null,
  val lastPreviewProbeHttpStatusCode: Int? = null,
  val lastPreviewProbeMessage: String? = null,
  val lastPreviewOpenedAtEpochMs: Long? = null,
  val lastPreviewProbeObservedAtEpochMs: Long? = null,
  val lastPreviewProbeSource: String? = null,
)

internal interface E2BSandboxSessionKeyValueStore {
  fun getString(key: String): String?

  fun putString(key: String, value: String)

  fun remove(key: String)
}

internal class InMemoryE2BSandboxSessionKeyValueStore(
  private val values: LinkedHashMap<String, String> = linkedMapOf(),
) : E2BSandboxSessionKeyValueStore {
  override fun getString(key: String): String? = values[key]

  override fun putString(key: String, value: String) {
    values[key] = value
  }

  override fun remove(key: String) {
    values.remove(key)
  }
}

internal class SharedPreferencesE2BSandboxSessionKeyValueStore(
  private val sharedPreferences: SharedPreferences,
) : E2BSandboxSessionKeyValueStore {
  override fun getString(key: String): String? =
    if (sharedPreferences.contains(key)) sharedPreferences.getString(key, null) else null

  override fun putString(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  override fun remove(key: String) {
    sharedPreferences.edit().remove(key).apply()
  }
}

internal class E2BSandboxSessionStore(
  private val keyValueStore: E2BSandboxSessionKeyValueStore,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
  fun load(): E2BSandboxSessionSnapshot? =
    keyValueStore.getString(KEY_ACTIVE_SESSION)
      ?.takeIf(String::isNotBlank)
      ?.let { raw ->
        runCatching { json.decodeFromString(E2BSandboxSessionSnapshot.serializer(), raw) }
          .getOrNull()
      }

  fun save(snapshot: E2BSandboxSessionSnapshot) {
    keyValueStore.putString(
      KEY_ACTIVE_SESSION,
      json.encodeToString(E2BSandboxSessionSnapshot.serializer(), snapshot),
    )
  }

  fun clear() {
    keyValueStore.remove(KEY_ACTIVE_SESSION)
  }

  companion object {
    fun fromContext(context: Context): E2BSandboxSessionStore = E2BSandboxSessionStore(
      keyValueStore = SharedPreferencesE2BSandboxSessionKeyValueStore(
        context.applicationContext.getSharedPreferences(
          DEFAULT_E2B_SANDBOX_SESSION_PREFERENCES,
          Context.MODE_PRIVATE,
        ),
      ),
    )
  }
}
