package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.memory.MemoryCandidate
import com.opencray.runtime.memory.MemoryCandidateExtractor
import com.opencray.runtime.memory.MemoryFlushCoordinator
import com.opencray.runtime.memory.MemoryFlushSummary
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStewardshipService
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.TaskCommitmentResolver
import com.opencray.runtime.memory.MemoryTurnEvidence
import com.opencray.runtime.memory.MemoryWriter
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.soul.InteractionPreferenceMemoryWritePlanner
import com.opencray.runtime.soul.NoOpRelationshipEventInterpreter
import com.opencray.runtime.soul.RelationshipEventInterpretation
import com.opencray.runtime.soul.RelationshipEventInterpreter
import com.opencray.runtime.soul.RelationshipEventRequest
import com.opencray.runtime.soul.RelationshipMemoryWritePlanner
import com.opencray.runtime.soul.SoulPlasticity

internal data class MemoryIngestionSummary(
  val writtenRecords: List<MemoryRecord> = emptyList(),
  val resolvedRecords: List<MemoryRecord> = emptyList(),
  val reaffirmedRecords: List<MemoryRecord> = emptyList(),
  val expiredRecordIds: List<String> = emptyList(),
) {
  val isEmpty: Boolean
    get() = writtenRecords.isEmpty() &&
      resolvedRecords.isEmpty() &&
      reaffirmedRecords.isEmpty() &&
      expiredRecordIds.isEmpty()
}

internal class ChatMemoryIngestionCoordinator(
  private val memoryStore: MemoryStore,
  private val workspaceIdProvider: () -> String? = { null },
  private val candidateExtractor: MemoryCandidateExtractor = MemoryCandidateExtractor(),
  private val writer: MemoryWriter = MemoryWriter(store = memoryStore),
  private val taskCommitmentResolver: TaskCommitmentResolver = TaskCommitmentResolver(store = memoryStore),
  private val memoryStewardshipService: MemoryStewardshipService = MemoryStewardshipService(),
  private val soulPlasticityProvider: () -> SoulPlasticity = { SoulPlasticity.MEDIUM },
  private val interactionPreferenceWritePlanner: InteractionPreferenceMemoryWritePlanner = InteractionPreferenceMemoryWritePlanner(),
  private val relationshipEventInterpreter: RelationshipEventInterpreter = NoOpRelationshipEventInterpreter,
  private val relationshipWritePlanner: RelationshipMemoryWritePlanner = RelationshipMemoryWritePlanner(),
  private val sessionScopedStateMarker: (String) -> Unit = {},
  private val flushCoordinator: MemoryFlushCoordinator = MemoryFlushCoordinator(
    candidateExtractor = candidateExtractor,
    writer = writer,
    existingRecordIdsProvider = { memoryStore.list().mapTo(linkedSetOf(), MemoryRecord::id) },
  ),
) {
  fun flushBeforeCompaction(
    sessionId: String,
    conversation: List<RuntimeConversationMessage>,
    taskId: String? = null,
  ): MemoryFlushSummary = flushCoordinator.flushBeforeCompaction(
    sessionId = sessionId,
    workspaceId = workspaceIdProvider(),
    conversation = conversation,
    taskId = taskId,
  )

  fun ingestCompletedTurn(
    sessionId: String,
    task: AgentTask,
    result: ExecutionResult,
    userInput: String? = null,
    assistantOutput: String?,
    toolObservations: List<String>,
  ): MemoryIngestionSummary {
    if (task.type != AgentTaskType.PROMPT) {
      return MemoryIngestionSummary()
    }
    if (result.status == ExecutionStatus.CANCELLED || isApprovalRequired(result)) {
      return MemoryIngestionSummary()
    }
    val workspaceId = workspaceIdProvider()

    val evidence = MemoryTurnEvidence(
      sessionId = sessionId,
      taskId = task.id,
      workspaceId = workspaceId,
      userInput = userInput?.trim()?.takeIf(String::isNotBlank) ?: task.input,
      assistantOutput = assistantOutput
        ?.trim()
        ?.takeIf { result.status == ExecutionStatus.SUCCESS }
        ?.takeIf(String::isNotBlank),
      toolObservations = toolObservations
        .asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList(),
    )
    val writeCandidates = candidateExtractor.extract(evidence)
    val maintenance = taskCommitmentResolver.maintain(
      evidence = evidence,
      proposedCandidates = writeCandidates,
    )
    val filteredWriteCandidates = writeCandidates.filterIndexed { index, _ ->
      index !in maintenance.droppedProposedCommitmentIndexes
    }
    val existingRecordsBeforeWrite = memoryStore.list()
    val stewardshipPlan = memoryStewardshipService.plan(
      existingRecords = existingRecordsBeforeWrite,
      evidence = evidence,
      proposedCandidates = filteredWriteCandidates,
    )
    stewardshipPlan.resolvedRecords.forEach(memoryStore::upsert)
    stewardshipPlan.reaffirmedRecords.forEach(memoryStore::upsert)
    val writeSummary = writer.write(stewardshipPlan.acceptedCandidates)
    val plasticity = soulPlasticityProvider()
    val interactionPreferenceScopesToRewrite = interactionPreferenceSnapshotScopesToRewrite(
      acceptedCandidates = stewardshipPlan.acceptedCandidates,
      resolvedRecords = stewardshipPlan.resolvedRecords,
      reaffirmedRecords = stewardshipPlan.reaffirmedRecords,
    )
    val interactionPreferenceWriteSummary = if (interactionPreferenceScopesToRewrite.isNotEmpty()) {
      writer.write(
        interactionPreferenceWritePlanner.plan(
          existingRecords = emptyList(),
          sourceRecords = memoryStore.list(),
          forcedScopes = interactionPreferenceScopesToRewrite,
          plasticity = plasticity,
          sourceSessionId = sessionId,
          workspaceId = workspaceId,
          sourceTaskId = task.id,
        ).candidates,
      ).writtenRecords
    } else {
      emptyList()
    }
    val relationshipEvents = when (
      val interpretation = relationshipEventInterpreter.interpret(
        RelationshipEventRequest(
          sessionId = sessionId,
          workspaceId = workspaceId,
          userInput = evidence.userInput,
          assistantOutput = evidence.assistantOutput,
          toolObservations = evidence.toolObservations,
        ),
      )
    ) {
      is RelationshipEventInterpretation.Success -> interpretation.events
      RelationshipEventInterpretation.Unavailable -> emptyList()
    }
    val relationshipWriteSummary = if (relationshipEvents.isEmpty()) {
      emptyList()
    } else {
      writer.write(
        relationshipWritePlanner.plan(
          existingRecords = memoryStore.list(),
          events = relationshipEvents,
          plasticity = plasticity,
          sourceSessionId = sessionId,
          workspaceId = workspaceId,
          sourceTaskId = task.id,
        ).candidates,
      ).writtenRecords
    }
    val summary = MemoryIngestionSummary(
      writtenRecords = writeSummary.writtenRecords + interactionPreferenceWriteSummary + relationshipWriteSummary,
      resolvedRecords = maintenance.resolvedRecords + stewardshipPlan.resolvedRecords,
      reaffirmedRecords = maintenance.reaffirmedRecords + stewardshipPlan.reaffirmedRecords,
      expiredRecordIds = maintenance.expiredRecordIds,
    )
    if (
      (summary.writtenRecords + summary.resolvedRecords + summary.reaffirmedRecords)
        .any(::isActiveSessionScopedRecord)
    ) {
      sessionScopedStateMarker(sessionId)
    }
    return summary
  }

  private fun isApprovalRequired(result: ExecutionResult): Boolean =
    result.status == ExecutionStatus.DENIED &&
      (result.errorCode == ERROR_APPROVAL_REQUIRED || result.errorCode == ERROR_HIGH_RISK_APPROVAL_REQUIRED)

  private fun interactionPreferenceSnapshotScopesToRewrite(
    acceptedCandidates: List<MemoryCandidate>,
    resolvedRecords: List<MemoryRecord>,
    reaffirmedRecords: List<MemoryRecord>,
  ): Set<MemoryScope> = buildSet {
    acceptedCandidates.forEach { candidate ->
      interactionPreferenceSourceScopeOrNull(candidate)?.let(::add)
    }
    resolvedRecords.forEach { record ->
      interactionPreferenceSourceScopeOrNull(record)?.let(::add)
    }
    reaffirmedRecords.forEach { record ->
      interactionPreferenceSourceScopeOrNull(record)?.let(::add)
    }
  }

  private fun interactionPreferenceSourceScopeOrNull(candidate: MemoryCandidate): MemoryScope? {
    if (candidate.kind != MemoryKind.USER_PREFERENCE) {
      return null
    }
    if (candidate.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] !in INTERACTION_PREFERENCE_SOURCE_KEYS) {
      return null
    }
    return when (candidate.scope) {
      MemoryScope.USER,
      MemoryScope.WORKSPACE,
      -> candidate.scope

      MemoryScope.SESSION -> null
    }
  }

  private fun interactionPreferenceSourceScopeOrNull(record: MemoryRecord): MemoryScope? {
    if (record.extensions[MemoryRecordExtensionKeys.KIND] != MemoryKind.USER_PREFERENCE.name.lowercase()) {
      return null
    }
    if (record.extensions[MemoryRecordExtensionKeys.PREFERENCE_KEY] !in INTERACTION_PREFERENCE_SOURCE_KEYS) {
      return null
    }
    return when (record.extensions[MemoryRecordExtensionKeys.SCOPE]) {
      MemoryScope.USER.name.lowercase() -> MemoryScope.USER
      MemoryScope.WORKSPACE.name.lowercase() -> MemoryScope.WORKSPACE
      else -> null
    }
  }

  private fun isActiveSessionScopedRecord(record: MemoryRecord): Boolean =
    record.extensions[MemoryRecordExtensionKeys.SCOPE] == MemoryScope.SESSION.name.lowercase() &&
      record.extensions[MemoryRecordExtensionKeys.STATUS] != MemoryStatus.RESOLVED.name.lowercase()

  private companion object {
    const val ERROR_APPROVAL_REQUIRED: String = "APPROVAL_REQUIRED"
    const val ERROR_HIGH_RISK_APPROVAL_REQUIRED: String = "HIGH_RISK_APPROVAL_REQUIRED"
    val INTERACTION_PREFERENCE_SOURCE_KEYS: Set<String> = setOf(
      MemoryPreferenceKeys.INTERACTION_PREFERENCE_SIGNAL,
      MemoryPreferenceKeys.USER_PREFERRED_NAME,
      MemoryPreferenceKeys.USER_ADDRESS_STYLE,
    )
  }
}
