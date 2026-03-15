package com.opencray.app

import android.content.Context
import com.opencray.persistence.model.MemoryRecord
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
  private val soulExtensionFactory = PersonalizationSoulExtensionFactory()

  fun loadSoulProfile(): SoulProfile? {
    val record = soulStore.load() ?: return null
    return SoulProfile(
      presetName = record.extensions[Extensions.PRESET].orEmpty(),
      customLabel = record.displayName.orEmpty(),
      customGuidance = record.extensions[Extensions.CUSTOM_GUIDANCE].orEmpty(),
      extensions = record.extensions.filterKeys { key -> !Extensions.isReserved(key) },
    )
  }

  fun saveSoulProfile(profile: SoulProfile) {
    val existing = soulStore.load()
    val now = nowEpochMs()
    val explicitExtensions = profile.extensions.filterKeys { key -> !Extensions.isReserved(key) }
    val explicitNormalizedKeys = explicitExtensions.keys
      .mapNotNull(PersonalizationSoulExtensionFactory::normalizeKey)
      .toSet()
    val managedExtensions = soulExtensionFactory.createManagedExtensions(profile.presetName)
    val managedNormalizedKeys = managedExtensions.keys
      .mapNotNull(PersonalizationSoulExtensionFactory::normalizeKey)
      .toSet()
    val preservedExtensions = existing?.extensions.orEmpty().filterKeys { key ->
      !Extensions.isReserved(key) &&
        !PersonalizationSoulExtensionFactory.isManagedKey(key) &&
        PersonalizationSoulExtensionFactory.normalizeKey(key) !in explicitNormalizedKeys &&
        PersonalizationSoulExtensionFactory.normalizeKey(key) !in managedNormalizedKeys
    }
    val updatedExtensions = preservedExtensions + explicitExtensions + managedExtensions + buildMap {
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

  internal fun listMemoryRecords(): List<MemoryRecord> = memoryStore.list()

  internal fun upsertMemoryRecord(record: MemoryRecord) {
    memoryStore.upsert(record)
  }

  fun clearMemoryAndHistory() {
    memoryStore.clear()
    queueSnapshotStore.clear()
  }

  internal data class SoulProfile(
    val presetName: String,
    val customLabel: String,
    val customGuidance: String,
    val extensions: Map<String, String> = emptyMap(),
  )

  private object Extensions {
    const val PRESET: String = "preset"
    const val CUSTOM_GUIDANCE: String = "custom_guidance"

    private val RESERVED_KEYS: Set<String> = setOf(PRESET, CUSTOM_GUIDANCE)

    fun isReserved(key: String): Boolean = key.trim().lowercase() in RESERVED_KEYS
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
