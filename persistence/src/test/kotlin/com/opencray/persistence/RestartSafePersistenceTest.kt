package com.opencray.persistence

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.model.SessionRecord
import com.opencray.persistence.model.SoulRecord
import com.opencray.persistence.store.file.JsonFileMemoryStore
import com.opencray.persistence.store.file.JsonFileSessionStore
import com.opencray.persistence.store.file.JsonFileSoulStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RestartSafePersistenceTest {
  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun sessionMemorySoulPersistAcrossRestart() {
    val root = tmp.newFolder("persistence")

    val soulStore1 = JsonFileSoulStore(directory = root)
    val sessionStore1 = JsonFileSessionStore(directory = root)
    val memoryStore1 = JsonFileMemoryStore(directory = root)

    val soul = SoulRecord(
      agentId = "agent-1",
      displayName = "OpenCray",
      createdAtEpochMs = 1_710_000_000_000,
      updatedAtEpochMs = 1_710_000_000_100,
    )
    soulStore1.save(soul)

    val session = SessionRecord(
      sessionId = "session-1",
      agentId = soul.agentId,
      state = mapOf("queue_state" to "idle"),
      createdAtEpochMs = 1_710_000_010_000,
      updatedAtEpochMs = 1_710_000_010_050,
    )
    sessionStore1.save(session)

    val memory = MemoryRecord(
      id = "mem-1",
      content = "Persistent memory survives restart",
      tags = listOf("core"),
      createdAtEpochMs = 1_710_000_020_000,
      updatedAtEpochMs = 1_710_000_020_000,
    )
    memoryStore1.upsert(memory)

    // Simulate app restart by creating new store instances.
    val soulStore2 = JsonFileSoulStore(directory = root)
    val sessionStore2 = JsonFileSessionStore(directory = root)
    val memoryStore2 = JsonFileMemoryStore(directory = root)

    assertEquals(soul, soulStore2.load())
    assertEquals(session, sessionStore2.load())
    assertEquals(listOf(memory), memoryStore2.list())

    // Migration/version metadata must exist in persisted JSON.
    assertContainsVersionMetadata(File(root, "soul.json"))
    assertContainsVersionMetadata(File(root, "session.json"))
    assertContainsVersionMetadata(File(root, "memory.json"))
  }

  private fun assertContainsVersionMetadata(file: File) {
    assertTrue("Expected persisted file to exist: ${file.path}", file.exists())
    val text = file.readText(Charsets.UTF_8)

    assertTrue(text.contains("\"schemaVersion\""))
    assertTrue(text.contains("\"migrationVersion\""))
    assertTrue(text.contains("\"termuxMetadataVersion\""))
    assertTrue(text.contains("\"termuxMetadata\""))
  }
}
