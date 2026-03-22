package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.soul.hasSoulObjectPayload
import java.util.Locale

enum class MemoryOperatorAction(val wireValue: String) {
  REAFFIRM("reaffirm"),
  SUPPRESS("suppress");

  companion object {
    fun fromWireValue(raw: String?): MemoryOperatorAction? =
      entries.firstOrNull { action ->
        action.wireValue.equals(raw?.trim(), ignoreCase = true)
      }
  }
}

data class MemoryOperatorRequest(
  val recordId: String,
  val action: MemoryOperatorAction,
  val actorSessionId: String,
  val actorTaskId: String? = null,
) {
  init {
    require(recordId.isNotBlank()) { "MemoryOperatorRequest recordId must not be blank." }
    require(actorSessionId.isNotBlank()) { "MemoryOperatorRequest actorSessionId must not be blank." }
  }
}

data class MemoryOperatorResult(
  val action: MemoryOperatorAction,
  val recordId: String,
  val applied: Boolean,
  val updatedRecord: MemoryRecord,
)

class MemoryOperator(
  private val store: MemoryStore,
  private val policy: MemoryPolicy = MemoryPolicy(),
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun apply(request: MemoryOperatorRequest): MemoryOperatorResult {
    val existing = store.list().firstOrNull { record -> record.id == request.recordId }
      ?: throw IllegalArgumentException("Memory record '${request.recordId}' was not found.")
    require(!existing.hasSoulObjectPayload()) {
      "Memory operator actions are not supported for internal soul-state records."
    }
    val metadata = existing.parseMemoryMetadata()
      ?: throw IllegalArgumentException("Memory record '${request.recordId}' does not have valid memory metadata.")
    val now = clock()
    val updated = when (request.action) {
      MemoryOperatorAction.REAFFIRM -> reaffirmRecord(
        record = existing,
        metadata = metadata,
        nowEpochMs = now,
      )
      MemoryOperatorAction.SUPPRESS -> suppressRecord(
        record = existing,
        metadata = metadata,
        nowEpochMs = now,
      )
    }
    val applied = updated != existing
    if (applied) {
      store.upsert(updated)
    }
    return MemoryOperatorResult(
      action = request.action,
      recordId = request.recordId,
      applied = applied,
      updatedRecord = updated,
    )
  }

  private fun reaffirmRecord(
    record: MemoryRecord,
    metadata: ParsedMemoryMetadata,
    nowEpochMs: Long,
  ): MemoryRecord = when (metadata.status) {
    MemoryStatus.ACTIVE,
    MemoryStatus.OPEN,
    -> updateStatus(
      record = record,
      status = metadata.status,
      nowEpochMs = nowEpochMs,
      resolutionReason = null,
      resolvedAtEpochMs = null,
      supersededBy = null,
      updateLastConfirmedAtEpochMs = true,
    )

    MemoryStatus.RESOLVED -> {
      require(canReactivateResolvedRecord(record)) {
        "Memory record '${record.id}' cannot be reaffirmed once it is resolved for '${record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON].orEmpty()}'."
      }
      updateStatus(
        record = record,
        status = policy.defaultStatusFor(metadata.kind),
        nowEpochMs = nowEpochMs,
        resolutionReason = null,
        resolvedAtEpochMs = null,
        supersededBy = null,
        updateLastConfirmedAtEpochMs = true,
      )
    }
  }

  private fun suppressRecord(
    record: MemoryRecord,
    metadata: ParsedMemoryMetadata,
    nowEpochMs: Long,
  ): MemoryRecord {
    if (
      metadata.status == MemoryStatus.RESOLVED &&
      record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] == RESOLUTION_REASON_OPERATOR_SUPPRESSED
    ) {
      return record
    }
    require(metadata.status != MemoryStatus.RESOLVED) {
      "Memory record '${record.id}' is already resolved and cannot be suppressed again."
    }
    return updateStatus(
      record = record,
      status = MemoryStatus.RESOLVED,
      nowEpochMs = nowEpochMs,
      resolutionReason = RESOLUTION_REASON_OPERATOR_SUPPRESSED,
      resolvedAtEpochMs = nowEpochMs,
      supersededBy = null,
      updateLastConfirmedAtEpochMs = false,
    )
  }

  private fun canReactivateResolvedRecord(record: MemoryRecord): Boolean =
    record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON] == RESOLUTION_REASON_OPERATOR_SUPPRESSED

  private fun updateStatus(
    record: MemoryRecord,
    status: MemoryStatus,
    nowEpochMs: Long,
    resolutionReason: String?,
    resolvedAtEpochMs: Long?,
    supersededBy: String?,
    updateLastConfirmedAtEpochMs: Boolean,
  ): MemoryRecord {
    val nextTags = record.tags
      .filterNot { tag -> tag.startsWith("status:") }
      .plus("status:${status.name.lowercase(Locale.US)}")
      .distinct()
      .sorted()
    val nextExtensions = buildMap<String, String> {
      putAll(record.extensions)
      put(MemoryRecordExtensionKeys.STATUS, status.name.lowercase(Locale.US))
      if (updateLastConfirmedAtEpochMs) {
        put(MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS, nowEpochMs.toString())
      }
      if (resolutionReason != null) {
        put(MemoryRecordExtensionKeys.RESOLUTION_REASON, resolutionReason)
      } else {
        remove(MemoryRecordExtensionKeys.RESOLUTION_REASON)
      }
      if (resolvedAtEpochMs != null) {
        put(MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS, resolvedAtEpochMs.toString())
      } else {
        remove(MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS)
      }
      if (!supersededBy.isNullOrBlank()) {
        put(MemoryRecordExtensionKeys.SUPERSEDED_BY, supersededBy)
      } else {
        remove(MemoryRecordExtensionKeys.SUPERSEDED_BY)
      }
    }
    return record.copy(
      tags = nextTags,
      recordVersion = record.recordVersion + 1L,
      updatedAtEpochMs = maxOf(record.createdAtEpochMs, nowEpochMs),
      extensions = nextExtensions,
    )
  }

  companion object {
    const val RESOLUTION_REASON_OPERATOR_SUPPRESSED: String = "operator_suppressed"
  }
}
