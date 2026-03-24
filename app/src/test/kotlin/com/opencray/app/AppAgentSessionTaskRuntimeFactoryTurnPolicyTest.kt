package com.opencray.app

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.context.ContextManager
import com.opencray.runtime.context.PromptAssembler
import com.opencray.runtime.context.PromptAssemblyInput
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.soul.InteractionPreferenceState
import com.opencray.runtime.soul.PreferenceAxisState
import com.opencray.runtime.soul.RelationshipState
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpretation
import com.opencray.runtime.soul.SoulTurnSemanticSignalInterpreter
import com.opencray.runtime.soul.SoulTurnSemanticSignalRequest
import com.opencray.runtime.soul.SoulTurnUserAffect
import com.opencray.runtime.soul.buildInteractionPreferenceStateMemoryExtensions
import com.opencray.runtime.soul.buildRelationshipStateMemoryExtensions
import org.junit.Assert.assertEquals
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

  @Test
  fun prepareSessionContextUsesMemoryBackedSoulOverlayForAddressStyleAndTurnPolicy() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-turn-policy-memory"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root-turn-policy-memory").toPath()
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
          isTaskBearingRequest = false,
          userAffect = SoulTurnUserAffect.PLAYFUL,
          userInvitesPlayfulness = true,
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
        customGuidance = "",
        extensions = mapOf(
          SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE to "neutral",
          SoulProfileExtensionKeys.PLASTICITY to "medium",
        ),
      ),
      taskType = AgentTaskType.PROMPT,
      taskId = "task-turn-policy-memory",
      taskInput = "那就轻松点吧。",
      transcriptStore = factory.transcriptStoreForSession(sessionId),
      memoryRecords = listOf(
        interactionPreferenceStateRecord(
          id = "interaction-open",
          scope = MemoryScope.USER,
          state = InteractionPreferenceState(
            initiative = PreferenceAxisState(offset = 1, higherSupport = 2),
            playfulness = PreferenceAxisState(offset = 1, higherSupport = 2),
            reassurance = PreferenceAxisState(offset = 1, higherSupport = 2),
          ),
          updatedAtEpochMs = 1_000L,
        ),
        relationshipStateRecord(
          id = "relationship-open",
          scope = MemoryScope.USER,
          state = RelationshipState(
            familiarity = 70,
            trust = 76,
            safety = 78,
            intimacyPermission = 61,
            playfulnessPermission = 44,
            affectionTendency = 34,
            reciprocity = 50,
          ),
          updatedAtEpochMs = 1_100L,
        ),
      ),
    )

    val effectiveSoul = checkNotNull(prepared.sessionContext.soulProfile)
    assertEquals("intimate", effectiveSoul.extensions[SoulProfileExtensionKeys.PREFERRED_ADDRESS_STYLE])
    assertEquals("true", effectiveSoul.extensions[SoulProfileExtensionKeys.HIGH_INTIMACY_BEHAVIOR_ALLOWED])

    val prompt = PromptAssembler().assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask("那就轻松点吧。"),
          baseSystemPrompt = "Base identity.",
          sessionContext = prepared.sessionContext,
          toolDefinitions = emptyList(),
          liveConversation = prepared.sessionContext.conversation,
        ),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("preferred_address_style=intimate"))
    assertTrue(prompt.systemPrompt.contains("[Turn Response Policy]"))
    assertTrue(prompt.systemPrompt.contains("playfulness_mode=light_teasing_allowed"))
    assertTrue(prompt.systemPrompt.contains("intimacy_mode=contextual_only"))
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

  private fun relationshipStateRecord(
    id: String,
    scope: MemoryScope,
    state: RelationshipState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal relationship snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + buildRelationshipStateMemoryExtensions(state),
  )

  private fun interactionPreferenceStateRecord(
    id: String,
    scope: MemoryScope,
    state: InteractionPreferenceState,
    updatedAtEpochMs: Long,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "internal interaction preference snapshot",
    createdAtEpochMs = updatedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = mapOf(
      MemoryRecordExtensionKeys.SCOPE to scope.name.lowercase(),
      MemoryRecordExtensionKeys.STATUS to MemoryStatus.ACTIVE.name.lowercase(),
      MemoryRecordExtensionKeys.LAST_CONFIRMED_AT_EPOCH_MS to updatedAtEpochMs.toString(),
    ) + buildInteractionPreferenceStateMemoryExtensions(state),
  )
}
