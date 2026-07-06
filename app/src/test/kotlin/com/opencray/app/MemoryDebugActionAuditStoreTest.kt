package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
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

  @Test
  fun appendUsesAtomicStorageUpdatePath() {
    val storage = UpdateOnlyMemoryAuditTextStorage()
    val store = MemoryDebugActionAuditStore(
      directory = temporaryFolder.newFolder("memory-debug-action-audit-update-path"),
      maxEntries = 2,
      storage = storage,
    )

    store.append(auditEntry(entryId = "audit-alpha", occurredAtEpochMs = 100L))
    store.append(auditEntry(entryId = "audit-beta", occurredAtEpochMs = 200L))

    assertEquals(listOf("audit-beta", "audit-alpha"), store.list().map { audit -> audit.entryId })
    assertEquals(2, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertTrue(storage.deletedNames.isEmpty())
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

private class UpdateOnlyMemoryAuditTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Memory debug action audit store should append through updateText.")
  }

  override fun delete(name: String): Boolean {
    deletedNames += name
    return textByName.remove(name) != null
  }

  override fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    updateTextCallCount += 1
    val updated = update(textByName[name])
    if (updated.write) {
      val updatedText = updated.text
      if (updatedText == null) {
        textByName.remove(name)
      } else {
        textByName[name] = updatedText
      }
    }
    return updated.result
  }
}
