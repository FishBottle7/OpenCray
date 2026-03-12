package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.SoulRecord
import com.opencray.persistence.store.SessionStoreQueueSnapshotStore
import com.opencray.persistence.store.file.JsonFileMemoryStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import com.opencray.persistence.store.file.JsonFileSoulStore
import java.io.File

internal class PersonalizationLocalStore(
  private val directory: File,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  private val soulStore = JsonFileSoulStore(directory)
  private val memoryStore = JsonFileMemoryStore(directory)
  private val sessionStore = JsonFileSessionStore(directory)
  private val queueSnapshotStore = SessionStoreQueueSnapshotStore(sessionStore)

  fun loadSoulProfile(): SoulProfile? {
    val record = soulStore.load() ?: return null
    return SoulProfile(
      presetName = record.extensions[Extensions.PRESET].orEmpty(),
      customLabel = record.displayName.orEmpty(),
      customGuidance = record.extensions[Extensions.CUSTOM_GUIDANCE].orEmpty(),
    )
  }

  fun saveSoulProfile(profile: SoulProfile) {
    val existing = soulStore.load()
    val now = nowEpochMs()
    val preservedExtensions = existing?.extensions.orEmpty().filterKeys { key ->
      key != Extensions.PRESET && key != Extensions.CUSTOM_GUIDANCE
    }
    val updatedExtensions = preservedExtensions + buildMap {
      if (profile.presetName.isNotBlank()) {
        put(Extensions.PRESET, profile.presetName)
      }
      if (profile.customGuidance.isNotBlank()) {
        put(Extensions.CUSTOM_GUIDANCE, profile.customGuidance)
      }
    }

    soulStore.save(
      SoulRecord(
        agentId = existing?.agentId ?: AGENT_ID,
        displayName = profile.customLabel.ifBlank { null },
        recordVersion = (existing?.recordVersion ?: 0L) + 1L,
        createdAtEpochMs = existing?.createdAtEpochMs ?: now,
        updatedAtEpochMs = now,
        termuxMetadata = existing?.termuxMetadata.orEmpty(),
        extensions = updatedExtensions,
      ),
    )
  }

  fun clearSoulProfile(): Boolean = soulStore.clear()

  fun clearMemoryAndHistory() {
    memoryStore.clear()
    queueSnapshotStore.clear()
  }

  internal data class SoulProfile(
    val presetName: String,
    val customLabel: String,
    val customGuidance: String,
  )

  private object Extensions {
    const val PRESET: String = "preset"
    const val CUSTOM_GUIDANCE: String = "custom_guidance"
  }

  companion object {
    private const val AGENT_ID = "app-shell-personalization"
    internal const val DIRECTORY_NAME = "personalization-local-state"

    fun fromContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): PersonalizationLocalStore = PersonalizationLocalStore(directoryForContext(context, directoryName))

    fun directoryForContext(
      context: Context,
      directoryName: String = DIRECTORY_NAME,
    ): File = File(context.filesDir, directoryName)
  }
}
