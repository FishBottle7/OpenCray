package com.opencray.persistence.store

import com.opencray.core.contracts.ContractJson
import com.opencray.core.orchestrator.SessionQueueSnapshot
import com.opencray.core.orchestrator.SessionQueueSnapshotStore
import com.opencray.persistence.model.SessionRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class SessionStoreQueueSnapshotStore(
  private val sessionStore: SessionStore,
) : SessionQueueSnapshotStore {

  override fun load(): SessionQueueSnapshot? {
    val record = safeLoadRecord() ?: return null
    val encodedSnapshot = record.state[StateKeys.QUEUE_SNAPSHOT_JSON] ?: return null

    return runCatching {
      ContractJson.instance.decodeFromString<SessionQueueSnapshot>(encodedSnapshot)
    }.getOrNull()
  }

  override fun save(snapshot: SessionQueueSnapshot) {
    val encodedSnapshot = ContractJson.instance.encodeToString(snapshot)
    try {
      sessionStore.update { existing ->
        SessionStoreUpdate(
          record = recordForSnapshot(
            snapshot = snapshot,
            existing = existing,
            encodedSnapshot = encodedSnapshot,
          ),
          result = Unit,
        )
      }
    } catch (_: IllegalStateException) {
      sessionStore.save(
        recordForSnapshot(
          snapshot = snapshot,
          existing = null,
          encodedSnapshot = encodedSnapshot,
        ),
      )
    }
  }

  private fun recordForSnapshot(
    snapshot: SessionQueueSnapshot,
    existing: SessionRecord?,
    encodedSnapshot: String,
  ): SessionRecord {
    val mergedState = existing?.state.orEmpty() + mapOf(
      StateKeys.QUEUE_STATE to snapshot.lifecycleState.name.lowercase(),
      StateKeys.QUEUE_NEXT_ENQUEUE_ORDER to snapshot.nextEnqueueOrder.toString(),
      StateKeys.QUEUE_SNAPSHOT_JSON to encodedSnapshot,
    )

    val updatedAt = maxOf(snapshot.updatedAtEpochMs, existing?.updatedAtEpochMs ?: snapshot.updatedAtEpochMs)
    val record = SessionRecord(
      sessionId = snapshot.sessionId,
      agentId = snapshot.agentId,
      state = mergedState,
      recordVersion = (existing?.recordVersion ?: 0L) + 1L,
      createdAtEpochMs = existing?.createdAtEpochMs ?: snapshot.updatedAtEpochMs,
      updatedAtEpochMs = updatedAt,
      termuxMetadata = existing?.termuxMetadata.orEmpty(),
      extensions = existing?.extensions.orEmpty(),
    )
    return record
  }

  override fun clear() {
    sessionStore.clear()
  }

  private fun safeLoadRecord(): SessionRecord? =
    runCatching { sessionStore.load() }.getOrNull()

  object StateKeys {
    const val QUEUE_STATE: String = "queue_state"
    const val QUEUE_NEXT_ENQUEUE_ORDER: String = "queue_next_enqueue_order"
    const val QUEUE_SNAPSHOT_JSON: String = "queue_snapshot_json"
  }
}
