package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryDebugActionAuditStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun appendRetainsNewestEntriesWithinConfiguredBound() {
    val directory = temporaryFolder.newFolder("memory-debug-action-audit-store")
    val store = MemoryDebugActionAuditStore(directory, maxEntries = 2)

    store.append(
      auditEntry(
        entryId = "audit-1",
        occurredAtEpochMs = 100L,
      ),
    )
    store.append(
      auditEntry(
        entryId = "audit-2",
        occurredAtEpochMs = 200L,
      ),
    )
    store.append(
      auditEntry(
        entryId = "audit-3",
        occurredAtEpochMs = 300L,
      ),
    )

    val reloadedStore = MemoryDebugActionAuditStore(directory, maxEntries = 2)
    val audits = reloadedStore.list()

    assertEquals(listOf("audit-3", "audit-2"), audits.map { audit -> audit.entryId })
    assertTrue(audits.none { audit -> audit.entryId == "audit-1" })
  }

  private fun auditEntry(
    entryId: String,
    occurredAtEpochMs: Long,
  ): MemoryDebugActionAuditEntry = MemoryDebugActionAuditEntry(
    entryId = entryId,
    recordId = "memory-user",
    action = "suppress",
    sessionId = "session-1",
    runId = "run-memory-debug-$entryId",
    taskId = "memory-debug-suppress-$entryId",
    occurredAtEpochMs = occurredAtEpochMs,
  )
}
