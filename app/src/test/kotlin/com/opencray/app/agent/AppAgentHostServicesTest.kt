package com.opencray.app.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAgentHostServicesTest {

  @Test
  fun createListAndSelectAgentExposeHostSnapshots() {
    val filesRoot = Files.createTempDirectory("app-agent-host-services-test")
    try {
      val pathResolver = AgentPathResolver(filesRoot)
      val registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()) { 2_000L }
      val configStore = AgentConfigStore(pathResolver)
      val bootstrapService = AgentBootstrapService(
        pathResolver = pathResolver,
        registryStore = registryStore,
        idFactory = AgentIdFactory(
          nowEpochMs = { 2_000L },
          randomToken = { "seed" },
        ),
        configStore = configStore,
        nowEpochMs = { 2_000L },
      )
      val services = AppAgentHostServices(
        registryStore = registryStore,
        bootstrapService = bootstrapService,
        configStore = configStore,
      )

      val createdNova = services.createAgent(
        mapOf(
          "displayName" to "Nova",
          "presetName" to "builder",
          "plasticity" to "low",
          "mode" to "noSoul",
          "baseDescription" to "Execution-focused agent.",
          "llm" to mapOf(
            "provider" to "anthropic",
            "protocol" to "anthropic",
            "model" to "claude-3-7-sonnet",
          ),
        ),
      )
      val novaId = createdNova["agentId"] as String
      assertEquals("Nova", createdNova["displayName"])
      assertEquals("builder", createdNova["presetName"])
      assertEquals("noSoul", createdNova["mode"])
      assertEquals(true, createdNova["isActive"])

      val createdQuarry = services.createAgent(
        mapOf(
          "displayName" to "Quarry",
          "presetName" to "steady",
          "plasticity" to "medium",
          "mode" to "lightweight",
          "activateOnCreate" to false,
        ),
      )
      val quarryId = createdQuarry["agentId"] as String
      assertEquals(false, createdQuarry["isActive"])

      val listedAgents = services.listAgents()
      assertEquals(2, listedAgents.size)
      assertTrue(listedAgents.any { agent ->
        agent["agentId"] == novaId && agent["isActive"] == true
      })
      assertTrue(listedAgents.any { agent ->
        agent["agentId"] == quarryId && agent["isActive"] == false
      })

      val activeBeforeSelect = services.loadActiveAgent()
      assertNotNull(activeBeforeSelect)
      assertEquals(novaId, activeBeforeSelect?.get("agentId"))

      val selected = services.selectAgent(quarryId)
      assertNotNull(selected)
      assertEquals(quarryId, selected?.get("agentId"))
      assertEquals(true, selected?.get("isActive"))

      val activeAfterSelect = services.loadActiveAgent()
      assertNotNull(activeAfterSelect)
      assertEquals(quarryId, activeAfterSelect?.get("agentId"))

      val listedAfterSelect = services.listAgents()
      assertTrue(listedAfterSelect.any { agent ->
        agent["agentId"] == quarryId && agent["isActive"] == true
      })
      assertFalse(listedAfterSelect.any { agent ->
        agent["agentId"] == novaId && agent["isActive"] == true
      })
    } finally {
      deleteRecursively(filesRoot)
    }
  }

  private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) {
      return
    }
    Files.walk(root).use { stream ->
      stream
        .sorted(Comparator.reverseOrder())
        .forEach { path -> Files.deleteIfExists(path) }
    }
  }
}
