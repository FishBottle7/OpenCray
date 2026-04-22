package com.opencray.app.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveAgentScopeResolverTest {

  @Test
  fun loadActiveScopeReturnsDescriptorConfigAndResolvedPaths() {
    val filesRoot = Files.createTempDirectory("active-agent-scope")
    try {
      val pathResolver = AgentPathResolver(filesRoot)
      val registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()) { 2_000L }
      val configStore = AgentConfigStore(pathResolver)
      val bootstrapService = AgentBootstrapService(
        pathResolver = pathResolver,
        registryStore = registryStore,
        configStore = configStore,
        idFactory = AgentIdFactory(
          nowEpochMs = { 2_000L },
          randomToken = { "seed" },
        ),
        nowEpochMs = { 2_000L },
      )

      val created = bootstrapService.createAgent(
        AgentCreateRequest(
          displayName = "Nova",
          presetName = "builder",
          plasticity = "low",
          mode = "noSoul",
        ),
      )

      val scope = ActiveAgentScopeResolver(
        registryStore = registryStore,
        pathResolver = pathResolver,
        configStore = configStore,
      ).loadActiveScope()

      assertNotNull(scope)
      assertEquals(created.descriptor.agentId, scope?.descriptor?.agentId)
      assertEquals("Nova", scope?.descriptor?.displayName)
      assertEquals("noSoul", scope?.config?.mode)
      assertEquals(
        pathResolver.resolve(created.descriptor.agentId).workspaceRoot,
        scope?.storagePaths?.workspaceRoot,
      )
    } finally {
      deleteRecursively(filesRoot)
    }
  }

  @Test
  fun loadScopeReturnsNullWhenAgentIsMissing() {
    val filesRoot = Files.createTempDirectory("active-agent-scope-missing")
    try {
      val pathResolver = AgentPathResolver(filesRoot)
      val resolver = ActiveAgentScopeResolver(
        registryStore = AgentRegistryStore(pathResolver.registryDirectory().toFile()),
        pathResolver = pathResolver,
      )

      assertNull(resolver.loadActiveScope())
      assertNull(resolver.loadScope("agent-missing"))
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
