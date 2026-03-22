package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCommitmentResolverTest {
  @Test
  fun maintainResolvesOpenSessionCommitmentFromSemanticInterpreterDecision() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-1",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-1",
              action = TaskCommitmentIntentAction.RESOLVE,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "I ran the targeted runtime tests and updated the docs.",
        toolObservations = emptyList(),
      ),
    )

    assertEquals(listOf("commitment-1"), summary.resolvedRecords.map { record -> record.id })
    val record = store.list().single()
    assertEquals("resolved", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("completed", record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.RESOLVED_AT_EPOCH_MS])
    assertTrue(record.tags.contains("status:resolved"))
  }

  @Test
  fun maintainUsesSemanticInterpreterToResolveCommitmentWithoutKeywordHeuristic() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-semantic-resolve",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-semantic-resolve",
              action = TaskCommitmentIntentAction.RESOLVE,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "The targeted runtime tests are green now and the docs are in sync.",
        toolObservations = emptyList(),
      ),
    )

    assertEquals(listOf("commitment-semantic-resolve"), summary.resolvedRecords.map { record -> record.id })
    assertEquals("resolved", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainUsesSemanticInterpreterToReaffirmOpenCommitment() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-reaffirm",
        content = "stabilize the flaky runtime test",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-reaffirm",
              action = TaskCommitmentIntentAction.REAFFIRM,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "The flaky runtime test still needs work; I am continuing on it next.",
        toolObservations = emptyList(),
      ),
    )

    assertEquals(listOf("commitment-reaffirm"), summary.reaffirmedRecords.map { record -> record.id })
    val record = store.list().single()
    assertEquals("open", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("2000", record.extensions[MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS])
    assertEquals(2_000L, record.updatedAtEpochMs)
  }

  @Test
  fun maintainSupersedesOpenCommitmentWithProposedReplacement() {
    val store = InMemoryMemoryStore()
    val proposedCandidate = taskCommitmentCandidate(
      content = "verify the Android smoke tests",
      sessionId = "session-1",
      taskId = "task-2",
    )
    store.upsert(
      memoryRecord(
        id = "commitment-old",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-old",
              action = TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED,
              proposedCommitmentIndex = 0,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      evidence = MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "Next I will verify the Android smoke tests.",
      ),
      proposedCandidates = listOf(proposedCandidate),
    )

    assertEquals(listOf("commitment-old"), summary.resolvedRecords.map { record -> record.id })
    assertTrue(summary.droppedProposedCommitmentIndexes.isEmpty())
    val record = store.list().single()
    assertEquals("resolved", record.extensions[MemoryRecordExtensionKeys.STATUS])
    assertEquals("superseded", record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertEquals(
      taskCommitmentRecordId(proposedCandidate),
      record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY],
    )
  }

  @Test
  fun maintainDropsDuplicateProposedCommitmentAndReaffirmsExistingOne() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-reaffirm",
        content = "stabilize the flaky runtime test",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              proposedCommitmentIndex = 0,
              action = TaskCommitmentIntentAction.DROP_PROPOSED,
            ),
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-reaffirm",
              action = TaskCommitmentIntentAction.REAFFIRM,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      evidence = MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "Next I will stabilize the flaky runtime test.",
      ),
      proposedCandidates = listOf(
        taskCommitmentCandidate(
          content = "stabilize the flaky runtime test",
          sessionId = "session-1",
          taskId = "task-2",
        ),
      ),
    )

    assertEquals(listOf("commitment-reaffirm"), summary.reaffirmedRecords.map { record -> record.id })
    assertEquals(listOf(0), summary.droppedProposedCommitmentIndexes)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainIgnoresSupersedeDecisionWhenProposedCommitmentMatchesCurrentContent() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-duplicate",
        content = "stabilize the flaky runtime test",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-duplicate",
              action = TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED,
              proposedCommitmentIndex = 0,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      evidence = MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "Next I will stabilize the flaky runtime test.",
      ),
      proposedCandidates = listOf(
        taskCommitmentCandidate(
          content = "stabilize the flaky runtime test",
          sessionId = "session-1",
          taskId = "task-2",
        ),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainResolvesAbandonedCommitmentWithoutReplacement() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-abandoned",
        content = "prepare the release branch",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Success(
          decisions = listOf(
            TaskCommitmentIntentDecision(
              commitmentId = "commitment-abandoned",
              action = TaskCommitmentIntentAction.ABANDON,
            ),
          ),
        ),
      ),
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "I am dropping the release branch work for now.",
      ),
    )

    assertEquals(listOf("commitment-abandoned"), summary.resolvedRecords.map { record -> record.id })
    val record = store.list().single()
    assertEquals("abandoned", record.extensions[MemoryRecordExtensionKeys.RESOLUTION_REASON])
    assertTrue(record.extensions[MemoryRecordExtensionKeys.SUPERSEDED_BY].isNullOrBlank())
  }

  @Test
  fun maintainDeletesExpiredTaskCommitments() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-expired",
        content = "update the docs after queue repair",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
        ttlMs = 100L,
        lastConfirmedAtEpochMs = 1_050L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "No new work.",
      ),
    )

    assertEquals(listOf("commitment-expired"), summary.expiredRecordIds)
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun maintainIgnoresCompletionEvidenceForDifferentSessionCommitment() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-other-session",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-other",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-main",
        taskId = "task-2",
        userInput = "Please continue.",
        toolObservations = listOf("Targeted runtime tests passed successfully."),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainDoesNotResolveCommitmentWithoutInterpreterEvenWhenOutputLooksCompleted() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-no-interpreter",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "I ran the targeted runtime tests and updated the docs.",
        toolObservations = emptyList(),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainSuppressesHeuristicResolutionWhenInterpreterFailsClosed() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-fail-closed",
        content = "run the targeted runtime tests",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "Malformed interpreter output.",
        ),
      ),
    )

    val summary = resolver.maintain(
      MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "I ran the targeted runtime tests and updated the docs.",
        toolObservations = emptyList(),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  @Test
  fun maintainDoesNotDropOrResolveWhenInterpreterIsUnavailableEvenWithProposedCommitment() {
    val store = InMemoryMemoryStore()
    store.upsert(
      memoryRecord(
        id = "commitment-unavailable",
        content = "stabilize the flaky runtime test",
        sourceSessionId = "session-1",
        updatedAtEpochMs = 1_000L,
      ),
    )
    val resolver = TaskCommitmentResolver(
      store = store,
      clock = { 2_000L },
      intentInterpreter = FixedTaskCommitmentIntentInterpreter(
        TaskCommitmentIntentInterpretation.Unavailable(
          allowHeuristicFallback = false,
          reason = "Gateway unavailable.",
        ),
      ),
    )

    val summary = resolver.maintain(
      evidence = MemoryTurnEvidence(
        sessionId = "session-1",
        taskId = "task-2",
        userInput = "Please continue.",
        assistantOutput = "Next I will stabilize the flaky runtime test.",
      ),
      proposedCandidates = listOf(
        taskCommitmentCandidate(
          content = "stabilize the flaky runtime test",
          sessionId = "session-1",
          taskId = "task-2",
        ),
      ),
    )

    assertTrue(summary.isEmpty)
    assertEquals("open", store.list().single().extensions[MemoryRecordExtensionKeys.STATUS])
  }

  private fun memoryRecord(
    id: String,
    content: String,
    sourceSessionId: String,
    updatedAtEpochMs: Long,
    ttlMs: Long = 14L * 24L * 60L * 60L * 1000L,
    lastConfirmedAtEpochMs: Long = updatedAtEpochMs,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:task_commitment",
      "scope:session",
      "status:open",
    ),
    extensions = mapOf(
      MemoryRecordExtensionKeys.KIND to "task_commitment",
      MemoryRecordExtensionKeys.SCOPE to "session",
      MemoryRecordExtensionKeys.STATUS to "open",
      MemoryRecordExtensionKeys.SOURCE_SESSION_ID to sourceSessionId,
      MemoryRecordExtensionKeys.TTL_MS to ttlMs.toString(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to lastConfirmedAtEpochMs.toString(),
    ),
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }

  private class FixedTaskCommitmentIntentInterpreter(
    private val interpretation: TaskCommitmentIntentInterpretation,
  ) : TaskCommitmentIntentInterpreter {
    override fun interpret(
      request: TaskCommitmentIntentRequest,
    ): TaskCommitmentIntentInterpretation = interpretation
  }

  private fun taskCommitmentCandidate(
    content: String,
    sessionId: String,
    taskId: String,
  ): MemoryCandidate = MemoryCandidate(
    kind = MemoryKind.TASK_COMMITMENT,
    scope = MemoryScope.SESSION,
    status = MemoryStatus.OPEN,
    content = content,
    source = MemoryEvidenceSource.ASSISTANT_OUTPUT,
    sourceSessionId = sessionId,
    sourceTaskId = taskId,
    ttlMs = 14L * 24L * 60L * 60L * 1000L,
  )

  private fun taskCommitmentRecordId(candidate: MemoryCandidate): String {
    val digestSource = "task_commitment|session:${candidate.sourceSessionId}|${candidate.content.lowercase(java.util.Locale.US)}"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
    return "mem-${digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)}"
  }
}
