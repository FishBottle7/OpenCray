package com.opencray.app.agent

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentPathResolverTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun resolveBuildsExpectedStorageLayout() {
    val resolver = AgentPathResolver(temporaryFolder.root.toPath())

    val paths = resolver.resolve("agent-123")

    assertEquals(
      temporaryFolder.root.toPath().resolve("agents").resolve("agent-123").normalize(),
      paths.agentRoot,
    )
    assertEquals(paths.privateRoot.resolve("SOUL.md").normalize(), paths.privateSoulFile)
    assertEquals(paths.privateRoot.resolve("agent-config.json").normalize(), paths.privateConfigFile)
    assertEquals(paths.agentRoot.resolve("workspace").normalize(), paths.workspaceRoot)
    assertEquals(paths.agentRoot.resolve("chat-local-state").normalize(), paths.chatLocalStateRoot)
    assertEquals(
      paths.agentRoot.resolve("personalization-local-state").normalize(),
      paths.personalizationLocalStateRoot,
    )
  }

  @Test
  fun ensureAgentDirectoriesCreatesManagedDirectories() {
    val resolver = AgentPathResolver(temporaryFolder.root.toPath())

    val paths = resolver.ensureAgentDirectories("agent-abc")

    paths.managedDirectories.forEach { directory ->
      assertTrue(Files.isDirectory(directory))
    }
  }

  @Test
  fun resolveRejectsUnsafeAgentIds() {
    val resolver = AgentPathResolver(temporaryFolder.root.toPath())

    try {
      resolver.resolve("../bad")
      fail("Expected invalid agent id to be rejected.")
    } catch (_: IllegalArgumentException) {
    }
  }
}
