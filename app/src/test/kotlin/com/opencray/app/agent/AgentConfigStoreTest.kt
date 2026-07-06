package com.opencray.app.agent

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentConfigStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStoreRoundTripsThroughDurableStorage() {
    val root = temporaryFolder.newFolder("agent-config-round-trip").toPath()
    val pathResolver = AgentPathResolver(root)
    val store = AgentConfigStore(pathResolver)
    val config = agentConfig(agentId = "agent-config")

    store.save(agentId = "agent-config", config = config)

    val paths = pathResolver.resolve("agent-config")
    assertEquals(config, store.load("agent-config"))
    assertTrue(Files.isRegularFile(paths.privateConfigFile))
    assertTrue(Files.isRegularFile(paths.privateRoot.resolve("agent-config.json.lock")))
    assertTrue(store.clear("agent-config"))
    assertNull(store.load("agent-config"))
    assertFalse(Files.exists(paths.privateConfigFile))
  }

  @Test
  fun mutationsUseDurableUpdatePath() {
    val storage = UpdateOnlyAgentConfigTextStorage()
    val store = AgentConfigStore(
      pathResolver = AgentPathResolver(temporaryFolder.newFolder("agent-config-update").toPath()),
      storageFactory = { storage },
    )
    val config = agentConfig(agentId = "agent-update")

    store.save(agentId = "agent-update", config = config)
    store.save(
      agentId = "agent-update",
      config = config.copy(displayName = "Renamed", updatedAtEpochMs = 20L),
    )

    assertEquals("Renamed", store.load("agent-update")?.displayName)
    assertTrue(store.clear("agent-update"))
    assertFalse(store.clear("agent-update"))
    assertNull(store.load("agent-update"))
    assertEquals(4, storage.updateTextCallCount)
    assertEquals(0, storage.writeTextCallCount)
    assertEquals(0, storage.deleteCallCount)
  }

  private fun agentConfig(agentId: String): AgentConfig = AgentConfig(
    agentId = agentId,
    displayName = "Agent $agentId",
    presetName = "STEADY",
    plasticity = "medium",
    mode = "full",
    createdAtEpochMs = 10L,
    updatedAtEpochMs = 10L,
  )
}

private class UpdateOnlyAgentConfigTextStorage : DurableTextStorage {
  var updateTextCallCount: Int = 0
    private set
  var writeTextCallCount: Int = 0
    private set
  var deleteCallCount: Int = 0
    private set

  private var text: String? = null

  override fun readText(name: String): String? = text

  override fun writeText(name: String, text: String) {
    writeTextCallCount += 1
    error("Agent config mutations should use updateText.")
  }

  override fun delete(name: String): Boolean {
    deleteCallCount += 1
    error("Agent config mutations should use updateText.")
  }

  override fun <T> updateText(
    name: String,
    update: (String?) -> DurableTextUpdate<T>,
  ): T {
    updateTextCallCount += 1
    val updated = update(text)
    if (updated.write) {
      text = updated.text
    }
    return updated.result
  }
}
