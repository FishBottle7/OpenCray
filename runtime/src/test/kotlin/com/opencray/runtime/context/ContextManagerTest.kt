package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.AgentTodoEntry
import com.opencray.runtime.AgentTodoStatus
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnUserAffect
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import com.opencray.runtime.workingstate.WorkingStateResumeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
  @Test
  fun prepareSelectsTranscriptSoulAndBoundedMemoryBeforePromptRendering() {
    val manager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 48,
        ),
      ),
      config = ContextManagerConfig(
        maxInjectedMemoryRecords = 1,
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          sessionPolicyText = "Keep the session coherent.",
          soulProfile = RuntimeSoulProfile(
            presetName = "BUILDER",
            displayName = "Night Shift",
            customGuidance = "Be terse and implementation-first.",
          ),
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-user",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Default to concise Chinese replies.",
                lastConfirmedAtEpochMs = 10L,
                score = 420,
              ),
              RetrievedMemory(
                id = "memory-project",
                kind = MemoryKind.PROJECT_FACT,
                scope = MemoryScope.WORKSPACE,
                status = MemoryStatus.ACTIVE,
                content = "Project uses the Gradle wrapper from the repo root.",
                lastConfirmedAtEpochMs = 11L,
                score = 360,
              ),
            ),
            matchedRecordCount = 2,
            omittedRecordCount = 0,
            trace = MemoryRecallTrace(
              queryTerms = listOf("chinese", "gradle"),
              selected = listOf(
                MemoryRecallSelectedTrace(
                  id = "memory-user",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  score = 420,
                  matchedTerms = listOf("chinese"),
                  contentPreview = "Default to concise Chinese replies.",
                ),
                MemoryRecallSelectedTrace(
                  id = "memory-project",
                  kind = MemoryKind.PROJECT_FACT,
                  scope = MemoryScope.WORKSPACE,
                  score = 360,
                  matchedTerms = listOf("gradle"),
                  contentPreview = "Project uses the Gradle wrapper from the repo root.",
                ),
              ),
            ),
          ),
        ),
        toolDefinitions = listOf(
          AgentToolDefinition(
            name = "Read",
            description = "Read a file from the workspace.",
          ),
        ),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Older request."),
          RuntimeConversationMessage(
            RuntimeConversationRole.ASSISTANT,
            "This assistant message is intentionally long so the transcript window has to truncate it before rendering.",
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Latest request."),
        ),
      ),
    )

    assertEquals("You are OpenCray for testing.", managed.baseSystemPrompt)
    assertEquals("Keep the session coherent.", managed.sessionPolicyText)
    assertTrue(managed.personalizationText.contains("display_name=Night Shift"))
    assertTrue(managed.memoryText.contains("Default to concise Chinese replies."))
    assertFalse(managed.memoryText.contains("Project uses the Gradle wrapper from the repo root."))
    assertEquals(
      "Compacted 1 older message(s) outside the active transcript window.",
      managed.compactionSummary?.text?.lineSequence()?.first(),
    )
    assertEquals(3, managed.report.sourceTranscriptMessageCount)
    assertEquals(2, managed.report.windowedTranscriptMessageCount)
    assertEquals(1, managed.report.omittedTranscriptMessageCount)
    assertEquals(1, managed.report.truncatedTranscriptMessageCount)
    assertEquals(0, managed.report.prunedTranscriptMessageCount)
    assertEquals(0, managed.report.rewrittenTranscriptMessageCount)
    assertFalse(managed.report.pruningSummaryIncluded)
    assertEquals(1, managed.report.compactedTranscriptMessageCount)
    assertTrue(managed.report.compactionSummaryIncluded)
    assertEquals(2, managed.report.matchedMemoryRecordCount)
    assertEquals(1, managed.report.injectedMemoryRecordCount)
    assertEquals(1, managed.report.omittedMemoryRecordCount)
    assertEquals(listOf("memory-user"), managed.report.memoryRecallTrace.selected.map { trace -> trace.id })
    assertEquals(listOf("memory-project"), managed.report.memoryRecallTrace.omitted.map { trace -> trace.id })
  }

  @Test
  fun prepareBuildsCompactionSummaryForOmittedToolActivity() {
    val manager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 120,
        ),
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Search the repo."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.ASSISTANT,
            content = """{"tool_call_id":"call-1","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
            kind = RuntimeConversationMessageKind.TOOL_CALL,
            toolCall = RuntimeConversationToolCall(
              id = "call-1",
              toolName = "Read",
            ),
          ),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"intro"}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolCallId = "call-1",
              toolName = "Read",
              status = "success",
              isError = false,
            ),
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "What did you find?"),
        ),
      ),
    )

    val summary = requireNotNull(managed.compactionSummary)

    assertEquals(2, summary.compactedMessageCount)
    assertEquals(1, summary.omittedAssistantMessageCount)
    assertEquals(1, summary.omittedToolMessageCount)
    assertTrue(summary.text.contains("Omitted tool activity: discovery=2."))
  }

  @Test
  fun prepareCarriesStructuredRecentToolObservationLayer() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"README intro","metadata":{"filePath":"README.md","offset":"1","returnedLineCount":"4","totalLineCount":"20","truncated":"false"}}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolCallId = "call-1",
              toolName = "Read",
              status = "success",
              isError = false,
            ),
          ),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-2","tool_name":"Grep","status":"success","content":"src/App.kt:12:needle","metadata":{"pattern":"needle","path":"src","matchCount":"1"}}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolCallId = "call-2",
              toolName = "Grep",
              status = "success",
              isError = false,
            ),
          ),
        ),
      ),
    )

    val observationLayer = requireNotNull(managed.recentToolObservationLayer)

    assertEquals(managed.recentToolObservationsText, observationLayer.text)
    assertEquals(2, observationLayer.observationCount)
    assertEquals(0, observationLayer.omittedObservationCount)
    assertTrue(observationLayer.text.contains("Read file_path=README.md"))
    assertTrue(observationLayer.text.contains("Grep pattern=needle path=src matches=1"))
    assertTrue(managed.report.recentToolObservationLayerIncluded)
    assertEquals(2, managed.report.recentToolObservationCount)
  }

  @Test
  fun prepareCarriesActiveSkillCapsuleForBudgetReduction() {
    val manager = ContextManager()
    val activeSkillCapsule = ActiveSkillCapsule(
      name = "ui-ux-pro-max",
      description = "High-end UI review workflow.",
      relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
      invocationControl = "explicit-only",
      executionContext = "fork",
      activationSource = "skill_read",
      markdownBody = """
        # UI UX Pro Max

        Audit the current interface first.
      """.trimIndent(),
      toolPermissionSummary = listOf("read:allow", "write:allow"),
      allowedToolKeys = setOf("read", "write"),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        activeSkillCapsule = activeSkillCapsule,
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertEquals("ui-ux-pro-max", managed.activeSkillCapsule?.name)
    assertEquals("skill_read", managed.activeSkillCapsule?.activationSource)
    assertTrue(managed.activeSkillText.contains("[Instructions]"))
    assertTrue(managed.activeSkillText.contains("Audit the current interface first"))
  }

  @Test
  fun prepareBuildsPruningSummaryBeforeWindowing() {
    val manager = ContextManager(
      contextPruner = ContextPruner(
        ContextPrunerConfig(
          maxToolChars = 128,
          maxToolLines = 4,
          maxAttachmentChars = 64,
          maxPreviewChars = 64,
        ),
      ),
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 4,
          maxCharsPerMessage = 120,
        ),
      ),
    )

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo."),
          RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
          RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
          RuntimeConversationMessage(
            RuntimeConversationRole.TOOL,
            "data:image/png;base64," + "A".repeat(160),
          ),
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue."),
        ),
      ),
    )

    val summary = requireNotNull(managed.pruningSummary)

    assertEquals(0, summary.removedMessageCount)
    assertEquals(1, summary.rewrittenMessageCount)
    assertEquals(0, summary.duplicateBackgroundMessageCount)
    assertEquals(1, summary.attachmentLikeMessageCount)
    assertTrue(summary.text.contains("removed=0, rewritten=1"))
    assertTrue(managed.transcriptWindow.messages.any { message ->
      message.content.startsWith("Attachment-like payload pruned by prompt guardrail.")
    })
    assertEquals(0, managed.report.prunedTranscriptMessageCount)
    assertEquals(1, managed.report.rewrittenTranscriptMessageCount)
    assertEquals(0, managed.report.duplicateBackgroundTranscriptMessageCount)
    assertEquals(1, managed.report.attachmentLikeTranscriptRewriteCount)
    assertTrue(managed.report.pruningSummaryIncluded)
  }

  @Test
  fun prepareFailClosesSoulAndMemoryInjectionWhenPolicyDisablesThem() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          soulProfile = RuntimeSoulProfile(
            presetName = "BUILDER",
            displayName = "Night Shift",
            customGuidance = "Be terse and implementation-first.",
          ),
          injectionPolicy = ContextInjectionPolicy(
            soulContractEnabled = false,
            soulTurnPolicyEnabled = false,
            automaticMemoryInjectionEnabled = false,
            memoryDerivedPolicyEnabled = false,
          ),
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-user",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Default to concise Chinese replies.",
                lastConfirmedAtEpochMs = 10L,
                score = 420,
              ),
            ),
            matchedRecordCount = 1,
            trace = MemoryRecallTrace(
              queryTerms = listOf("chinese"),
              selected = listOf(
                MemoryRecallSelectedTrace(
                  id = "memory-user",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  score = 420,
                  matchedTerms = listOf("chinese"),
                  contentPreview = "Default to concise Chinese replies.",
                ),
              ),
            ),
          ),
        ),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertEquals("", managed.personalizationText)
    assertEquals("", managed.memoryText)
    assertEquals(0, managed.report.matchedMemoryRecordCount)
    assertEquals(0, managed.report.injectedMemoryRecordCount)
    assertEquals(0, managed.report.omittedMemoryRecordCount)
    assertTrue(managed.report.memoryRecallTrace.queryTerms.isEmpty())
    assertTrue(managed.report.memoryRecallTrace.selected.isEmpty())
    assertEquals("", managed.turnResponsePolicyText)
  }

  @Test
  fun prepareBuildsTurnResponsePolicyWhenStructuredSignalIsAvailable() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          soulProfile = RuntimeSoulProfile(
            presetName = "WARM",
            extensions = mapOf(
              "initiative_preference_offset" to "1",
              "reassurance_preference_offset" to "1",
              "supportive_reassurance_allowed" to "true",
              "proactive_relational_check_in_allowed" to "true",
            ),
          ),
          turnSemanticSignal = SoulTurnSemanticSignal(
            isTaskBearingRequest = true,
            userAffect = SoulTurnUserAffect.STRAINED,
            clarificationNeeded = true,
          ),
        ),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertTrue(managed.turnResponsePolicyText.contains("task_priority=task_first"))
    assertTrue(managed.turnResponsePolicyText.contains("response_shape=short_support_then_answer"))
    assertTrue(managed.turnResponsePolicyText.contains("clarification_mode=proactive_task_focused"))
    assertTrue(managed.turnResponsePolicyText.contains("directives:"))
  }

  @Test
  fun prepareSynthesizesWorkingStateFromTaskAndRecentObservations() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Inspect the recent runtime context pipeline."),
        runId = "run-context-1",
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the recent runtime context pipeline."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = """{"run_id":"run-1","task_id":"task-1","turn":1,"tool_call_id":"call-1","tool_name":"Read","status":"success","content":"intro","metadata":{"filePath":"runtime/context/PromptAssembler.kt","offset":"1","limit":"80","returnedLineCount":"40","totalLineCount":"420","truncated":"false"}}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolCallId = "call-1",
              toolName = "Read",
              status = "success",
              isError = false,
            ),
          ),
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("task_id=task-context"))
    assertTrue(managed.workingStateText.contains("run_id=run-context-1"))
    assertTrue(managed.workingStateText.contains("primary_goal=Inspect the recent runtime context pipeline."))
    assertTrue(managed.workingStateText.contains("[Recent Actions]"))
    assertTrue(managed.workingStateText.contains("Read file_path=runtime/context/PromptAssembler.kt"))
    assertTrue(managed.report.workingStateTrace.included)
    assertTrue(managed.report.workingStateTrace.objectivePresent)
    assertEquals(1, managed.report.workingStateTrace.recentActionCount)
    assertTrue(managed.report.workingStateTrace.synthesizedFromTaskInput)
    assertTrue(managed.report.workingStateTrace.synthesizedFromRecentObservations)
    assertFalse(managed.report.workingStateTrace.synthesizedFromTodoSnapshot)
  }

  @Test
  fun prepareProjectsResumeContextIntoWorkingStateWhenPresent() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue after approval resume."),
        runId = "run-context-resume-1",
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
        resumeContext = WorkingStateResumeContext(
          turnIndex = 1,
          toolCallCount = 1,
          pendingActionCount = 1,
          nextActionType = "tool_call",
          pendingToolName = "Write",
          checkpointBoundary = "tool_result_committed",
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("[Recent Actions]"))
    assertTrue(
      managed.workingStateText.contains(
        "Resume checkpoint turn=1 tool_calls=1 pending_actions=1 next_action=tool_call pending_tool=Write [source=resume_checkpoint; why=tool_result_committed]",
      ),
    )
    assertTrue(managed.workingStateText.contains("[Decisions]"))
    assertTrue(
      managed.workingStateText.contains(
        "Continue from the saved checkpoint state instead of restarting from the original task input.",
      ),
    )
    assertTrue(managed.report.workingStateTrace.synthesizedFromResumeContext)
    assertEquals(1, managed.report.workingStateTrace.recentActionCount)
    assertEquals(1, managed.report.workingStateTrace.decisionCount)
  }

  @Test
  fun preparePreservesSeededWorkingStateEntries() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue the current task."),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(
          workingState = WorkingState(
            objective = WorkingStateObjective(
              primaryGoal = "Ship the working-state layer.",
              currentSubgoal = "Finish runtime prompt injection.",
              status = "in_progress",
            ),
            findings = listOf(
              WorkingStateEntry(
                text = "PromptAssembler still has no dedicated working-state layer.",
                sourceType = "code_inspection",
              ),
            ),
            nextActions = listOf(
              WorkingStateEntry(text = "Add prompt-layer tests."),
            ),
          ),
        ),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
      ),
    )

    assertTrue(managed.workingStateText.contains("primary_goal=Ship the working-state layer."))
    assertTrue(managed.workingStateText.contains("current_subgoal=Finish runtime prompt injection."))
    assertTrue(managed.workingStateText.contains("[Recent Findings]"))
    assertTrue(managed.workingStateText.contains("source=code_inspection"))
    assertTrue(managed.workingStateText.contains("[Next Actions]"))
    assertFalse(managed.report.workingStateTrace.synthesizedFromTaskInput)
    assertFalse(managed.report.workingStateTrace.synthesizedFromRecentObservations)
    assertFalse(managed.report.workingStateTrace.synthesizedFromTodoSnapshot)
    assertEquals(1, managed.report.workingStateTrace.findingCount)
    assertEquals(1, managed.report.workingStateTrace.nextActionCount)
  }

  @Test
  fun prepareBuildsWorkingStateFromTodoSnapshotWhenStructuredStateIsMissing() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue the runtime rollout."),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = emptyList(),
        todoSnapshot = listOf(
          AgentTodoEntry(
            content = "Wire working state todo projection",
            status = AgentTodoStatus.IN_PROGRESS,
            activeForm = "Wiring working state todo projection",
          ),
          AgentTodoEntry(
            content = "Add context-manager assertions",
            status = AgentTodoStatus.PENDING,
          ),
          AgentTodoEntry(
            content = "Run runtime unit tests",
            status = AgentTodoStatus.PENDING,
          ),
          AgentTodoEntry(
            content = "Review the earlier sketch",
            status = AgentTodoStatus.COMPLETED,
          ),
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("primary_goal=Continue the runtime rollout."))
    assertTrue(managed.workingStateText.contains("current_subgoal=Wiring working state todo projection"))
    assertTrue(managed.workingStateText.contains("status=in_progress"))
    assertTrue(managed.workingStateText.contains("[Next Actions]"))
    assertTrue(managed.workingStateText.contains("Add context-manager assertions [source=todo_snapshot]"))
    assertTrue(managed.workingStateText.contains("Run runtime unit tests [source=todo_snapshot]"))
    assertFalse(managed.workingStateText.contains("Review the earlier sketch"))
    assertTrue(managed.report.workingStateTrace.included)
    assertTrue(managed.report.workingStateTrace.synthesizedFromTaskInput)
    assertFalse(managed.report.workingStateTrace.synthesizedFromRecentObservations)
    assertTrue(managed.report.workingStateTrace.synthesizedFromTodoSnapshot)
    assertEquals(2, managed.report.workingStateTrace.nextActionCount)
  }

  @Test
  fun prepareUsesStructuredRecentToolActionsForWorkingState() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue the runtime rollout."),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue the runtime rollout."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = """{"tool_name":"Write","status":"success","content":"Wrote README.md successfully.","metadata":{"filePath":"README.md","targetSummary":"README.md"}}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolName = "Write",
              status = "success",
              isError = false,
            ),
          ),
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("[Recent Actions]"))
    assertTrue(managed.workingStateText.contains("Write file_path=README.md [source=workspace_mutation]"))
    assertTrue(managed.report.workingStateTrace.included)
    assertEquals(1, managed.report.workingStateTrace.recentActionCount)
    assertTrue(managed.report.workingStateTrace.synthesizedFromRecentObservations)
  }

  @Test
  fun prepareProjectsApprovalBoundarySignalsIntoWorkingState() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue the runtime rollout."),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue the runtime rollout."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content =
              """{"tool_name":"Write","status":"denied","content":"Approval required.","error_code":"APPROVAL_REQUIRED","error_message":"Approval required for Write before continuing.","metadata":{"filePath":"README.md"}}""",
            kind = RuntimeConversationMessageKind.TOOL_RESULT,
            toolResult = RuntimeConversationToolResult(
              toolName = "Write",
              status = "denied",
              isError = true,
            ),
          ),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content =
              "approval_rejected task_id=task-1 run_id=run-1 tool_name=Write outcome=user_rejected executed=false next_step=await_user_instruction",
          ),
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("[Decisions]"))
    assertTrue(managed.workingStateText.contains("Do not retry Write automatically; wait for new instruction."))
    assertTrue(managed.workingStateText.contains("[Blockers]"))
    assertTrue(managed.workingStateText.contains("Approval required for Write before continuing."))
    assertTrue(managed.workingStateText.contains("User rejected approval for Write; await new instruction."))
    assertEquals(1, managed.report.workingStateTrace.decisionCount)
    assertEquals(2, managed.report.workingStateTrace.blockerCount)
    assertTrue(managed.report.workingStateTrace.synthesizedFromRecentObservations)
  }

  @Test
  fun prepareProjectsRetryAbandonedReplayIntoWorkingState() {
    val manager = ContextManager()

    val managed = manager.prepare(
      PromptAssemblyInput(
        task = promptTask(input = "Continue after restore."),
        baseSystemPrompt = "You are OpenCray for testing.",
        sessionContext = AgentRuntimeSessionContext(),
        toolDefinitions = emptyList(),
        liveConversation = listOf(
          RuntimeConversationMessage(RuntimeConversationRole.USER, "Continue after restore."),
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content =
              "retry_abandoned task_id=task-restore run_id=run-restore outcome=retry_budget_exhausted attempt=2 error_code=TOOL_EXECUTION_FAILED next_step=await_user_instruction",
          ),
        ),
      ),
    )

    assertTrue(managed.workingStateText.contains("[Decisions]"))
    assertTrue(managed.workingStateText.contains("Do not auto-rerun from task input; wait for explicit resume or new instruction."))
    assertTrue(managed.workingStateText.contains("[Blockers]"))
    assertTrue(managed.workingStateText.contains("Retry path exhausted after repeated failure; await explicit resume or new instruction."))
    assertTrue(managed.workingStateText.contains("why=TOOL_EXECUTION_FAILED"))
    assertEquals(1, managed.report.workingStateTrace.decisionCount)
    assertEquals(1, managed.report.workingStateTrace.blockerCount)
  }

  private fun promptTask(): AgentTask = AgentTask(
    id = "task-context",
    type = AgentTaskType.PROMPT,
    input = "Summarize the repo changes.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    createdAtEpochMs = 100L,
  )

  private fun promptTask(input: String): AgentTask = promptTask().copy(input = input)
}
