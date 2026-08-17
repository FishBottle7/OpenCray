package com.opencray.app

import com.opencray.core.contracts.AgentTaskType
import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextInjectionPolicy
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillCatalogEntry
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.subagent.SubAgentChildSessionBootstrap
import com.opencray.runtime.subagent.SubAgentContextMode
import com.opencray.runtime.subagent.SubAgentTask
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateObjective
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactorySubAgentChildSessionBootstrapTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun prepareSessionContextUsesSubAgentChildSessionBootstrapInsteadOfNormalLiveContext() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-child-bootstrap-delegated"))
    val childSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-child-bootstrap-delegated").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val prompt = "Inspect the child session bootstrap transcript."
    val prepared = factory.prepareSessionContext(
      sessionId = childSessionId,
      workspaceId = "workspace-child-bootstrap",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-child-bootstrap",
      taskInput = prompt,
      transcriptStore = factory.transcriptStoreForSession(childSessionId),
      memoryRecords = emptyList(),
      liveContextMode = LiveContextMode.FULL,
      skillCatalog = injectedSkillCatalog(),
      subAgentChildSessionBootstrap = bootstrap(
        contextMode = SubAgentContextMode.DELEGATED,
        parentWorkingState = parentWorkingState(),
      ),
    )

    assertEquals("SAFE_PARENT_POLICY", prepared.sessionContext.sessionPolicyText)
    assertEquals(SubAgentContextMode.DELEGATED.wireValue, prepared.sessionContext.liveContextTrace.mode)
    assertEquals(true, prepared.sessionContext.liveContextTrace.soulEnabled)
    assertEquals(false, prepared.sessionContext.liveContextTrace.memoryRecallEnabled)
    assertTrue(prepared.sessionContext.recalledMemory.memories.isEmpty())
    assertTrue(prepared.sessionContext.memoryFlushTrace.isEmpty)
    assertTrue(!prepared.sessionContext.durableCompaction.included)
    assertEquals(BootstrapMode.LIGHTWEIGHT, prepared.sessionContext.bootstrapContext.mode)
    assertEquals(
      listOf("AGENTS.md", "PROJECT.md"),
      prepared.sessionContext.bootstrapContext.files.map(BootstrapSnippet::name),
    )
    assertEquals(0, prepared.sessionContext.skillInventory.visibleSkillCount)
    assertTrue(prepared.sessionContext.skillCatalog.skillsByName.isEmpty())
    assertEquals(2, prepared.sessionContext.conversation.size)
    assertEquals(RuntimeConversationRole.TOOL, prepared.sessionContext.conversation[0].role)
    assertTrue(
      prepared.sessionContext.conversation[0].content.contains(
        "Delegated parent context for this child run.",
      ),
    )
    assertTrue(
      prepared.sessionContext.conversation[0].content.contains(
        "user_goal=Audit the current repo layout.",
      ),
    )
    assertTrue(
      prepared.sessionContext.conversation[0].content.contains(
        "- Read file_path=README.md lines=1-4 limit=all truncated=false",
      ),
    )
    assertEquals(RuntimeConversationRole.USER, prepared.sessionContext.conversation[1].role)
    assertEquals(prompt, prepared.sessionContext.conversation[1].content)
    assertEquals(
      prepared.sessionContext.conversation,
      factory.transcriptStoreForSession(childSessionId).snapshot(),
    )
  }

  @Test
  fun prepareSessionContextPrefersChildWorkingStateOverBootstrapAfterChildSessionHasState() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-child-bootstrap-working-state"))
    val childSessionId = chatStore.loadState().activeSession.sessionId
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-child-bootstrap-working-state").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )
    val childWorkingState = WorkingState(
      objective = WorkingStateObjective(
        primaryGoal = "Persist the child session execution thread.",
        currentSubgoal = "Prefer the child session snapshot after the first run.",
        status = "in_progress",
      ),
    )
    factory.workingStateStoreForSession(childSessionId).replace(childWorkingState)
    val prompt = "Continue the child execution."
    val prepared = factory.prepareSessionContext(
      sessionId = childSessionId,
      workspaceId = "workspace-child-bootstrap-working-state",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = null,
      taskType = AgentTaskType.PROMPT,
      taskId = "task-child-bootstrap-working-state",
      taskInput = prompt,
      transcriptStore = factory.transcriptStoreForSession(childSessionId),
      memoryRecords = emptyList(),
      liveContextMode = LiveContextMode.FULL,
      skillCatalog = injectedSkillCatalog(),
      subAgentChildSessionBootstrap = bootstrap(
        contextMode = SubAgentContextMode.MINIMAL,
        parentWorkingState = WorkingState(
          objective = WorkingStateObjective(
            primaryGoal = "This parent working state should not win.",
            currentSubgoal = "Bootstrap only provides the initial default.",
            status = "blocked",
          ),
        ),
      ),
    )

    assertEquals(childWorkingState, prepared.sessionContext.workingState)
    assertEquals("SAFE_PARENT_POLICY", prepared.sessionContext.sessionPolicyText)
    assertEquals(SubAgentContextMode.MINIMAL.wireValue, prepared.sessionContext.liveContextTrace.mode)
    assertEquals(BootstrapMode.LIGHTWEIGHT, prepared.sessionContext.bootstrapContext.mode)
    assertEquals(0, prepared.sessionContext.skillInventory.visibleSkillCount)
    assertTrue(prepared.sessionContext.skillCatalog.skillsByName.isEmpty())
    assertEquals(1, prepared.sessionContext.conversation.size)
    assertEquals(RuntimeConversationRole.USER, prepared.sessionContext.conversation.single().role)
    assertEquals(prompt, prepared.sessionContext.conversation.single().content)
    assertEquals(
      prepared.sessionContext.conversation,
      factory.transcriptStoreForSession(childSessionId).snapshot(),
    )
  }

  private fun bootstrap(
    contextMode: SubAgentContextMode,
    parentWorkingState: WorkingState,
  ): SubAgentChildSessionBootstrap = SubAgentChildSessionBootstrap.fromParentSession(
    parentSessionId = "parent-session",
    parentSessionContext = parentSessionContext(parentWorkingState),
    childTask = SubAgentTask(
      description = "Inspect the repository bootstrap state.",
      prompt = "Read the relevant files and report back.",
      subagentType = "researcher",
      contextMode = contextMode,
      parentRunId = "run-parent",
      parentTaskId = "task-parent",
      parentTurn = 3,
    ),
    parentGoalSummary = "Audit the current repo layout.",
    parentObservationLines = listOf(
      "Read file_path=README.md lines=1-4 limit=all truncated=false",
      "LS path=runtime entries=3",
    ),
  )

  private fun parentSessionContext(
    parentWorkingState: WorkingState,
  ): AgentRuntimeSessionContext = AgentRuntimeSessionContext(
    sessionPolicyText = "SAFE_PARENT_POLICY",
    soulProfile = RuntimeSoulProfile(
      displayName = "OpenCray",
      customGuidance = "Stay concise and precise.",
    ),
    injectionPolicy = ContextInjectionPolicy(),
    memoryToolsEnabled = true,
    liveContextTrace = LiveContextTrace(
      mode = LiveContextMode.FULL.wireValue,
      soulEnabled = true,
      memoryRecallEnabled = true,
    ),
    bootstrapContext = BootstrapContext(
      mode = BootstrapMode.FULL,
      files = listOf(
        bootstrapSnippet("AGENTS.md"),
        bootstrapSnippet("PROJECT.md"),
        bootstrapSnippet("SOUL.md"),
      ),
      trace = BootstrapTrace(
        mode = BootstrapMode.FULL.wireValue,
        visibleFileCount = 3,
        injectedFileCount = 3,
        omittedFileCount = 0,
        truncatedFileCount = 0,
      ),
    ),
    workingState = parentWorkingState,
    skillInventory = injectedSkillCatalog().inventory,
    skillCatalog = injectedSkillCatalog(),
  )

  private fun parentWorkingState(): WorkingState = WorkingState(
    objective = WorkingStateObjective(
      primaryGoal = "Bootstrap the child session from the parent snapshot.",
      currentSubgoal = "Hand over the delegated repo context.",
      status = "in_progress",
    ),
  )

  private fun bootstrapSnippet(name: String): BootstrapSnippet {
    val content = "# $name\n\nShared guidance"
    return BootstrapSnippet(
      name = name,
      relativePath = name,
      content = content,
      sourceCharCount = content.length,
      truncated = false,
    )
  }

  private fun injectedSkillCatalog(): SkillCatalog {
    val inventory = SkillInventory(
      skills = listOf(
        VisibleSkill(
          name = "audit",
          description = "Audit repository changes.",
          relativePath = ".codex/skills/audit/SKILL.md",
          invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
          userInvocable = true,
          executionContext = SkillExecutionContext.INLINE,
        ),
      ),
    )
    return SkillCatalog(
      inventory = inventory,
      skillsByName = mapOf(
        "audit" to SkillCatalogEntry(
          name = "audit",
          description = "Audit repository changes.",
          relativePath = ".codex/skills/audit/SKILL.md",
          invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
          userInvocable = true,
          executionContext = SkillExecutionContext.INLINE,
          markdownBody = "# Audit\n\nFocus on regressions.",
        ),
      ),
    )
  }
}
