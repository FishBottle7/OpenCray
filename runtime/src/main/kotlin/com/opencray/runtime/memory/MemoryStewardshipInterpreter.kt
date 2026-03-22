package com.opencray.runtime.memory

data class MemoryStewardshipRequest(
  val sessionId: String,
  val workspaceId: String? = null,
  val userInput: String,
  val assistantOutput: String? = null,
  val toolObservations: List<String> = emptyList(),
  val activeRecords: List<StewardableMemoryRecord> = emptyList(),
  val proposedCandidates: List<StewardableMemoryCandidate> = emptyList(),
) {
  init {
    require(sessionId.isNotBlank()) { "MemoryStewardshipRequest sessionId must not be blank." }
    require(userInput.isNotBlank()) { "MemoryStewardshipRequest userInput must not be blank." }
  }
}

data class StewardableMemoryRecord(
  val id: String,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val content: String,
  val source: MemoryEvidenceSource? = null,
  val sourceSessionId: String? = null,
  val workspaceId: String? = null,
  val updatedAtEpochMs: Long? = null,
  val lastConfirmedAtEpochMs: Long? = null,
  val preferenceKey: String? = null,
  val preferenceValue: String? = null,
)

data class StewardableMemoryCandidate(
  val index: Int,
  val kind: MemoryKind,
  val scope: MemoryScope,
  val content: String,
  val source: MemoryEvidenceSource = MemoryEvidenceSource.USER_INPUT,
  val sourceSessionId: String,
  val sourceTaskId: String? = null,
  val workspaceId: String? = null,
  val preferenceKey: String? = null,
  val preferenceValue: String? = null,
) {
  init {
    require(index >= 0) { "StewardableMemoryCandidate index must be >= 0." }
    require(sourceSessionId.isNotBlank()) { "StewardableMemoryCandidate sourceSessionId must not be blank." }
  }
}

enum class MemoryStewardshipAction(val wireValue: String) {
  REFRESH_RECORD_WITH_CANDIDATE("refresh_record_with_candidate"),
  REAFFIRM_RECORD("reaffirm_record"),
  RESOLVE_RECORD("resolve_record"),
  SUPERSEDE_RECORD_WITH_CANDIDATE("supersede_record_with_candidate"),
  DROP_CANDIDATE("drop_candidate");

  companion object {
    fun fromWireValue(raw: String?): MemoryStewardshipAction? =
      entries.firstOrNull { action ->
        action.wireValue.equals(raw?.trim(), ignoreCase = true)
      }
  }
}

enum class MemoryStewardshipResolutionReason(val wireValue: String) {
  INVALIDATED("invalidated"),
  OBSOLETE("obsolete"),
  DUPLICATE("duplicate");

  companion object {
    fun fromWireValue(raw: String?): MemoryStewardshipResolutionReason? =
      entries.firstOrNull { reason ->
        reason.wireValue.equals(raw?.trim(), ignoreCase = true)
      }
  }
}

data class MemoryStewardshipDecision(
  val action: MemoryStewardshipAction,
  val recordId: String? = null,
  val candidateIndex: Int? = null,
  val resolutionReason: MemoryStewardshipResolutionReason? = null,
)

sealed interface MemoryStewardshipInterpretation {
  data class Success(
    val decisions: List<MemoryStewardshipDecision>,
  ) : MemoryStewardshipInterpretation

  data class Unavailable(
    val reason: String? = null,
  ) : MemoryStewardshipInterpretation
}

interface MemoryStewardshipInterpreter {
  fun interpret(request: MemoryStewardshipRequest): MemoryStewardshipInterpretation
}

object NoOpMemoryStewardshipInterpreter : MemoryStewardshipInterpreter {
  override fun interpret(
    request: MemoryStewardshipRequest,
  ): MemoryStewardshipInterpretation = MemoryStewardshipInterpretation.Unavailable(
    reason = "No memory stewardship interpreter configured.",
  )
}
