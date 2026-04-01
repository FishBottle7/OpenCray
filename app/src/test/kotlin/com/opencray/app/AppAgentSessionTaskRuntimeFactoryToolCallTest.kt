package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.policy.SafetyAutomationMode
import com.opencray.policy.SafetySettingsMetadataKeys
import com.opencray.policy.ToolPolicyOverride
import com.opencray.runtime.NoOpOpenCrayAgentRuntimeEventSink
import com.opencray.runtime.subagent.SubAgentContinuationKind
import com.opencray.runtime.subagent.SubAgentExecutionSnapshot
import com.opencray.runtime.subagent.SubAgentExecutionState
import com.opencray.runtime.subagent.SubAgentHandleState
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.skills.SkillLoader
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryToolCallTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun toolCallTaskDoesNotRequireConfiguredLlm() {
    val workspaceRoot = temporaryFolder.newFolder("workspace").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call"))
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = chatStore.loadState().activeSession.sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-without-llm",
      type = AgentTaskType.TOOL_CALL,
      input =
        """{"type":"tool_call","tool_name":"TodoWrite","arguments":{"todos":[{"content":"Ship update entry","status":"in_progress"}]}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for direct tool call test.") },
      ),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.SUCCESS,
      result.status,
    )
    assertTrue(result.stdout.contains("Ship update entry"))
    assertEquals(1, runtimeFactory.todoStoreForSession(chatStore.loadState().activeSession.sessionId).snapshot().size)
  }

  @Test
  fun directWaitAgentCanUseDurableSessionHandlesWithoutCheckpointResumeState() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-tool-call-wait-agent").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call-wait-agent"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    runtimeFactory.subAgentHandleStoreForSession(sessionId).upsert(
      SubAgentHandleState(
        agentId = "child-durable",
        childRunId = "child-run-durable",
        childTaskId = "child-task-durable",
        description = "Inspect README",
        prompt = "Read README.md and summarize it.",
        subagentType = "researcher",
        contextMode = "minimal",
        parentRunId = "run-parent-old",
        parentTaskId = "task-parent-old",
        parentTurn = 1,
        depth = 1,
        snapshot = SubAgentExecutionSnapshot(
          state = SubAgentExecutionState.COMPLETED,
          continuationKind = SubAgentContinuationKind.NONE,
          resumable = false,
          requiresUserAction = false,
          isHighRisk = false,
          headline = "README says hello.",
        ),
        childExecutionStatus = ExecutionStatus.SUCCESS.name,
        childTurnCount = 1,
        childToolCallCount = 1,
        createdAtEpochMs = 900L,
        updatedAtEpochMs = 1_100L,
      ),
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "tool-call-wait-agent-durable",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"wait_agent","arguments":{"agent_id":"child-durable"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for durable wait_agent test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("README says hello."))
    assertEquals("child-durable", result.metadata["agentId"])
    assertEquals("child-run-durable", result.metadata["childRunId"])
    assertEquals("completed", result.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE])
  }

  @Test
  fun prepareSessionContextDoesNotAppendToolCallPayloadAsUserMessage() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-tool-call-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-tool-call-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val toolPayload =
      """{"type":"tool_call","tool_name":"SkillsFind","arguments":{"query":"android"}}"""

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-tool-call-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.TOOL_CALL,
      taskId = "tool-call-context",
      taskInput = toolPayload,
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    assertFalse(
      prepared.sessionContext.conversation.any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
    assertFalse(
      runtimeFactory.transcriptStoreForSession(sessionId).snapshot().any { message ->
        message.role == RuntimeConversationRole.USER && message.content == toolPayload
      },
    )
  }

  @Test
  fun prepareSessionContextCanSkipAppendingPromptInputDuringApprovalResume() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-prompt-resume-context").toPath()
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-prompt-resume-context"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    chatStore.appendMessage(
      sessionId = sessionId,
      role = com.opencray.persistence.model.ChatTranscriptRole.USER,
      text = "Write the note.",
    )
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val prepared = runtimeFactory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-prompt-resume-context",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "prompt-resume-context",
      taskInput = "Write the note.",
      transcriptStore = runtimeFactory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
      appendTaskInputToTranscript = false,
    )

    assertEquals(
      1,
      prepared.sessionContext.conversation.count { message ->
        message.role == RuntimeConversationRole.USER && message.content == "Write the note."
      },
    )
  }

  @Test
  fun hostUiPreapprovedToolCallAllowsMatchingToolWithoutChatApproval() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-preapproved").toPath()
    val targetFile = workspaceRoot.resolve("delete-me.txt")
    targetFile.toFile().writeText("remove")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-host-ui-preapproved"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-delete",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"workspace_delete_file","arguments":{"path":"delete-me.txt"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = mapOf(
        SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID to ToolPolicyOverride.ASK.wireValue,
        RunLifecycleMetadataKeys.SUBMISSION_SOURCE to RunSubmissionSources.HOST_UI_TOOL_ACTION,
        RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME to "workspace_delete_file",
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui preapproval test.") },
      ),
    )

    assertEquals(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      ExecutionStatus.SUCCESS,
      result.status,
    )
    assertFalse(Files.exists(targetFile))
    assertEquals("USER_APPROVED_RETRY", result.metadata["policyReasonCode"])
  }

  @Test
  fun hostUiPreapprovedToolCallRequiresExactToolMatch() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-mismatch").toPath()
    val targetFile = workspaceRoot.resolve("delete-me.txt")
    targetFile.toFile().writeText("remove")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-host-ui-mismatch"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-delete-mismatch",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"workspace_delete_file","arguments":{"path":"delete-me.txt"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = mapOf(
        SafetySettingsMetadataKeys.FILE_DELETES_POLICY_ID to ToolPolicyOverride.ASK.wireValue,
        RunLifecycleMetadataKeys.SUBMISSION_SOURCE to RunSubmissionSources.HOST_UI_TOOL_ACTION,
        RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME to "workspace_write_file",
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui mismatch test.") },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertTrue(Files.exists(targetFile))
  }

  @Test
  fun hostUiPreapprovedSkillsAddAllowsNestedApprovalGates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-add").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Installs from an external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        External install fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-add")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-add"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val sourceRef = jsonPath(sourceRoot)
    val task = AgentTask(
      id = "host-ui-preapproved-skills-add",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsAdd","arguments":{"sourceRef":"$sourceRef","skill":"find-skills"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsAdd",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills add test.") },
      ),
    )

    assertTrue(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      result.status == ExecutionStatus.SUCCESS,
    )
    assertEquals("USER_APPROVED_RETRY", result.metadata["policyReasonCode"])
    assertTrue(Files.exists(packageManager.managedRootPath().toPath().resolve("find-skills").resolve("SKILL.md")))
  }

  @Test
  fun hostUiPreapprovedSkillsFindAllowsNestedNetworkGate() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-find").toPath()
    val packageManager = createSkillPackageManager("skills-find")
    writeSkill(
      root = packageManager.catalogRootPath(),
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Search fixture from catalog.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        Catalog search fixture.
      """.trimIndent(),
    )
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-find"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-skills-find",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsFind","arguments":{"query":"find","max_results":4}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsFind",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills find test.") },
      ),
    )

    assertTrue(
      "status=${result.status} errorCode=${result.errorCode} errorMessage=${result.errorMessage} stdout=${result.stdout} stderr=${result.stderr}",
      result.status == ExecutionStatus.SUCCESS,
    )
    assertTrue(result.stdout.contains("find-skills"))
  }

  @Test
  fun hostUiPreapprovedSkillsInspectAllowsNestedLocalReadGate() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-inspect").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-inspect-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Inspect fixture from external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        Inspect fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-inspect")
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-inspect"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val sourceRef = jsonPath(sourceRoot)
    val task = AgentTask(
      id = "host-ui-preapproved-skills-inspect",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsInspect","arguments":{"sourceRef":"$sourceRef"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsInspect",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills inspect test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("inspection"))
    assertTrue(result.stdout.contains("find-skills"))
  }

  @Test
  fun hostUiPreapprovedSkillsUpdateAllowsNestedApprovalGates() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-host-ui-skills-update").toPath()
    val sourceRoot = temporaryFolder.newFolder("external-skill-update-source")
    writeSkill(
      root = sourceRoot,
      relativeDirectory = "find-skills",
      frontMatter = """
        name: find-skills
        description: Updates from an external source.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read ]
      """.trimIndent(),
      body = """
        # Find Skills

        External update fixture.
      """.trimIndent(),
    )
    val packageManager = createSkillPackageManager("skills-update")
    val sourceReport = SkillLoader.load(sourceRoot)
    assertEquals(
      "invalid=${sourceReport.invalidSkills.map { diagnostic -> diagnostic.detail }}",
      listOf("find-skills"),
      sourceReport.loadedSkills.map { skill -> skill.name },
    )
    val installAttempt = packageManager.installFromLocalSource(
      sourcePath = sourceRoot,
      sourceRef = sourceRoot.invariantSeparatorsPath,
      selectedSkillName = "find-skills",
    )
    assertNotNull("errorCode=${installAttempt.errorCode} errorMessage=${installAttempt.errorMessage}", installAttempt.result)

    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-skills-update"))
    val sessionId = chatStore.loadState().activeSession.sessionId
    val runtimeFactory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      safetySettingsProvider = { SafetySettingsState(automationMode = SafetyAutomationMode.SAFE) },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      readRootsProvider = { setOf(workspaceRoot, sourceRoot.toPath()) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      skillPackageManagerProvider = { packageManager },
    )
    val runtime = runtimeFactory.create(
      sessionId = sessionId,
      eventSink = NoOpOpenCrayAgentRuntimeEventSink,
    )
    val task = AgentTask(
      id = "host-ui-preapproved-skills-update",
      type = AgentTaskType.TOOL_CALL,
      input = """{"type":"tool_call","tool_name":"SkillsUpdate","arguments":{"skillId":"find-skills"}}""",
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.ALLOW,
        reasonCode = "TEST_ALLOW",
      ),
      createdAtEpochMs = 1_000L,
      metadata = hostUiPreapprovedTaskMetadata(
        toolName = "SkillsUpdate",
        automationMode = SafetyAutomationMode.SAFE,
      ),
    )

    val result = runtime.execute(
      task,
      RuntimeExecutionHooks(
        isCancellationRequested = { false },
        requestRetry = { _ -> error("Retry was not expected for host-ui skills update test.") },
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertTrue(result.stdout.contains("find-skills"))
  }

  private fun createSkillPackageManager(name: String): SkillPackageManager = SkillPackageManager(
    managedRoot = temporaryFolder.newFolder("$name-managed"),
    catalogRoot = temporaryFolder.newFolder("$name-catalog"),
    manifestStore = SkillInstallManifestStore.fromFile(
      temporaryFolder.newFile("$name-manifest.json"),
    ),
  )

  private fun writeSkill(
    root: File,
    relativeDirectory: String,
    frontMatter: String,
    body: String,
  ): File {
    val skillDirectory = root.resolve(relativeDirectory)
    Files.createDirectories(skillDirectory.toPath())
    val skillFile = skillDirectory.resolve("SKILL.md")
    val content = buildString {
      appendLine("---")
      appendLine(frontMatter)
      appendLine("---")
      appendLine(body)
    }
    Files.write(skillFile.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    return skillFile
  }

  private fun jsonPath(file: File): String = file.invariantSeparatorsPath.replace("/", "\\/")

  private fun hostUiPreapprovedTaskMetadata(
    toolName: String,
    automationMode: SafetyAutomationMode? = null,
  ): Map<String, String> = buildMap {
    put(RunLifecycleMetadataKeys.SUBMISSION_SOURCE, RunSubmissionSources.HOST_UI_TOOL_ACTION)
    put(RunLifecycleMetadataKeys.PREAPPROVED_TOOL_NAME, toolName)
    automationMode?.let { mode ->
      put(SafetySettingsMetadataKeys.CHAT_MODE, mode.chatMetadataLabel)
      put(SafetySettingsMetadataKeys.EXECUTION_MODE, mode.executionMode.name)
    }
  }
}
