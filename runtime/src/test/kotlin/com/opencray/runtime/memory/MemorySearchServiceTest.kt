package com.opencray.runtime.memory

import com.opencray.persistence.model.MemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySearchServiceTest {
  private val service = MemorySearchService(
    clock = { NOW_EPOCH_MS },
  )
  private val projector = MemoryCorpusProjector(
    clock = { NOW_EPOCH_MS },
  )

  @Test
  fun projectSearchAndGetExposeProjectedMemoryCorpus() {
    val context = MemoryToolContext(
      sessionId = "session-main",
      workspaceId = "workspace-main",
      records = listOf(
        memoryRecord(
          id = "mem-user",
          content = "Default to concise Chinese replies.",
          kind = "user_preference",
          scope = "user",
          sourceSessionId = "session-source",
          confirmedAtEpochMs = DAY_1_EPOCH_MS,
          updatedAtEpochMs = DAY_1_EPOCH_MS,
        ),
        memoryRecord(
          id = "mem-workspace",
          content = "Project uses the Gradle wrapper from the repo root.",
          kind = "project_fact",
          scope = "workspace",
          sourceSessionId = "session-source",
          workspaceId = "workspace-main",
          confirmedAtEpochMs = DAY_2_EPOCH_MS,
          updatedAtEpochMs = DAY_2_EPOCH_MS,
        ),
      ),
    )

    val projection = projector.project(context)
    val expectedDayPath = "memory/${formatMemoryDateStamp(DAY_2_EPOCH_MS)}.md"

    assertNotNull(projection.file("MEMORY.md"))
    assertNotNull(projection.file(expectedDayPath))

    val search = service.search(
      context = context,
      query = "gradle wrapper repo root",
      maxResults = 3,
    )

    assertEquals(listOf("gradle", "wrapper", "repo", "root"), search.queryTerms)
    assertEquals(1, search.matches.size)
    assertEquals("mem-workspace", search.matches.single().recordId)
    assertEquals(expectedDayPath, search.matches.single().path)
    assertTrue(search.matches.single().snippet.contains("content: Project uses the Gradle wrapper from the repo root"))

    val get = service.get(
      context = context,
      path = expectedDayPath,
      from = search.matches.single().startLine,
      lines = 4,
    )

    assertEquals(expectedDayPath, get.path)
    assertEquals(search.matches.single().startLine, get.startLine)
    assertTrue(get.text.contains("## mem-workspace"))
    assertTrue(get.text.contains("kind: project_fact"))
  }

  @Test
  fun searchFiltersScopeMismatchAndExpiryButKeepsResolvedAccessibleRecords() {
    val context = MemoryToolContext(
      sessionId = "session-main",
      workspaceId = "workspace-main",
      records = listOf(
        memoryRecord(
          id = "resolved-session",
          content = "Finished the queue repair and closed the follow-up task.",
          kind = "task_commitment",
          scope = "session",
          status = "resolved",
          sourceSessionId = "session-main",
          confirmedAtEpochMs = DAY_2_EPOCH_MS,
          updatedAtEpochMs = DAY_2_EPOCH_MS,
        ),
        memoryRecord(
          id = "wrong-workspace",
          content = "Project switched to pnpm workspaces.",
          kind = "project_fact",
          scope = "workspace",
          sourceSessionId = "session-source",
          workspaceId = "workspace-other",
          confirmedAtEpochMs = DAY_2_EPOCH_MS,
          updatedAtEpochMs = DAY_2_EPOCH_MS,
        ),
        memoryRecord(
          id = "expired-fact",
          content = "Use the legacy build script in scripts/build-old.sh.",
          kind = "project_fact",
          scope = "workspace",
          sourceSessionId = "session-source",
          workspaceId = "workspace-main",
          ttlMs = 1_000L,
          confirmedAtEpochMs = DAY_1_EPOCH_MS,
          updatedAtEpochMs = DAY_1_EPOCH_MS,
        ),
      ),
    )

    val search = service.search(
      context = context,
      query = "queue repair follow-up task",
      maxResults = 5,
    )

    assertEquals(1, search.matches.size)
    assertEquals("resolved-session", search.matches.single().recordId)
    assertEquals(MemoryStatus.RESOLVED, search.matches.single().status)
  }

  private fun memoryRecord(
    id: String,
    content: String,
    kind: String,
    scope: String,
    sourceSessionId: String,
    status: String = "active",
    workspaceId: String? = null,
    ttlMs: Long? = null,
    confirmedAtEpochMs: Long,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = content,
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    tags = listOf(
      "kind:$kind",
      "scope:$scope",
      "status:$status",
    ),
    extensions = mapOf(
      "kind" to kind,
      "scope" to scope,
      "status" to status,
      "source_session_id" to sourceSessionId,
      "last_confirmed_at_epoch_ms" to confirmedAtEpochMs.toString(),
    ) + listOfNotNull(
      workspaceId?.let { "workspace_id" to it },
      ttlMs?.let { "ttl_ms" to it.toString() },
    ).toMap(),
  )

  private companion object {
    const val DAY_1_EPOCH_MS: Long = 1_710_000_000_000L
    const val DAY_2_EPOCH_MS: Long = 1_710_086_400_000L
    const val NOW_EPOCH_MS: Long = 1_710_172_800_000L
  }
}
