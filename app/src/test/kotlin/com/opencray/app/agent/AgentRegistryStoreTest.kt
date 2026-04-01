package com.opencray.app.agent

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
}
