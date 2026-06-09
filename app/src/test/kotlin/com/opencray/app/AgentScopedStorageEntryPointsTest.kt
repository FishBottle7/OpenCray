package com.opencray.app

import com.opencray.app.agent.AgentPathResolver
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.compaction.DurableCompactionState
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.process.ManagedProcessControllerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentScopedStorageEntryPointsTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun agentScopedWorkspaceAndLocalStoresResolveInsideAgentRoot() {
    val resolver = AgentPathResolver(temporaryFolder.newFolder("agent-scoped-local").toPath())
    val paths = resolver.resolve("agent-alpha")

    val workspaceRoot = AppAgentWorkspace.ensureRootForAgent(resolver, "agent-alpha")
    val chatStore = ChatSessionLocalStore.fromAgent(resolver, "agent-alpha")
    val activeSessionId = chatStore.loadState().activeSession.sessionId
    val personalizationStore = PersonalizationLocalStore.fromAgent(resolver, "agent-alpha")

    chatStore.appendUserMessage(activeSessionId, "Agent alpha transcript")
    personalizationStore.upsertMemoryRecord(
      MemoryRecord(
        id = "memory-1",
        content = "Keep replies concise.",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_001L,
      ),
    )

    assertEquals(paths.workspaceRoot, workspaceRoot)
    assertEquals(paths.chatLocalStateRoot.toFile(), ChatSessionLocalStore.directoryForAgent(resolver, "agent-alpha"))
    assertEquals(
      paths.personalizationLocalStateRoot.toFile(),
      PersonalizationLocalStore.directoryForAgent(resolver, "agent-alpha"),
    )
    assertTrue(paths.workspaceRoot.toFile().isDirectory)
    assertTrue(paths.chatLocalStateRoot.toFile().walkTopDown().any { entry -> entry.isFile })
    assertTrue(paths.personalizationLocalStateRoot.toFile().walkTopDown().any { entry -> entry.isFile })
  }

  @Test
  fun agentScopedRuntimeFactoriesCreateSessionArtifactsUnderDedicatedRoots() {
    val resolver = AgentPathResolver(temporaryFolder.newFolder("agent-scoped-runtime").toPath())
    val paths = resolver.resolve("agent-alpha")
    val sessionId = "session/root-check"

    val queueFactory = FileBackedAgentQueueSnapshotStoreFactory.fromAgent(resolver, "agent-alpha")
    val runRecordFactory = FileBackedAgentRunRecordStoreFactory.fromAgent(resolver, "agent-alpha")
    val transcriptFactory = FileBackedAgentSessionTranscriptStoreFactory.fromAgent(resolver, "agent-alpha")
    val supplementFactory = FileBackedAgentSessionSupplementStoreFactory.fromAgent(resolver, "agent-alpha")
    val compactionFactory = FileBackedAgentSessionCompactionStoreFactory.fromAgent(resolver, "agent-alpha")
    val processRegistryFactory = FileBackedAgentProcessRegistryFactory.fromAgent(
      pathResolver = resolver,
      agentId = "agent-alpha",
      controllerFactory = ManagedProcessControllerFactory { error("Process start should not run in this test.") },
    )

    queueFactory.forChatSession(sessionId)
    runRecordFactory.forChatSession(sessionId)
    transcriptFactory.forChatSession(sessionId).appendIfDistinct(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Rooted transcript",
      ),
    )
    supplementFactory.forChatSession(sessionId).append(
      runId = "run-1",
      taskId = "task-1",
      text = "Rooted supplement",
    )
    compactionFactory.forChatSession(sessionId).save(DurableCompactionState())
    processRegistryFactory.forChatSession(sessionId)

    assertTrue(queueFactory.directoryForSession(sessionId).toPath().startsWith(paths.queueSnapshotsRoot))
    assertTrue(runRecordFactory.directoryForSession(sessionId).toPath().startsWith(paths.runRecordsRoot))
    assertTrue(transcriptFactory.directoryForSession(sessionId).toPath().startsWith(paths.transcriptStoreRoot))
    assertTrue(
      supplementFactory.directoryForSession(sessionId).toPath().startsWith(paths.transcriptSupplementsRoot),
    )
    assertTrue(compactionFactory.directoryForSession(sessionId).toPath().startsWith(paths.compactionRoot))
    assertTrue(processRegistryFactory.directoryForSession(sessionId).toPath().startsWith(paths.processRegistryRoot))
    assertTrue(queueFactory.directoryForSession(sessionId).isDirectory)
    assertTrue(runRecordFactory.directoryForSession(sessionId).isDirectory)
    assertTrue(transcriptFactory.directoryForSession(sessionId).isDirectory)
    assertTrue(supplementFactory.directoryForSession(sessionId).isDirectory)
    assertTrue(compactionFactory.directoryForSession(sessionId).isDirectory)
    assertTrue(processRegistryFactory.directoryForSession(sessionId).isDirectory)
  }

  @Test
  fun differentAgentsResolveDifferentScopedRoots() {
    val resolver = AgentPathResolver(temporaryFolder.newFolder("agent-scoped-isolation").toPath())

    val alphaWorkspace = AppAgentWorkspace.directoryForAgent(resolver, "agent-alpha")
    val betaWorkspace = AppAgentWorkspace.directoryForAgent(resolver, "agent-beta")
    val alphaChat = ChatSessionLocalStore.directoryForAgent(resolver, "agent-alpha")
    val betaChat = ChatSessionLocalStore.directoryForAgent(resolver, "agent-beta")
    val alphaQueueRoot = FileBackedAgentQueueSnapshotStoreFactory.rootDirectoryForAgent(resolver, "agent-alpha")
    val betaQueueRoot = FileBackedAgentQueueSnapshotStoreFactory.rootDirectoryForAgent(resolver, "agent-beta")

    assertNotEquals(alphaWorkspace, betaWorkspace)
    assertNotEquals(alphaChat, betaChat)
    assertNotEquals(alphaQueueRoot, betaQueueRoot)
  }
}
