package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import org.junit.Assert.assertSame
import org.junit.Test

class OpenCrayRuntimeServiceHostTest {
  @Test
  fun runtimeOwnerAccessProjectsOnlyHostFacingRuntimeDependencies() {
    val lifecycleDescriptor = HostRuntimeLifecycleDescriptor()
    val runtimeManager = NoOpAgentSessionRuntimeManager()
    val runEventJournalStoreFactory = inMemoryRunEventJournalStoreFactory()
    val promptCheckpointStoreFactory = inMemoryPromptCheckpointStoreFactory()
    val supplementStoreFactory = object : AgentSessionSupplementStoreFactory {
      private val stores = linkedMapOf<String, SessionSupplementStore>()

      override fun forChatSession(sessionId: String): SessionSupplementStore =
        stores.getOrPut(sessionId) { InMemorySessionSupplementStore() }
    }
    val approvalRegistry = AgentTaskApprovalRegistry()
    val memoryIngestionCoordinator = ChatMemoryIngestionCoordinator(
      memoryStore = InMemoryMemoryStore(),
    )
    val replayAccess = OpenCrayRuntimeReplayAccess(
      approvalRejectionRecorder = { _, _, _, _, _ -> },
      approvalApprovedRecorder = { _, _, _, _, _ -> },
      subAgentReplayRecorder = { _, _ -> },
      runCancellationRecorder = { _, _, _, _ -> },
      terminalReplayRepairer = { _, _ -> },
    )
    val owner = InProcessOpenCrayRuntimeOwner(
      lifecycleDescriptor = lifecycleDescriptor,
      sessionRuntimeManager = runtimeManager,
      runEventJournalStoreFactory = runEventJournalStoreFactory,
      promptCheckpointStoreFactory = promptCheckpointStoreFactory,
      supplementStoreFactory = supplementStoreFactory,
      transcriptMessagesProvider = { emptyList() },
      approvalRegistry = approvalRegistry,
      memoryIngestionCoordinator = memoryIngestionCoordinator,
      replayAccess = replayAccess,
    )

    val access = owner.toRuntimeOwnerAccess()

    assertSame(lifecycleDescriptor, access.lifecycleDescriptor)
    assertSame(runtimeManager, access.sessionRuntimeManager)
    assertSame(runEventJournalStoreFactory, access.runEventJournalStoreFactory)
    assertSame(promptCheckpointStoreFactory, access.promptCheckpointStoreFactory)
    assertSame(supplementStoreFactory, access.supplementStoreFactory)
    assertSame(approvalRegistry, access.approvalRegistry)
    assertSame(memoryIngestionCoordinator, access.memoryIngestionCoordinator)
    assertSame(replayAccess, access.replayAccess)
  }

  private class NoOpAgentSessionRuntimeManager : AgentSessionRuntimeManager {
    override fun forSession(sessionId: String): AgentSessionHandle = error("unused in test")

    override fun observe(listener: AgentSessionRuntimeListener): () -> Unit = { }

    override fun release(sessionId: String) = Unit

    override fun releaseIdleSessions() = Unit
  }

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadEntries = records.isNotEmpty()
      records.clear()
      return hadEntries
    }
  }
}
