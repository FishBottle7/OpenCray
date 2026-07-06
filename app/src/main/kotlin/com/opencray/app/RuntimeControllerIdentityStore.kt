package com.opencray.app

import android.content.Context
import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import kotlinx.serialization.Serializable

internal interface RuntimeControllerIdentityStore {
  fun controllerIdForTarget(target: RuntimeServiceTarget): String
}

internal fun inMemoryRuntimeControllerIdentityStore(): RuntimeControllerIdentityStore =
  InMemoryRuntimeControllerIdentityStore()

internal fun defaultRuntimeControllerIdentityStoreProvider():
  (Context) -> RuntimeControllerIdentityStore = DefaultRuntimeControllerIdentityStoreProvider()

internal class FileBackedRuntimeControllerIdentityStore(
  private val storage: DurableTextStorage,
) : RuntimeControllerIdentityStore {
  override fun controllerIdForTarget(target: RuntimeServiceTarget): String {
    val fileName = fileNameForTarget(target)
    return storage.updateText(fileName) { current ->
      val record = current
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(::decodeRecordOrNull)
        ?.takeIf { decoded -> decoded.target == target.wireValue }
      val existing = record?.controllerId?.trim()?.takeIf(String::isNotBlank)
      if (existing != null) {
        DurableTextUpdate(
          text = current,
          result = existing,
          write = false,
        )
      } else {
        val created = lifecycleId(prefix = "runtime-controller-stable")
        val encoded = PersistenceJson.instance.encodeToString(
          serializer = PersistedRuntimeControllerIdentityRecord.serializer(),
          value = PersistedRuntimeControllerIdentityRecord(
            controllerId = created,
            target = target.wireValue,
            createdAtEpochMs = System.currentTimeMillis(),
          ),
        )
        DurableTextUpdate(
          text = encoded,
          result = created,
        )
      }
    }
  }

  private fun decodeRecordOrNull(
    encoded: String,
  ): PersistedRuntimeControllerIdentityRecord? = runCatching {
    PersistenceJson.instance.decodeFromString(
      deserializer = PersistedRuntimeControllerIdentityRecord.serializer(),
      string = encoded,
    )
  }.getOrNull()

  private fun fileNameForTarget(target: RuntimeServiceTarget): String =
    "runtime-controller-identity-${target.wireValue}.json"

  companion object {
    fun fromRootDirectory(runtimeRootDirectory: File): RuntimeControllerIdentityStore {
      if (!runtimeRootDirectory.exists()) {
        runtimeRootDirectory.mkdirs()
      }
      return FileBackedRuntimeControllerIdentityStore(
        storage = DirectoryDurableTextStorage(runtimeRootDirectory),
      )
    }

    fun fromContext(context: Context): RuntimeControllerIdentityStore {
      return fromRootDirectory(
        File(
          context.filesDir,
          FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
        ),
      )
    }
  }
}

private class DefaultRuntimeControllerIdentityStoreProvider :
  (Context) -> RuntimeControllerIdentityStore {
  private val lock = Any()
  private val fallbackStore = inMemoryRuntimeControllerIdentityStore()
  private val fileBackedStoresByRoot = linkedMapOf<String, RuntimeControllerIdentityStore>()

  override fun invoke(context: Context): RuntimeControllerIdentityStore {
    val filesDir = resolveFilesDir(context) ?: return fallbackStore
    val runtimeRoot = File(
      filesDir,
      FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
    )
    val rootKey = runtimeRoot.absoluteFile.path
    return synchronized(lock) {
      fileBackedStoresByRoot.getOrPut(rootKey) {
        FileBackedRuntimeControllerIdentityStore.fromRootDirectory(runtimeRoot)
      }
    }
  }

  private fun resolveFilesDir(context: Context): File? =
    runCatching { context.filesDir }
      .getOrNull()
      ?.takeIf { filesDir -> filesDir.path.isNotBlank() }
}

private class InMemoryRuntimeControllerIdentityStore : RuntimeControllerIdentityStore {
  private val lock = Any()
  private val controllerIds = linkedMapOf<RuntimeServiceTarget, String>()

  override fun controllerIdForTarget(target: RuntimeServiceTarget): String = synchronized(lock) {
    controllerIds.getOrPut(target) {
      lifecycleId(prefix = "runtime-controller-stable")
    }
  }
}

@Serializable
private data class PersistedRuntimeControllerIdentityRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val target: String,
  val controllerId: String,
  val createdAtEpochMs: Long,
)
