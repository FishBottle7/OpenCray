package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.soul.RelationshipEvent
import com.opencray.runtime.soul.RelationshipEventConfidence
import com.opencray.runtime.soul.RelationshipEventType
import com.opencray.runtime.soul.RelationshipEventValence
import com.opencray.runtime.soul.buildRelationshipEventMemoryExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRetrieverTest {
  @Test
  fun retrieveIncludesScopedDurableMemoriesAndFiltersUnrelatedRecords() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "user-pref",
          content = "Default to concise Chinese replies.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
        ),
        memoryRecord(
          id = "workspace-rule",
          content = "Do not revert user changes without explicit approval.",
          kind = MemoryKind.DURABLE_INSTRUCTION,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
        ),
        memoryRecord(
          id = "gradle-fact",
          content = "Project uses the Gradle wrapper from the repo root.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
        ),
        memoryRecord(
          id = "other-session-open",
          content = "Finish the docs pass after the queue repair.",
          kind = MemoryKind.TASK_COMMITMENT,
          scope = MemoryScope.SESSION,
          status = MemoryStatus.OPEN,
          sourceSessionId = "session-other",
        ),
        memoryRecord(
          id = "same-session-open",
          content = "Finish the Gradle validation after the patch lands.",
          kind = MemoryKind.TASK_COMMITMENT,
          scope = MemoryScope.SESSION,
          status = MemoryStatus.OPEN,
          sourceSessionId = "session-main",
        ),
        memoryRecord(
          id = "unrelated-fact",
          content = "Repository listens on port 8080 in staging.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        workspaceId = "workspace-main",
        userInput = "Please keep using Chinese, respect the no-revert rule, and verify the Gradle setup.",
      ),
    )

    assertEquals(4, result.memories.size)
    assertEquals(4, result.matchedRecordCount)
    assertEquals(0, result.omittedRecordCount)
    assertTrue(result.trace.queryTerms.containsAll(listOf("chinese", "gradle", "no-revert", "verify")))
    assertTrue(result.memories.take(2).map { memory -> memory.id }.containsAll(listOf("workspace-rule", "user-pref")))
    assertTrue(result.memories.any { memory -> memory.id == "gradle-fact" })
    assertTrue(result.memories.any { memory -> memory.id == "same-session-open" })
    assertTrue(result.memories.none { memory -> memory.id == "other-session-open" })
    assertTrue(result.memories.none { memory -> memory.id == "unrelated-fact" })
    assertEquals(result.memories.map { memory -> memory.id }, result.trace.selected.map { trace -> trace.id })
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.SCOPE_MISMATCH])
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.UNMATCHED_PROJECT_FACT])
  }

  @Test
  fun retrieveAppliesBudgetPerKindAndOverallRecallLimit() {
    val retriever = MemoryRetriever(
      policy = MemoryPolicy(
        recallBudget = MemoryRecallBudget(
          maxRecords = 3,
          maxChars = 400,
          maxRecordsPerKind = 1,
        ),
      ),
      clock = { NOW_EPOCH_MS },
    )

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "pref-1",
          content = "Default to terse Chinese replies.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
        ),
        memoryRecord(
          id = "pref-2",
          content = "Prefer PowerShell commands over bash commands.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
        ),
        memoryRecord(
          id = "fact-1",
          content = "Project uses Gradle and Kotlin in the runtime module.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
        ),
        memoryRecord(
          id = "fact-2",
          content = "Project stores API schemas under docs/api.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
        ),
        memoryRecord(
          id = "rule-1",
          content = "Do not skip tests before reporting completion.",
          kind = MemoryKind.DURABLE_INSTRUCTION,
          scope = MemoryScope.USER,
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        workspaceId = "workspace-main",
        userInput = "Check Gradle, stay terse, and do not skip tests.",
      ),
    )

    assertEquals(3, result.memories.size)
    assertEquals(4, result.matchedRecordCount)
    assertEquals(1, result.omittedRecordCount)
    assertEquals(1, result.memories.count { memory -> memory.kind == MemoryKind.USER_PREFERENCE })
    assertEquals(1, result.memories.count { memory -> memory.kind == MemoryKind.PROJECT_FACT })
    assertEquals(1, result.memories.count { memory -> memory.kind == MemoryKind.DURABLE_INSTRUCTION })
    assertEquals(listOf("pref-2"), result.trace.omitted.map { trace -> trace.id })
    assertEquals(
      listOf(MemoryRecallOmissionReason.MAX_PER_KIND),
      result.trace.omitted.map { trace -> trace.omissionReason },
    )
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.UNMATCHED_PROJECT_FACT])
  }

  @Test
  fun retrieveDropsExpiredAndResolvedRecords() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "expired-fact",
          content = "Project uses a temporary preview endpoint.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.WORKSPACE,
          workspaceId = "workspace-main",
          updatedAtEpochMs = NOW_EPOCH_MS - (91L * DAY_MS),
          ttlMs = 90L * DAY_MS,
          lastConfirmedAtEpochMs = NOW_EPOCH_MS - (91L * DAY_MS),
        ),
        memoryRecord(
          id = "resolved-commitment",
          content = "Finish the migration follow-up.",
          kind = MemoryKind.TASK_COMMITMENT,
          scope = MemoryScope.SESSION,
          status = MemoryStatus.RESOLVED,
          sourceSessionId = "session-main",
        ),
        memoryRecord(
          id = "active-rule",
          content = "Do not replace user edits without checking first.",
          kind = MemoryKind.DURABLE_INSTRUCTION,
          scope = MemoryScope.USER,
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        workspaceId = "workspace-main",
        userInput = "Keep the no-revert rule in mind.",
      ),
    )

    assertEquals(listOf("active-rule"), result.memories.map { memory -> memory.id })
    assertEquals(1, result.matchedRecordCount)
    assertEquals(0, result.omittedRecordCount)
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.EXPIRED])
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.RESOLVED])
  }

  @Test
  fun retrieveAcceptsShortQueriesForGenericMemoryRecall() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "assistant-rule",
          content = "When discussing the patch plan, refer to the helper as Xiao Bai.",
          kind = MemoryKind.DURABLE_INSTRUCTION,
          scope = MemoryScope.USER,
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        userInput = "xiao",
      ),
    )

    assertEquals(listOf("xiao"), result.trace.queryTerms)
    assertEquals(listOf("assistant-rule"), result.memories.map { memory -> memory.id })
  }

  @Test
  fun retrieveKeepsSoulPreferenceRecordsOutOfGenericMemoryRecallLayer() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "agent-name",
          content = "Agent display name is Xiao Bai",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          preferenceKey = MemoryPreferenceKeys.AGENT_DISPLAY_NAME,
          preferenceValue = "Xiao Bai",
        ),
        memoryRecord(
          id = "user-pref",
          content = "Default to concise Chinese replies.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        userInput = "Please keep using Chinese.",
      ),
    )

    assertEquals(listOf("user-pref"), result.memories.map { memory -> memory.id })
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.SOUL_PREFERENCE])
  }

  @Test
  fun retrieveKeepsInternalSoulObjectsOutOfGenericMemoryRecallLayer() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "relationship-event",
          content = "Internal relationship event supportive_response @ 1000: Supportive response.",
          kind = MemoryKind.PROJECT_FACT,
          scope = MemoryScope.USER,
          extraExtensions = buildRelationshipEventMemoryExtensions(
            RelationshipEvent(
              eventType = RelationshipEventType.SUPPORTIVE_RESPONSE,
              valence = RelationshipEventValence.POSITIVE,
              confidence = RelationshipEventConfidence.MEDIUM,
              summary = "Supportive response.",
              occurredAtEpochMs = 1_000L,
            ),
          ),
        ),
        memoryRecord(
          id = "user-pref",
          content = "Default to concise Chinese replies.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        userInput = "Please keep using Chinese.",
      ),
    )

    assertEquals(listOf("user-pref"), result.memories.map { memory -> memory.id })
    assertEquals(1, result.trace.filteredCounts[MemoryRecallFilterReason.INTERNAL_SOUL_OBJECT])
  }

  @Test
  fun retrieveToleratesDuplicateActivePreferenceRecordsAfterInterruptedSupersede() {
    val retriever = MemoryRetriever(clock = { NOW_EPOCH_MS })

    val result = retriever.retrieve(
      records = listOf(
        memoryRecord(
          id = "pref-warm",
          content = "Agent style profile should be warm.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          preferenceKey = "agent_style_profile",
          preferenceValue = "warm",
        ),
        memoryRecord(
          id = "pref-serious",
          content = "Agent style profile should be serious.",
          kind = MemoryKind.USER_PREFERENCE,
          scope = MemoryScope.USER,
          preferenceKey = "agent_style_profile",
          preferenceValue = "serious",
        ),
      ),
      request = MemoryRecallRequest(
        sessionId = "session-main",
        userInput = "Please keep using Chinese.",
      ),
    )

    assertTrue(result.memories.isEmpty())
    assertEquals(2, result.trace.filteredCounts[MemoryRecallFilterReason.SOUL_PREFERENCE])
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: MemoryKind,
    scope: MemoryScope,
    status: MemoryStatus = MemoryStatus.ACTIVE,
    sourceSessionId: String = "session-source",
    workspaceId: String? = null,
    ttlMs: Long? = null,
    lastConfirmedAtEpochMs: Long? = null,
    updatedAtEpochMs: Long = NOW_EPOCH_MS - 2_000L,
    preferenceKey: String? = null,
    preferenceValue: String? = null,
    extraExtensions: Map<String, String> = emptyMap(),
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    tags = listOf(
      "kind:${kind.name.lowercase()}",
      "scope:${scope.name.lowercase()}",
      "status:${status.name.lowercase()}",
    ),
    createdAtEpochMs = updatedAtEpochMs - 1_000L,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = buildMap {
      put(MemoryRecordExtensionKeys.KIND, kind.name.lowercase())
      put(MemoryRecordExtensionKeys.SCOPE, scope.name.lowercase())
      put(MemoryRecordExtensionKeys.STATUS, status.name.lowercase())
      put(MemoryRecordExtensionKeys.SOURCE_SESSION_ID, sourceSessionId)
      lastConfirmedAtEpochMs?.let { put(MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS, it.toString()) }
      ttlMs?.let { put(MemoryRecordExtensionKeys.TTL_MS, it.toString()) }
      workspaceId?.let { put(MemoryRecordExtensionKeys.WORKSPACE_ID, it) }
      preferenceKey?.let { put(MemoryRecordExtensionKeys.PREFERENCE_KEY, it) }
      preferenceValue?.let { put(MemoryRecordExtensionKeys.PREFERENCE_VALUE, it) }
      putAll(extraExtensions)
    },
  )

  private companion object {
    const val DAY_MS: Long = 24L * 60L * 60L * 1000L
    const val NOW_EPOCH_MS: Long = 200L * DAY_MS
  }
}
