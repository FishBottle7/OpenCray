package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.soul.SoulTurnUserAffect
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryTurnPolicyTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun prepareSessionContextCarriesTurnSignalIntoPromptAssembly() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-turn-policy"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-turn-policy").toPath()
    val sessionId = chatStore.loadState().activeSession.sessionId
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
      soulTurnSemanticSignalInterpreter = FixedSoulTurnSemanticSignalInterpreter(
        SoulTurnSemanticSignal(
          isTaskBearingRequest = true,
          userAffect = SoulTurnUserAffect.STRAINED,
          clarificationNeeded = true,
        ),
      ),
    )

    val prepared = factory.prepareSessionContext(
      sessionId = sessionId,
      workspaceId = "workspace-turn-policy",
      visibleThroughMessageId = null,
      excludedMessageIds = emptySet(),
      soulProfile = WorkspaceSoulProfile(
        presetName = "WARM",
        customLabel = "Night Shift",
        customGuidance = "Stay grounded.",
        extensions = mapOf(
          SoulProfileExtensionKeys.INITIATIVE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.REASSURANCE_PREFERENCE_OFFSET to "1",
          SoulProfileExtensionKeys.SUPPORTIVE_REASSURANCE_ALLOWED to "true",
          SoulProfileExtensionKeys.PROACTIVE_RELATIONAL_CHECK_IN_ALLOWED to "true",
        ),
      ),
      taskType = AgentTaskType.PROMPT,
      taskId = "task-turn-policy",
      taskInput = "这个错误有点急，先帮我判断缺什么信息。",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = emptyList(),
    )

    val prompt = PromptAssembler().assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask("这个错误有点急，先帮我判断缺什么信息。"),
          baseSystemPrompt = "Base identity.",
          sessionContext = prepared.sessionContext,
          toolDefinitions = emptyList(),
          liveConversation = prepared.sessionContext.conversation,
        ),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Turn Response Policy]"))
    assertTrue(prompt.systemPrompt.contains("task_priority=task_first"))
    assertTrue(prompt.systemPrompt.contains("response_shape=short_support_then_answer"))
    assertTrue(prompt.systemPrompt.contains("clarification_mode=proactive_task_focused"))
  }

  private fun promptTask(input: String): AgentTask = AgentTask(
    id = "task-turn-policy",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 1_000L,
  )

  private class FixedSoulTurnSemanticSignalInterpreter(
    private val signal: SoulTurnSemanticSignal,
  ) : SoulTurnSemanticSignalInterpreter {
    override fun interpret(
      request: SoulTurnSemanticSignalRequest,
    ): SoulTurnSemanticSignalInterpretation = SoulTurnSemanticSignalInterpretation.Success(signal)
  }
}
