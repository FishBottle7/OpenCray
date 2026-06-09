package com.opencray.app

import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE_TEST: String =
  "PROCESS_INTERRUPTED_ON_RESTORE"
private const val METADATA_RESTORED_TERMINAL_STATE_TEST: String = "restoredTerminalState"
private const val RESTORED_TERMINAL_STATE_INTERRUPTED_TEST: String = "interrupted"

class AppAgentSessionTaskRuntimeFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun readManagedProcessUsesRegistryReadWhenProcessDropsOutOfList() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-process-read"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-process-read").toPath()
    val archivedSnapshot = ManagedProcessSnapshot(
      processId = "proc-archived",
      taskId = "task-archived",
      command = "npm",
      args = listOf("run", "dev"),
      workingDirectory = ".",
      status = ManagedProcessStatus.FAILED,
      processStarted = true,
      timeoutMs = 120_000L,
      errorCode = ERROR_MANAGED_PROCESS_INTERRUPTED_ON_RESTORE_TEST,
      errorMessage = "Archived managed process restore.",
      startedAtEpochMs = 1_000L,
      updatedAtEpochMs = 1_005L,
      finishedAtEpochMs = 1_005L,
      metadata = mapOf(
        METADATA_RESTORED_TERMINAL_STATE_TEST to RESTORED_TERMINAL_STATE_INTERRUPTED_TEST,
      ),
    )
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      processRegistryProvider = {
        object : AgentProcessRegistry {
          override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot =
            error("unused in test")

          override fun list(): List<ManagedProcessSnapshot> = emptyList()

          override fun read(processId: String): ManagedProcessSnapshot? =
            archivedSnapshot.takeIf { snapshot -> snapshot.processId == processId }

          override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? = null

          override fun terminate(processId: String): ManagedProcessSnapshot? = null
        }
      },
    )

    assertTrue(factory.listManagedProcesses("session-archived").isEmpty())
    assertSame(
      archivedSnapshot,
      factory.readManagedProcess("session-archived", "proc-archived"),
    )
    assertNull(factory.readManagedProcess("session-archived", "proc-missing"))
  }
}
