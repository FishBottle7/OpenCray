package com.opencray.runtime

import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapFileTrace
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionTrace
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.MemoryFlushTrace
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class OpenCrayAgentRuntimeMetadataProjectionTest : OpenCrayAgentRuntimeTestBase() {
  @Test
  fun runPromptTaskExposesMemoryRecallTraceInResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-trace-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Applied the recalled memory."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-user",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Default to concise Chinese replies.",
                lastConfirmedAtEpochMs = 2_000L,
                matchedTerms = listOf("chinese"),
                score = 420,
              ),
            ),
            matchedRecordCount = 2,
            omittedRecordCount = 1,
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
              ),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep using Chinese and verify Gradle."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["contextMatchedMemoryCount"])
    assertEquals("1", result.metadata["contextInjectedMemoryCount"])
    assertEquals("1", result.metadata["contextOmittedMemoryCount"])
    assertEquals("chinese,gradle", result.metadata["contextMemoryQueryTerms"])
    assertEquals("memory-user@420[chinese]", result.metadata["contextMemorySelectedSummary"])
    assertTrue(gateway.requests.single().prompt.contains("[Retrieved Memory]"))
  }

  @Test
  fun runPromptTaskProjectsMemoryFlushTraceIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-memory-flush-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Memory flush trace applied."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          memoryFlushTrace = MemoryFlushTrace(
            outcome = MemoryFlushOutcome.WRITTEN,
            executionMode = "inline",
            omittedMessageCount = 4,
            omittedCharCount = 512,
            signature = "flush-signature-123",
            candidateCount = 3,
            writtenRecordCount = 2,
            writtenKinds = listOf("project_fact", "user_preference"),
            writtenRecordIds = listOf("mem-a", "mem-b"),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_675L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the flushed history."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("written", result.metadata["contextMemoryFlushOutcome"])
    assertEquals("inline", result.metadata["contextMemoryFlushExecutionMode"])
    assertEquals("4", result.metadata["contextMemoryFlushOmittedMessageCount"])
    assertEquals("512", result.metadata["contextMemoryFlushOmittedCharCount"])
    assertEquals("flush-signature-123", result.metadata["contextMemoryFlushSignature"])
    assertEquals("3", result.metadata["contextMemoryFlushCandidateCount"])
    assertEquals("2", result.metadata["contextMemoryFlushWrittenRecordCount"])
    assertEquals("project_fact,user_preference", result.metadata["contextMemoryFlushWrittenKinds"])
    assertEquals("mem-a,mem-b", result.metadata["contextMemoryFlushWrittenRecordIds"])
  }

  @Test
  fun runPromptTaskProjectsDurableCompactionIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-durable-compaction-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Durable compaction trace applied."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          durableCompaction = DurableCompactionContext(
            text = """
              Older session history has been durably compacted into summaries.
              [Compacted History]
              Compacted 6 older message(s) outside the active transcript window.
            """.trimIndent(),
            trace = DurableCompactionTrace(
              compactedThisRun = true,
              executionMode = "inline",
              sourceTranscriptMessageCount = 18,
              retainedTranscriptMessageCount = 12,
              latestCompactedMessageCount = 6,
              includedSummaryCount = 1,
              omittedSummaryCount = 0,
              totalCompactedMessageCount = 6,
              latestCompactedAtEpochMs = 4_200L,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_690L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the durable summaries."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("true", result.metadata["contextDurableCompactionCompactedThisRun"])
    assertEquals("inline", result.metadata["contextDurableCompactionExecutionMode"])
    assertEquals("18", result.metadata["contextDurableCompactionSourceTranscriptMessageCount"])
    assertEquals("12", result.metadata["contextDurableCompactionRetainedTranscriptMessageCount"])
    assertEquals("6", result.metadata["contextDurableCompactionLatestMessageCount"])
    assertEquals("1", result.metadata["contextDurableCompactionIncludedSummaryCount"])
    assertEquals("0", result.metadata["contextDurableCompactionOmittedSummaryCount"])
    assertEquals("6", result.metadata["contextDurableCompactionTotalCompactedMessageCount"])
    assertEquals("4200", result.metadata["contextDurableCompactionLatestAtEpochMs"])
    assertTrue(gateway.requests.single().prompt.contains("[Durable Compaction]"))
    assertTrue(gateway.requests.single().prompt.contains("Compacted 6 older message(s) outside the active transcript window."))
  }

  @Test
  fun runPromptTaskProjectsBootstrapMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-bootstrap-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Bootstrap context applied."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          liveContextTrace = LiveContextTrace(
            mode = "full",
            soulEnabled = true,
            memoryRecallEnabled = true,
          ),
          bootstrapContext = BootstrapContext(
            mode = BootstrapMode.FULL,
            files = listOf(
              BootstrapSnippet(
                name = "AGENTS.md",
                relativePath = "AGENTS.md",
                content = "# Agents\nFollow the workspace instructions.",
                sourceCharCount = 44,
                truncated = false,
              ),
              BootstrapSnippet(
                name = "PROJECT.md",
                relativePath = "PROJECT.md",
                content = "# Project\nThis repo uses Gradle.",
                sourceCharCount = 80,
                truncated = true,
              ),
            ),
            trace = BootstrapTrace(
              mode = "full",
              visibleFileCount = 2,
              injectedFileCount = 2,
              omittedFileCount = 0,
              truncatedFileCount = 1,
              files = listOf(
                BootstrapFileTrace(
                  name = "AGENTS.md",
                  relativePath = "AGENTS.md",
                  sourceCharCount = 44,
                  injectedCharCount = 44,
                  truncated = false,
                ),
                BootstrapFileTrace(
                  name = "PROJECT.md",
                  relativePath = "PROJECT.md",
                  sourceCharCount = 80,
                  injectedCharCount = 31,
                  truncated = true,
                ),
              ),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_688L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Respect the workspace bootstrap files."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("full", result.metadata["contextLiveMode"])
    assertEquals("true", result.metadata["contextLiveSoulEnabled"])
    assertEquals("true", result.metadata["contextLiveMemoryRecallEnabled"])
    assertEquals("full", result.metadata["contextBootstrapMode"])
    assertEquals("2", result.metadata["contextBootstrapVisibleFileCount"])
    assertEquals("2", result.metadata["contextBootstrapInjectedFileCount"])
    assertEquals("0", result.metadata["contextBootstrapOmittedFileCount"])
    assertEquals("1", result.metadata["contextBootstrapTruncatedFileCount"])
    assertEquals(
      "AGENTS.md@AGENTS.md[44|44|false];PROJECT.md@PROJECT.md[80|31|true]",
      result.metadata["contextBootstrapFileSummary"],
    )
    val systemPrompt = requireNotNull(gateway.requests.single().systemPrompt)
    assertTrue(systemPrompt.contains("[Bootstrap AGENTS.md]"))
    assertTrue(systemPrompt.contains("Follow the workspace instructions."))
  }

  @Test
  fun runPromptTaskExposesPruningMetadataWhenSeededConversationNeedsCleanup() {
    val workspaceRoot = temporaryFolder.newFolder("agent-pruning-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"clean context applied"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note."),
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Protocol note."),
            RuntimeConversationMessage(
              RuntimeConversationRole.TOOL,
              "data:image/png;base64," + "A".repeat(160),
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Continue from the cleaned transcript."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("0", result.metadata["contextPrunedMessageCount"])
    assertEquals("1", result.metadata["contextRewrittenMessageCount"])
    assertEquals("true", result.metadata["contextPruningSummaryIncluded"])
    assertTrue(gateway.requests.single().prompt.contains("[Pruning Summary]"))
    assertTrue(gateway.requests.single().prompt.contains("Attachment-like payload pruned by prompt guardrail."))
  }

  @Test
  fun runPromptTaskExposesContextBudgetDiagnosticsInGatewayAndResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-budget-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"budget metadata captured"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          allowedToolNames = emptySet(),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = "Budget diagnostics must stay visible to the runtime host. ".repeat(80).trim(),
        llmMetadata = mapOf(
          "context_window_tokens" to "900",
          "reserved_output_tokens" to "256",
          "prompt_safety_margin_tokens" to "96",
          "effective_input_percent" to "0.15",
        ),
        sessionContext = AgentRuntimeSessionContext(
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
            RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Earlier answer."),
          ),
          recalledMemory = MemoryRecallResult(
            memories = listOf(
              RetrievedMemory(
                id = "memory-budget-runtime",
                kind = MemoryKind.USER_PREFERENCE,
                scope = MemoryScope.USER,
                status = MemoryStatus.ACTIVE,
                content = "Keep budget diagnostics visible to the runtime host.",
                lastConfirmedAtEpochMs = 10L,
                score = 420,
              ),
            ),
            matchedRecordCount = 1,
          ),
          durableCompaction = DurableCompactionContext(
            text = "Older compacted history for budget diagnostics.",
          ),
        ),
      ),
      clock = IncrementingClock(start = 6_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need budget diagnostics."),
      hooks = runtimeHooks(),
    )

    val requestMetadata = gateway.requests.single().metadata

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("budget metadata captured", result.stdout)
    assertEquals("true", requestMetadata["contextBudgetApplied"])
    assertEquals("EMERGENCY", requestMetadata["contextBudgetPressureMode"])
    assertEquals("900", requestMetadata["contextBudgetContextWindowTokens"])
    assertEquals("256", requestMetadata["contextBudgetReservedOutputTokens"])
    assertEquals("96", requestMetadata["contextBudgetSafetyMarginTokens"])
    assertEquals("balanced", requestMetadata["contextBudgetSelectedPreset"])
    assertEquals("dev", requestMetadata["contextBudgetEffectivePreset"])
    assertEquals("raw", requestMetadata["contextBudgetPresetSource"])
    assertEquals("true", requestMetadata["contextBudgetPresetDiverged"])
    assertEquals("balanced", requestMetadata["contextBudgetSourcePreset"])
    assertEquals("12", requestMetadata["contextBudgetSourceTranscriptMaxMessages"])
    assertEquals("4", requestMetadata["contextBudgetSourceInjectedMemoryMaxRecords"])
    assertEquals("6", requestMetadata["contextBudgetSourceMemoryRecallMaxRecords"])
    assertEquals("3200", requestMetadata["contextBudgetSourceBootstrapMaxChars"])
    assertEquals("8", requestMetadata["contextBudgetSourceSkillInventoryMaxSkills"])
    assertEquals("3200", requestMetadata["contextBudgetSourceActiveSkillMaxChars"])
    assertEquals("4", requestMetadata["contextBudgetSourceRecentObservationMaxEntries"])
    assertEquals("8", requestMetadata["contextBudgetSourceMemoryFlushMaxToolObservations"])
    assertEquals("548", requestMetadata["contextBudgetHardInputTokens"])
    assertEquals("512", requestMetadata["contextBudgetTargetInputTokens"])
    assertEquals("548", requestMetadata["contextBudgetEmergencyInputTokens"])
    assertEquals("true", requestMetadata["contextBudgetUnresolvedOverflow"])
    assertTrue(requestMetadata["contextBudgetFullLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetCompactLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetMinimalLayerCount"].orEmpty().isNotBlank())
    assertEquals("minimal", requestMetadata["contextToolProtocolDetailMode"])
    assertEquals("true", requestMetadata["contextToolProtocolReducedForBudget"])
    assertEquals("0", requestMetadata["contextToolProtocolAttachmentExampleCount"])
    assertTrue(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains("RETRIEVED_MEMORY:"))
    assertTrue(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains("CONVERSATION:"))
    assertFalse(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains(":reduced"))
    assertFalse(requestMetadata["contextBudgetLayerSummary"].orEmpty().contains(":kept"))
    val layerDetails = Json.parseToJsonElement(
      requestMetadata["contextBudgetLayerDetails"].orEmpty(),
    ).jsonArray
    assertTrue(layerDetails.isNotEmpty())
    val conversationLayer = layerDetails.firstOrNull { layer ->
      (layer.jsonObject["id"] as? JsonPrimitive)?.content == "CONVERSATION"
    }?.jsonObject
    assertNotNull(conversationLayer)
    assertTrue(
      setOf("full", "compact", "minimal", "omitted").contains(
        (conversationLayer?.get("finalState") as? JsonPrimitive)?.content,
      ),
    )
    assertEquals(requestMetadata["contextBudgetPressureMode"], result.metadata["contextBudgetPressureMode"])
    assertEquals(requestMetadata["contextBudgetHardInputTokens"], result.metadata["contextBudgetHardInputTokens"])
    assertEquals(requestMetadata["contextBudgetTargetInputTokens"], result.metadata["contextBudgetTargetInputTokens"])
    assertEquals(requestMetadata["contextBudgetUnresolvedOverflow"], result.metadata["contextBudgetUnresolvedOverflow"])
    assertEquals(
      requestMetadata["contextBudgetEffectivePreset"],
      result.metadata["contextBudgetEffectivePreset"],
    )
    assertEquals(requestMetadata["contextBudgetPresetSource"], result.metadata["contextBudgetPresetSource"])
    assertEquals(requestMetadata["contextBudgetSourcePreset"], result.metadata["contextBudgetSourcePreset"])
    assertEquals(
      requestMetadata["contextBudgetSourceTranscriptMaxMessages"],
      result.metadata["contextBudgetSourceTranscriptMaxMessages"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceInjectedMemoryMaxRecords"],
      result.metadata["contextBudgetSourceInjectedMemoryMaxRecords"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceMemoryRecallMaxRecords"],
      result.metadata["contextBudgetSourceMemoryRecallMaxRecords"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceBootstrapMaxChars"],
      result.metadata["contextBudgetSourceBootstrapMaxChars"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceSkillInventoryMaxSkills"],
      result.metadata["contextBudgetSourceSkillInventoryMaxSkills"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceActiveSkillMaxChars"],
      result.metadata["contextBudgetSourceActiveSkillMaxChars"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceRecentObservationMaxEntries"],
      result.metadata["contextBudgetSourceRecentObservationMaxEntries"],
    )
    assertEquals(
      requestMetadata["contextBudgetSourceMemoryFlushMaxToolObservations"],
      result.metadata["contextBudgetSourceMemoryFlushMaxToolObservations"],
    )
    assertEquals(requestMetadata["contextBudgetFullLayerCount"], result.metadata["contextBudgetFullLayerCount"])
    assertEquals(
      requestMetadata["contextBudgetCompactLayerCount"],
      result.metadata["contextBudgetCompactLayerCount"],
    )
    assertEquals(
      requestMetadata["contextBudgetMinimalLayerCount"],
      result.metadata["contextBudgetMinimalLayerCount"],
    )
    assertEquals(requestMetadata["contextBudgetLayerSummary"], result.metadata["contextBudgetLayerSummary"])
    assertEquals(requestMetadata["contextToolProtocolDetailMode"], result.metadata["contextToolProtocolDetailMode"])
    assertEquals(requestMetadata["contextToolProtocolReducedForBudget"], result.metadata["contextToolProtocolReducedForBudget"])
    assertEquals(requestMetadata["contextToolProtocolAttachmentExampleCount"], result.metadata["contextToolProtocolAttachmentExampleCount"])
    assertTrue(
      result.metadata["contextBudgetOmittedLayerNames"].orEmpty().contains("Retrieved Memory") ||
        result.metadata["contextBudgetReducedLayerNames"].orEmpty().contains("Conversation"),
    )
  }

  @Test
  fun runPromptTaskReportsWorkingStateBudgetReductionInGatewayAndResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-working-state-budget-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"working state budget metadata captured"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          allowedToolNames = emptySet(),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        systemPrompt = "Working state budget diagnostics must remain observable. ".repeat(80).trim(),
        llmMetadata = mapOf(
          "context_window_tokens" to "900",
          "reserved_output_tokens" to "256",
          "prompt_safety_margin_tokens" to "96",
          "effective_input_percent" to "0.15",
        ),
        sessionContext = AgentRuntimeSessionContext(
          workingState = WorkingState(
            objective = WorkingStateObjective(
              taskId = "task-working-state-budget-runtime",
              runId = "run-working-state-budget-runtime",
              primaryGoal = "Keep the latest operational state visible when prompt pressure spikes.",
              currentSubgoal = "Prove runtime metadata exposes the working-state reducer decision.",
              status = "in_progress",
            ),
            findings = (1..6).map { index ->
              WorkingStateEntry(
                text = "Finding $index " + "evidence ".repeat(12).trim(),
                sourceType = "code_inspection",
              )
            },
            recentActions = (1..8).map { index ->
              WorkingStateEntry(
                text = "Recent action $index " + "workspace mutation ".repeat(10).trim(),
                sourceType = "workspace_mutation",
              )
            },
            decisions = (1..4).map { index ->
              WorkingStateEntry(
                text = "Decision $index " + "branch rationale ".repeat(10).trim(),
                sourceType = "branch_control",
              )
            },
            blockers = (1..3).map { index ->
              WorkingStateEntry(
                text = "Blocker $index " + "approval wait ".repeat(10).trim(),
                sourceType = "approval_boundary",
              )
            },
            nextActions = (1..4).map { index ->
              WorkingStateEntry(
                text = "Next action $index " + "verify focused tests ".repeat(10).trim(),
                sourceType = "todo_snapshot",
              )
            },
            updatedAtEpochMs = 456_789L,
          ),
        ),
      ),
      clock = IncrementingClock(start = 6_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need working state budget diagnostics."),
      hooks = runtimeHooks(),
    )

    val request = gateway.requests.single()
    val requestMetadata = request.metadata
    val layerSummary = requestMetadata["contextBudgetLayerSummary"].orEmpty()

    val messageText = gatewayStructuredPayloadText(request)

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("working state budget metadata captured", result.stdout)
    assertTrue(request.messages.isNotEmpty())
    assertEquals("messages_primary", request.metadata["gatewayTransportMode"])
    assertEquals("fallback_debug_only", request.metadata["gatewayPromptFieldRole"])
    assertTrue(messageText.contains("[Working State]"))
    assertTrue(messageText.contains("Recent action 8"))
    assertTrue(messageText.contains("Decision 4"))
    assertTrue(messageText.contains("Blocker 3"))
    assertTrue(messageText.contains("Next action 4"))
    assertFalse(messageText.contains("[Recent Findings]"))
    assertFalse(messageText.contains("Finding 1"))
    assertFalse(messageText.contains("Recent action 1"))
    assertFalse(messageText.contains("Decision 1"))
    assertFalse(messageText.contains("Blocker 1"))
    assertFalse(messageText.contains("Next action 1"))
    assertFalse(messageText.contains("updated_at_epoch_ms=456789"))
    assertTrue(layerSummary.contains("WORKING_STATE:"))
    assertTrue(layerSummary.contains(":minimal["))
    assertTrue(layerSummary.contains("reduce_working_state_minimal"))
    assertEquals("dev", requestMetadata["contextBudgetEffectivePreset"])
    assertEquals("raw", requestMetadata["contextBudgetPresetSource"])
    assertTrue(requestMetadata["contextBudgetMinimalLayerCount"].orEmpty().isNotBlank())
    assertTrue(requestMetadata["contextBudgetReducedLayerNames"].orEmpty().contains("Working State"))
    assertEquals(layerSummary, result.metadata["contextBudgetLayerSummary"])
    assertEquals(
      requestMetadata["contextBudgetReducedLayerNames"],
      result.metadata["contextBudgetReducedLayerNames"],
    )
  }

  @Test
  fun runPromptTaskOmitsHostOnlyMetadataFromGatewayRequestMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-host-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"metadata sanitized"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 6_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Check metadata visibility.",
        metadata = mapOf(
          "_host.pendingMessageId" to "assistant-1",
          "chatMode" to "AUTO",
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("AUTO", gateway.requests.single().metadata["chatMode"])
    assertTrue("_host.pendingMessageId" !in gateway.requests.single().metadata)
  }
}
