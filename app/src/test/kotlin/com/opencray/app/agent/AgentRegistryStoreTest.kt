package com.opencray.app.agent

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentRegistryStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createSelectAndArchiveMaintainSingleActiveAgent() {
    var now = 100L
    val store = AgentRegistryStore(
      directory = temporaryFolder.newFolder("registry-create-select"),
      nowEpochMs = { now++ },
    )
    val first = AgentDescriptor(
      agentId = "agent-first",
      displayName = "First",
      createdAtEpochMs = 10L,
      updatedAtEpochMs = 10L,
      presetName = "STEADY",
      plasticity = "medium",
      activeSessionId = "session-first",
    )
    val second = AgentDescriptor(
      agentId = "agent-second",
      displayName = "Second",
      createdAtEpochMs = 20L,
      updatedAtEpochMs = 20L,
      presetName = "WARM",
      plasticity = "high",
      activeSessionId = "session-second",
    )

    store.create(first, makeActive = false)
    store.create(second, makeActive = false)

    assertEquals("agent-first", store.activeAgentId())

    store.select("agent-second")

    assertEquals("agent-second", store.activeAgentId())
    assertEquals("agent-second", store.loadActiveAgent()?.agentId)

    store.archive("agent-second")

    assertEquals("agent-first", store.activeAgentId())
    assertEquals(1, store.list().size)
    assertEquals(2, store.list(includeArchived = true).size)
    assertTrue(store.loadAgent("agent-second")?.isArchived == true)
  }

  @Test
  fun updatePreservesCreatedAtAndCanPromoteAgentToActive() {
    var now = 200L
    val store = AgentRegistryStore(
      directory = temporaryFolder.newFolder("registry-update"),
      nowEpochMs = { now++ },
    )
    val original = AgentDescriptor(
      agentId = "agent-original",
      displayName = "Original",
      createdAtEpochMs = 12L,
      updatedAtEpochMs = 12L,
      presetName = "STEADY",
      plasticity = "low",
      activeSessionId = "session-original",
    )

    store.create(original, makeActive = false)
    val updated = store.update(
      descriptor = original.copy(
        displayName = "Renamed",
        createdAtEpochMs = 999L,
        updatedAtEpochMs = 999L,
        plasticity = "high",
      ),
      makeActive = true,
    )

    val persisted = requireNotNull(store.loadAgent("agent-original"))
    assertEquals("Renamed", persisted.displayName)
    assertEquals(12L, persisted.createdAtEpochMs)
    assertEquals("high", persisted.plasticity)
    assertEquals("agent-original", updated.activeAgentId)
    assertFalse(persisted.isArchived)
  }

  @Test
  fun mutationsUseAtomicStorageUpdatePath() {
    var now = 300L
    val storage = UpdateOnlyAgentRegistryTextStorage()
    val store = AgentRegistryStore(
      directory = temporaryFolder.newFolder("registry-update-path"),
      nowEpochMs = { now++ },
      storage = storage,
    )
    val first = agentDescriptor("agent-first", displayName = "First")
    val second = agentDescriptor("agent-second", displayName = "Second")

    store.create(first, makeActive = false)
    store.create(second, makeActive = false)
    store.update(second.copy(displayName = "Second renamed"), makeActive = true)
    store.select("agent-second")
    store.archive("agent-second")

    assertEquals("agent-first", store.activeAgentId())
    assertEquals("Second renamed", store.loadAgent("agent-second")?.displayName)
    assertTrue(store.loadAgent("agent-second")?.isArchived == true)
    assertEquals(5, storage.updateTextCallCount)
    assertEquals(4, storage.appliedWriteCount)
    assertEquals(0, storage.writeTextCallCount)
    assertTrue(storage.deletedNames.isEmpty())
  }

  private fun agentDescriptor(
    agentId: String,
    displayName: String,
  ): AgentDescriptor = AgentDescriptor(
    agentId = agentId,
    displayName = displayName,
    createdAtEpochMs = 30L,
    updatedAtEpochMs = 30L,
    presetName = "STEADY",
    plasticity = "medium",
    activeSessionId = "session-$agentId",
  )
}

private class UpdateOnlyAgentRegistryTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var appliedWriteCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  val deletedNames = mutableListOf<String>()

  private var textByName = linkedMapOf<String, String>()

  override fun readText(name: String): String? = textByName[name]

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Agent registry mutations should use updateText.")
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
      appliedWriteCount += 1
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
