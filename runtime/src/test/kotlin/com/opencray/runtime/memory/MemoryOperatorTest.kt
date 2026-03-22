package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.buildRelationshipStateMemoryExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryOperatorTest {
  @Test
  fun suppressMarksActiveRecordResolvedWithOperatorReason() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "memory-user",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
        status = MemoryStatus.ACTIVE,
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    val result = operator.apply(
      MemoryOperatorRequest(
        recordId = "memory-user",
        action = MemoryOperatorAction.SUPPRESS,
        actorSessionId = "session-main",
      ),
    )

    assertTrue(result.applied)
    val record = store.list().single()
    assertEquals("resolved", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(
      MemoryOperator.RESOLUTION_REASON_OPERATOR_SUPPRESSED,
      record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON],
    )
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS])
    assertTrue(record.tags.contains("status:resolved"))
  }

  @Test
  fun reaffirmRefreshesActiveRecordConfirmationTimestamp() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "memory-user",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
        status = MemoryStatus.ACTIVE,
        updatedAtEpochMs = 1_000L,
        lastConfirmedAtEpochMs = 1_100L,
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    val result = operator.apply(
      MemoryOperatorRequest(
        recordId = "memory-user",
        action = MemoryOperatorAction.REAFFIRM,
        actorSessionId = "session-main",
      ),
    )

    assertTrue(result.applied)
    val record = store.list().single()
    assertEquals("active", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS])
    assertEquals(2_000L, record.updatedAtEpochMs)
  }

  @Test
  fun reaffirmReactivatesOperatorSuppressedRecordUsingDefaultKindStatus() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "memory-project",
        kind = MemoryKind.PROJECT_FACT,
        scope = MemoryScope.WORKSPACE,
        status = MemoryStatus.RESOLVED,
        workspaceId = "workspace-main",
        updatedAtEpochMs = 1_000L,
        resolutionReason = MemoryOperator.RESOLUTION_REASON_OPERATOR_SUPPRESSED,
        resolvedAtEpochMs = 1_100L,
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    val result = operator.apply(
      MemoryOperatorRequest(
        recordId = "memory-project",
        action = MemoryOperatorAction.REAFFIRM,
        actorSessionId = "session-main",
      ),
    )

    assertTrue(result.applied)
    val record = store.list().single()
    assertEquals("active", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals(null, record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals(null, record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS])
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS])
  }

  @Test
  fun suppressIsIdempotentForAlreadySuppressedRecord() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "memory-user",
        kind = MemoryKind.USER_PREFERENCE,
        scope = MemoryScope.USER,
        status = MemoryStatus.RESOLVED,
        resolutionReason = MemoryOperator.RESOLUTION_REASON_OPERATOR_SUPPRESSED,
        resolvedAtEpochMs = 1_100L,
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    val result = operator.apply(
      MemoryOperatorRequest(
        recordId = "memory-user",
        action = MemoryOperatorAction.SUPPRESS,
        actorSessionId = "session-main",
      ),
    )

    assertFalse(result.applied)
    val record = store.list().single()
    assertEquals("1100", record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS])
  }

  @Test(expected = IllegalArgumentException::class)
  fun reaffirmRejectsCompletedResolvedRecord() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "memory-commitment",
        kind = MemoryKind.TASK_COMMITMENT,
        scope = MemoryScope.SESSION,
        status = MemoryStatus.RESOLVED,
        sourceSessionId = "session-main",
        resolutionReason = "completed",
        resolvedAtEpochMs = 1_100L,
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    operator.apply(
      MemoryOperatorRequest(
        recordId = "memory-commitment",
        action = MemoryOperatorAction.REAFFIRM,
        actorSessionId = "session-main",
      ),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun applyRejectsInternalSoulStateRecords() {
    val store = InMemoryMemoryStore(
      memoryRecord(
        id = "relationship-state",
        kind = MemoryKind.PROJECT_FACT,
        scope = MemoryScope.USER,
        status = MemoryStatus.ACTIVE,
        extraExtensions = buildRelationshipStateMemoryExtensions(
          RelationshipState(
            familiarity = 1,
            trust = 1,
            safety = 1,
            reciprocity = 1,
            lastUpdatedAtEpochMs = 1_000L,
          ),
        ),
      ),
    )
    val operator = MemoryOperator(store = store, clock = { 2_000L })

    operator.apply(
      MemoryOperatorRequest(
        recordId = "relationship-state",
        action = MemoryOperatorAction.SUPPRESS,
        actorSessionId = "session-main",
      ),
    )
  }

  private class InMemoryMemoryStore(
    vararg records: MemoryRecord,
  ) : MemoryStore {
    private val values = linkedMapOf<String, MemoryRecord>().apply {
      records.forEach { record -> put(record.id, record) }
    }

    override fun list(): List<MemoryRecord> = values.values.toList()

    override fun upsert(record: MemoryRecord) {
      values[record.id] = record
    }

    override fun delete(id: String): Boolean = values.remove(id) != null

    override fun clear(): Boolean {
      val hadEntries = values.isNotEmpty()
      values.clear()
      return hadEntries
    }
  }

  private fun memoryRecord(
    id: String,
    kind: MemoryKind,
    scope: MemoryScope,
    status: MemoryStatus,
    sourceSessionId: String = "session-source",
    workspaceId: String? = null,
    updatedAtEpochMs: Long = 1_000L,
    lastConfirmedAtEpochMs: Long? = 1_000L,
    resolvedAtEpochMs: Long? = null,
    resolutionReason: String? = null,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "Remember this durable fact.",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:${kind.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${status.name.lowercase()}",
    ),
    extensions = buildMap {
      put(MemoryRecordExtensionKeys.KIND, kind.name.lowercase())
      put(MemoryRecordExtensionKeys.SCOPE, scope.name.lowercase())
      put(MemoryRecordExtensionKeys.STATUS, status.name.lowercase())
      put(MemoryRecordExtensionKeys.SOURCE, MemoryEvidenceSource.USER_INPUT.name.lowercase())
      put(MemoryRecordExtensionKeys.SOURCE_SESSION_ID, sourceSessionId)
      lastConfirmedAtEpochMs?.let {
        put(MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS, it.toString())
      }
      resolvedAtEpochMs?.let {
        put(MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS, it.toString())
      }
      resolutionReason?.let {
        put(MemoryRecordExtensionKeys.RESOLUTION_REASON, it)
      }
      workspaceId?.let {
        put(MemoryRecordExtensionKeys.WORKSPACE_ID, it)
      }
      putAll(extraExtensions)
    },
  )
}
