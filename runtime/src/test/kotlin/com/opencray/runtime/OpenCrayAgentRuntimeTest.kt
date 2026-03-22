package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.core.orchestrator.SuspensionRequest
import com.opencray.llm.LiteLlmAttemptOutcome
import com.opencray.llm.LiteLlmAttemptRecord
import com.opencray.llm.LiteLlmCompletionMode
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayResult
import com.opencray.llm.LiteLlmMetadataKeys
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmRouteSelectionMetadata
import com.opencray.llm.LiteLlmStructuredCompletion
import com.opencray.llm.LiteLlmStructuredToolCall
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
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.skills.SkillCatalogResolver
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryTrace
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.skills.VisibleSkillTrace
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class OpenCrayAgentRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun runPromptTaskFeedsToolObservationIntoNextLlmTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"README 确认内容是 hello from workspace"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "读一下 README 然后告诉我内容"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README 确认内容是 hello from workspace", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Identity]"))
    assertTrue(gateway.requests[0].prompt.contains("[Tool Protocol]"))
    assertTrue(gateway.requests[1].prompt.contains("workspace_read_file"))
    assertTrue(gateway.requests[1].prompt.contains("hello from workspace"))
  }

  @Test
  fun runPromptTaskInjectsSupplementsAtTurnStartBeforeNextLlmRequest() {
    val workspaceRoot = temporaryFolder.newFolder("agent-supplement-workspace")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "hello from workspace".toByteArray(StandardCharsets.UTF_8),
    )

    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_read_file","arguments":{"path":"README.md"}}""",
        """{"type":"final","answer":"I saw the supplement."}""",
      ),
    )
    val eventSink = RecordingEventSink()
    var supplementProviderCalls = 0
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        supplementInputProvider = { _, _ ->
          supplementProviderCalls += 1
          if (supplementProviderCalls == 2) {
            listOf(
              OpenCraySupplementInput(
                entryId = "supplement-1",
                text = "Also verify the tests before you answer.",
                createdAtEpochMs = 1_500L,
              ),
            )
          } else {
            emptyList()
          }
        },
      ),
      eventSink = eventSink,
      clock = IncrementingClock(start = 1_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and then answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("I saw the supplement.", result.stdout)
    assertEquals(2, gateway.requests.size)
    assertFalse(gateway.requests[0].prompt.contains("Also verify the tests before you answer."))
    assertTrue(gateway.requests[1].prompt.contains("Also verify the tests before you answer."))
    val supplementEvent = eventSink.events.filterIsInstance<OpenCraySupplementEvent>().single()
    assertEquals("supplement-1", supplementEvent.entryId)
    assertEquals(1, supplementEvent.turn)
    assertEquals("Also verify the tests before you answer.", supplementEvent.text)
  }

  @Test
  fun runPromptTaskSeedsStoredConversationIntoFirstLlmTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-history-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"延续了之前的对话"}""",
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
          sessionPolicyText = "Keep the session coherent with earlier decisions.",
          conversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
            RuntimeConversationMessage(RuntimeConversationRole.ASSISTANT, "Earlier answer."),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "What changed since then?"),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("延续了之前的对话", result.stdout)
    assertEquals("3", result.metadata["contextMessageCount"])
    assertTrue(result.metadata["contextLayerNames"].orEmpty().contains("Task Metadata"))
    assertTrue(result.metadata["contextLayerNames"].orEmpty().contains("Conversation"))
    assertTrue(gateway.requests[0].systemPrompt.orEmpty().contains("[Session Policy]"))
    assertTrue(gateway.requests[0].prompt.contains("Earlier question."))
    assertTrue(gateway.requests[0].prompt.contains("Earlier answer."))
    assertTrue(gateway.requests[0].prompt.contains("What changed since then?"))
  }

  @Test
  fun runPromptTaskCarriesFinalAttachmentsIntoResultMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-final-attachments-workspace")
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {
          "type": "final",
          "answer": "",
          "attachments": [
            {
              "kind": "image",
              "relative_path": "outputs/result.png",
              "display_name": "result.png",
              "mime_type": "image/png"
            },
            {
              "kind": "audio",
              "path": "outputs/voice-note.m4a",
              "duration_ms": 4200,
              "waveform_bars": [12, 48, 80],
              "transcript_text": "Voice summary"
            }
          ]
        }
        """.trimIndent(),
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      clock = IncrementingClock(start = 2_200L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Send the generated media only."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("", result.stdout)
    val attachmentsJson = result.metadata[OpenCrayExecutionMetadataKeys.FINAL_ATTACHMENTS_JSON].orEmpty()
    val attachments = Json.decodeFromString(
      ListSerializer(OpenCrayFinalAttachment.serializer()),
      attachmentsJson,
    )
    assertEquals(2, attachments.size)
    assertEquals("outputs/result.png", attachments.first().relativePath)
    assertEquals("image", attachments.first().kind)
    assertEquals("outputs/voice-note.m4a", attachments.last().path)
    assertEquals("audio", attachments.last().kind)
    assertEquals(4_200L, attachments.last().durationMs)
    assertEquals(listOf(12, 48, 80), attachments.last().waveformBars)
    assertEquals("Voice summary", attachments.last().transcriptText)
  }

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
  fun runPromptTaskExposesSkillInventoryMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-skill-inventory-metadata")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Used the visible skill inventory."}""",
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
          skillInventory = SkillInventory(
            skills = listOf(
              VisibleSkill(
                name = "ui-ux-pro-max",
                description = "High-end UI review workflow.",
                relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
                userInvocable = true,
                executionContext = SkillExecutionContext.INLINE,
              ),
              VisibleSkill(
                name = "fun-brainstorming",
                description = "Fast architectural brainstorming workflow.",
                relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
                userInvocable = true,
                executionContext = SkillExecutionContext.FORK,
              ),
            ),
            invalidSkillCount = 1,
            trace = SkillInventoryTrace(
              visible = listOf(
                VisibleSkillTrace(
                  name = "ui-ux-pro-max",
                  relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                  invocationControl = "explicit-only",
                  userInvocable = true,
                  executionContext = "inline",
                  descriptionPreview = "High-end UI review workflow.",
                ),
                VisibleSkillTrace(
                  name = "fun-brainstorming",
                  relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                  invocationControl = "explicit-and-implicit",
                  userInvocable = true,
                  executionContext = "fork",
                  descriptionPreview = "Fast architectural brainstorming workflow.",
                ),
              ),
              totalVisibleSkillCount = 2,
              implicitSkillCount = 1,
              invalidSkillCount = 1,
            ),
          ),
        ),
      ),
      clock = IncrementingClock(start = 2_650L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use the right skill workflow before answering."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("2", result.metadata["contextVisibleSkillCount"])
    assertEquals("2", result.metadata["contextInjectedSkillCount"])
    assertEquals("0", result.metadata["contextOmittedSkillCount"])
    assertEquals("1", result.metadata["contextImplicitSkillCount"])
    assertEquals("1", result.metadata["contextInvalidSkillCount"])
    assertEquals(
      "ui-ux-pro-max@.codex/skills/ui-ux-pro-max/SKILL.md[explicit-only|true|inline];" +
        "fun-brainstorming@.codex/skills/fun-brainstorming/SKILL.md[explicit-and-implicit|true|fork]",
      result.metadata["contextVisibleSkillSummary"],
    )
    assertTrue(gateway.requests.single().prompt.contains("[Skill Inventory]"))
    assertTrue(gateway.requests.single().prompt.contains("name=ui-ux-pro-max"))
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
  fun runPromptTaskPromotesReadSkillIntoActiveCapsule() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Audit the current interface first, then apply a concrete design system.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"final","answer":"Used the active skill capsule."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_700L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill, then follow it."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals("skill_read", result.metadata["contextActiveSkillActivationSource"])
    assertEquals("true", result.metadata["contextActiveSkillToolRestrictionEnabled"])
    assertEquals("read,write", result.metadata["contextActiveSkillAllowedTools"])
    assertEquals(2, gateway.requests.size)
    assertTrue(gateway.requests[1].prompt.contains("[Active Skill]"))
    assertTrue(gateway.requests[1].prompt.contains("name=ui-ux-pro-max"))
    assertTrue(gateway.requests[1].prompt.contains("Audit the current interface first"))
    assertTrue(gateway.requests[1].prompt.contains("- Read:"))
    assertFalse(gateway.requests[1].prompt.contains("- Bash:"))
  }

  @Test
  fun runPromptTaskBlocksDisallowedToolWhenActiveSkillRestrictsTools() {
    val workspaceRoot = temporaryFolder.newFolder("agent-active-skill-policy-workspace")
    val skillsRoot = temporaryFolder.newFolder("agent-active-skill-policy-root")
    writeSkill(
      root = skillsRoot,
      relativeDirectory = "ui-ux-pro-max",
      frontMatter = """
        name: ui-ux-pro-max
        description: High-end UI review workflow.
        invocation-control: explicit-only
        user-invocable: true
        allowed-tools: [ read, write ]
      """.trimIndent(),
      body = """
        # UI UX Pro Max

        Stay within the read/write design workflow.
      """.trimIndent(),
    )
    val skillCatalog = SkillCatalogResolver().resolve(listOf(skillsRoot))
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"skill_read","arguments":{"name":"ui-ux-pro-max"}}""",
        """{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}""",
        """{"type":"final","answer":"Stopped after the skill policy blocked Bash."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          skillsRoots = listOf(skillsRoot),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        sessionContext = AgentRuntimeSessionContext(
          skillInventory = skillCatalog.inventory,
          skillCatalog = skillCatalog,
        ),
      ),
      clock = IncrementingClock(start = 2_800L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Load the UI skill and then try Bash anyway."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("ui-ux-pro-max", result.metadata["contextActiveSkillName"])
    assertEquals(3, gateway.requests.size)
    assertTrue(gateway.requests[2].prompt.contains("SKILL_TOOL_POLICY_BLOCKED"))
    assertTrue(gateway.requests[2].prompt.contains("outside the active allowlist"))
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
    assertEquals("1", result.metadata["contextPrunedMessageCount"])
    assertEquals("1", result.metadata["contextRewrittenMessageCount"])
    assertEquals("true", result.metadata["contextPruningSummaryIncluded"])
    assertTrue(gateway.requests.single().prompt.contains("[Pruning Summary]"))
    assertTrue(gateway.requests.single().prompt.contains("Attachment-like payload pruned from prompt."))
  }

  @Test
  fun runPromptTaskFailsWhenToolBudgetIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-budget")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"workspace_list_files","arguments":{}}""",
        """{"type":"tool_call","tool_name":"workspace_list_files","arguments":{}}""",
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
        maxTurns = 3,
        maxToolCalls = 1,
      ),
      clock = IncrementingClock(start = 3_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Inspect the workspace twice."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MAX_TOOL_CALLS_EXCEEDED", result.errorCode)
    assertEquals("tool_budget_exceeded", result.metadata["responseFormat"])
  }

  @Test
  fun runPromptTaskAddsTurnBudgetReminderBeforeLastTurn() {
    val workspaceRoot = temporaryFolder.newFolder("agent-turn-budget-reminder")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "turn budget".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"Returning the final answer in time."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 3, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_250L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and answer before the turn budget runs out."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Returning the final answer in time.", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals(2, gateway.requests.size)
    assertEquals("3", gateway.requests[0].metadata["remainingTurnCount"])
    assertEquals("2", gateway.requests[1].metadata["remainingTurnCount"])
    assertTrue(
      gateway.requests[1].systemPrompt.orEmpty().contains(
        "You have two model turns left including this one.",
      ),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Turn budget note: after this turn, only one model turn remains.",
      ),
    )
  }

  @Test
  fun runPromptTaskFinalAnswerOnlyTurnRejectsAnotherToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-final-turn-only")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "final turn only".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_375L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use one tool, then answer on the final turn."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MAX_TURNS_EXCEEDED", result.errorCode)
    assertEquals("turn_limit_final_answer_required", result.metadata["responseFormat"])
    assertEquals("true", result.metadata["finalAnswerRequired"])
    assertEquals("2", result.metadata["turnCount"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals("1", gateway.requests[1].metadata["remainingTurnCount"])
    assertTrue(gateway.requests[0].tools.isNotEmpty())
    assertTrue(
      gateway.requests[1].systemPrompt.orEmpty().contains(
        "This is the last allowed model turn. Do not call any more tools. Return the final user-facing answer now.",
      ),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Turn budget note: this is the last allowed model turn.",
      ),
    )
  }

  @Test
  fun runPromptTaskWithoutHardBudgetsSkipsTurnAndToolBudgetEnforcement() {
    val workspaceRoot = temporaryFolder.newFolder("agent-no-turn-cap")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "first".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("NOTES.md"),
      "second".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("TODO.md"),
      "third".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("PLAN.md"),
      "fourth".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"NOTES.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"TODO.md"}}""",
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"PLAN.md"}}""",
        """{"type":"final","answer":"Unlimited budgets completed."}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 0, maxToolCalls = 0),
      clock = IncrementingClock(start = 3_450L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Keep using tools until you have enough context."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Unlimited budgets completed.", result.stdout)
    assertEquals("5", result.metadata["turnCount"])
    assertEquals("4", result.metadata["toolCallCount"])
    assertEquals(5, gateway.requests.size)
    assertTrue(
      gateway.requests.all { request ->
        request.metadata["remainingTurnCount"] == null &&
          request.metadata["maxTurnCount"] == null &&
          !request.systemPrompt.orEmpty().contains("[Turn Budget]") &&
          !request.prompt.contains("Turn budget note:")
      },
    )
  }

  @Test
  fun runPromptTaskStopsImmediatelyWhenToolRequiresApproval() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-stop")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
        """{"type":"final","answer":"should not be reached"}""",
      ),
    )
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_500L)::next,
    )

    val suspensionRequests = mutableListOf<SuspensionRequest>()
    val result = runtime.execute(
      task = promptTask(
        input = "Write a note in safe mode.",
        metadata = mapOf("chatMode" to "SAFE"),
      ),
      hooks = runtimeHooks(
        onSuspend = suspensionRequests::add,
      ),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals("APPROVAL_REQUIRED", result.errorCode)
    assertEquals("tool_approval_required", result.metadata["responseFormat"])
    assertEquals("1", result.metadata["toolCallCount"])
    assertEquals(1, gateway.requests.size)
    assertEquals(listOf("APPROVAL_REQUIRED"), suspensionRequests.map(SuspensionRequest::reasonCode))
    assertTrue(!Files.exists(workspaceRoot.toPath().resolve("note.txt")))
  }

  @Test
  fun runPromptTaskApprovalResumeContinuesFromSavedTurnWithoutReissuingPromptToolCall() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-resume")
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_600L)::next,
    )
    val task = promptTask(
      input = "Write a note in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals("APPROVAL_REQUIRED", firstResult.errorCode)
    assertEquals(1, initialGateway.requests.size)
    assertEquals(0, resumeState.turnIndex)
    assertEquals(0, resumeState.toolCallCount)
    assertEquals(0, resumeState.nextActionIndex)
    assertEquals(1, resumeState.pendingActions.size)
    val resumedPendingCall = (resumeState.pendingActions.single() as OpenCraySerializableModelAction.ToolCall).call
    assertEquals("Write", resumedPendingCall.toolName)
    assertEquals("oc-call-1", resumedPendingCall.id)

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Approved write completed."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          approvedTaskId = task.id,
          approvedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_700L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Approved write completed.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertTrue(resumedGateway.requests.single().prompt.contains("note.txt"))
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.id == "oc-call-1" &&
          message.toolCalls.singleOrNull()?.toolName == "Write"
      },
    )
    assertTrue(
      resumedGateway.requests.single().messages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "oc-call-1" &&
          message.toolResult?.toolName == "Write"
      },
    )
    assertTrue(Files.exists(workspaceRoot.toPath().resolve("note.txt")))
    assertEquals(
      listOf("tool_result", "assistant", "lifecycle"),
      resumedEventSink.events.drop(1).map { event ->
        when (event) {
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayLifecycleEvent -> "lifecycle"
          else -> "other"
        }
      },
    )
    assertTrue(resumedEventSink.events.none { event -> event is OpenCrayToolCallEvent })
  }

  @Test
  fun runPromptTaskRejectedApprovalResumeContinuesWithoutReaskingApproval() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-reject-resume")
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}}""",
      ),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 3_750L)::next,
    )
    val task = promptTask(
      input = "Write a note in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"I skipped the blocked write and did not change the file."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          rejectedTaskId = task.id,
          rejectedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 2,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_760L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("I skipped the blocked write and did not change the file.", resumedResult.stdout)
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertEquals(
      listOf("tool_result", "assistant", "lifecycle"),
      resumedEventSink.events.drop(1).map { event ->
        when (event) {
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayLifecycleEvent -> "lifecycle"
          else -> "other"
        }
      },
    )
    assertEquals(
      listOf("Write:DENIED"),
      resumedEventSink.events
        .filterIsInstance<OpenCrayToolResultEvent>()
        .map { event -> "${event.call.toolName}:${event.result.status.name}" },
    )
    assertTrue(resumedEventSink.events.none { event -> event is OpenCrayToolCallEvent })
    assertTrue(!Files.exists(workspaceRoot.toPath().resolve("note.txt")))
  }

  @Test
  fun runPromptTaskApprovalResumeContinuesRemainingActionsInSameTurnInOriginalOrder() {
    val workspaceRoot = temporaryFolder.newFolder("agent-approval-batch-resume")
    Files.write(
      workspaceRoot.toPath().resolve("input.txt"),
      "seed".toByteArray(StandardCharsets.UTF_8),
    )
    val initialGateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[
          {"type":"progress","text":"Inspecting workspace","stage":"inspect"},
          {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"input.txt"}},
          {"type":"tool_call","tool_name":"Write","arguments":{"file_path":"note.txt","content":"hello"}},
          {"type":"progress","text":"Verifying saved note","stage":"verify"},
          {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"note.txt"}}
        ]}""",
      ),
    )
    val initialEventSink = RecordingEventSink()
    val task = promptTask(
      input = "Inspect the workspace, write a note, then verify it in safe mode.",
      metadata = mapOf("chatMode" to "SAFE"),
    )
    val firstRuntime = OpenCrayAgentRuntime(
      gateway = initialGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 4),
      eventSink = initialEventSink,
      clock = IncrementingClock(start = 3_800L)::next,
    )

    val firstResult = firstRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )
    val resumeState = requireNotNull(
      OpenCrayPromptResumeMetadata.decodeFromMetadata(
        metadata = firstResult.metadata,
        json = Json { ignoreUnknownKeys = true },
      ),
    )

    assertEquals(ExecutionStatus.DENIED, firstResult.status)
    assertEquals(listOf("Inspecting workspace"), initialEventSink.events.filterIsInstance<OpenCrayProgressEvent>().map { it.text })
    assertEquals(listOf("Read", "Write"), initialEventSink.events.filterIsInstance<OpenCrayToolCallEvent>().map { it.call.toolName })
    assertEquals(
      listOf("Read:SUCCESS", "Write:DENIED"),
      initialEventSink.events.filterIsInstance<OpenCrayToolResultEvent>().map { "${it.call.toolName}:${it.result.status.name}" },
    )
    assertEquals(0, resumeState.turnIndex)
    assertEquals(1, resumeState.toolCallCount)
    assertEquals(2, resumeState.nextActionIndex)
    assertEquals(5, resumeState.pendingActions.size)

    val resumedGateway = RecordingGateway(
      outputs = listOf(
        """{"type":"final","answer":"Verified the approved note."}""",
      ),
    )
    val resumedEventSink = RecordingEventSink()
    val resumedRuntime = OpenCrayAgentRuntime(
      gateway = resumedGateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          approvedTaskId = task.id,
          approvedToolName = "Write",
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(
        maxTurns = 4,
        maxToolCalls = 4,
        promptResumeState = resumeState,
      ),
      eventSink = resumedEventSink,
      clock = IncrementingClock(start = 3_900L)::next,
    )

    val resumedResult = resumedRuntime.execute(
      task = task,
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, resumedResult.status)
    assertEquals("Verified the approved note.", resumedResult.stdout)
    assertEquals(listOf("Verifying saved note"), resumedEventSink.events.filterIsInstance<OpenCrayProgressEvent>().map { it.text })
    assertEquals(listOf("Read"), resumedEventSink.events.filterIsInstance<OpenCrayToolCallEvent>().map { it.call.toolName })
    assertEquals(
      listOf("Write:SUCCESS", "Read:SUCCESS"),
      resumedEventSink.events.filterIsInstance<OpenCrayToolResultEvent>().map { "${it.call.toolName}:${it.result.status.name}" },
    )
    assertEquals(1, resumedGateway.requests.size)
    assertEquals("1", resumedGateway.requests.single().metadata["turnIndex"])
    assertTrue(resumedGateway.requests.single().prompt.contains("Protocol note: return only the next step on each turn."))
    assertTrue(resumedGateway.requests.single().prompt.contains("prefer it over the legacy JSON tool_call fallback"))
    assertTrue(resumedGateway.requests.single().prompt.contains("note.txt"))
    assertEquals(
      "hello",
      String(
        Files.readAllBytes(workspaceRoot.toPath().resolve("note.txt")),
        StandardCharsets.UTF_8,
      ),
    )
  }

  @Test
  fun runPromptTaskEmitsLifecycleAssistantAndToolEvents() {
    val workspaceRoot = temporaryFolder.newFolder("agent-event-stream")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "event stream".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
        """{"type":"final","answer":"done"}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 7_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and finish."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(
      listOf("lifecycle", "tool_call", "tool_result", "assistant", "lifecycle"),
      eventSink.events.map { event ->
        when (event) {
          is OpenCrayLifecycleEvent -> "lifecycle"
          is OpenCrayProgressEvent -> "progress"
          is OpenCraySupplementEvent -> "supplement"
          is OpenCrayApprovalEvent -> "approval"
          is OpenCraySubAgentEvent -> "subagent"
          is OpenCrayToolCallEvent -> "tool_call"
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayMemoryRetrievalEvent -> "memory_retrieval"
          is OpenCrayMemoryWriteEvent -> "memory_write"
          is OpenCrayCancellationEvent -> "cancelled"
        }
      },
    )
    assertEquals(OpenCrayRunLifecyclePhase.START, (eventSink.events[0] as OpenCrayLifecycleEvent).phase)
    assertEquals("Read", (eventSink.events[1] as OpenCrayToolCallEvent).call.toolName)
    assertEquals(AgentToolResultStatus.SUCCESS, (eventSink.events[2] as OpenCrayToolResultEvent).result.status)
    assertEquals("done", (eventSink.events[3] as OpenCrayAssistantEvent).text)
    assertTrue((eventSink.events[3] as OpenCrayAssistantEvent).isFinal)
    assertEquals(OpenCrayRunLifecyclePhase.END, (eventSink.events[4] as OpenCrayLifecycleEvent).phase)
    assertFalse(eventSink.events.any { event -> event.taskId.isBlank() || event.runId.isBlank() })
  }

  @Test
  fun runPromptTaskSupportsPublicProgressEventsBeforeToolAndFinal() {
    val workspaceRoot = temporaryFolder.newFolder("agent-progress-events")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "progress-enabled".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"actions":[{"type":"progress","stage":"Planning","text":"Scanning README before reading it."},{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}]}""",
        """{"actions":[{"type":"progress","stage":"Summarizing","text":"Read completed; preparing the final answer."},{"type":"final","answer":"README says progress-enabled."}]}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 7_100L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and keep the user updated."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says progress-enabled.", result.stdout)
    assertEquals(
      listOf(
        "lifecycle",
        "progress",
        "tool_call",
        "tool_result",
        "progress",
        "assistant",
        "lifecycle",
      ),
      eventSink.events.map { event ->
        when (event) {
          is OpenCrayLifecycleEvent -> "lifecycle"
          is OpenCrayProgressEvent -> "progress"
          is OpenCraySupplementEvent -> "supplement"
          is OpenCrayApprovalEvent -> "approval"
          is OpenCraySubAgentEvent -> "subagent"
          is OpenCrayToolCallEvent -> "tool_call"
          is OpenCrayToolResultEvent -> "tool_result"
          is OpenCrayAssistantEvent -> "assistant"
          is OpenCrayMemoryRetrievalEvent -> "memory_retrieval"
          is OpenCrayMemoryWriteEvent -> "memory_write"
          is OpenCrayCancellationEvent -> "cancelled"
        }
      },
    )
    assertEquals(
      listOf(
        "Scanning README before reading it.",
        "Read completed; preparing the final answer.",
      ),
      eventSink.events
        .filterIsInstance<OpenCrayProgressEvent>()
        .map(OpenCrayProgressEvent::text),
    )
    assertEquals(
      listOf("Planning", "Summarizing"),
      eventSink.events
        .filterIsInstance<OpenCrayProgressEvent>()
        .map(OpenCrayProgressEvent::stage),
    )
    assertTrue(gateway.requests[0].prompt.contains("A progress action is a short public status update"))
    assertTrue(gateway.requests[1].prompt.contains("Scanning README before reading it."))
  }

  @Test
  fun runPromptTaskCanAdvanceManagedProcessAcrossTurns() {
    val workspaceRoot = temporaryFolder.newFolder("agent-managed-process")
    val registry = ScriptedProcessRegistry()
    val gateway = DynamicGateway { index ->
      when (index) {
        0 -> """{"type":"tool_call","tool_name":"ProcessStart","arguments":{"command":"npm","args":["run","dev"],"working_directory":"."}}"""
        1 -> {
          val processId = registry.startedProcessId ?: error("ProcessStart should have run before ProcessWait.")
          """{"type":"tool_call","tool_name":"ProcessWait","arguments":{"process_id":"$processId","timeout_ms":250}}"""
        }

        2 -> """{"type":"final","answer":"Managed process is ready."}"""
        else -> error("Unexpected managed-process turn $index.")
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          processRegistry = registry,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 5, maxToolCalls = 3),
      clock = IncrementingClock(start = 7_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Start the dev server and wait until it is ready.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Managed process is ready.", result.stdout)
    assertEquals("3", result.metadata["turnCount"])
    assertEquals("2", result.metadata["toolCallCount"])
    assertEquals(listOf(250L), registry.waitTimeouts)
    assertTrue(gateway.requests[1].prompt.contains("ProcessStart"))
    assertTrue(gateway.requests[1].prompt.contains("process_id=${registry.startedProcessId}"))
    assertTrue(gateway.requests[1].prompt.contains("status=running"))
    assertTrue(gateway.requests[2].prompt.contains("ProcessWait"))
    assertTrue(gateway.requests[2].prompt.contains("server ready"))
    assertTrue(gateway.requests[2].prompt.contains("exit_code=0"))
  }

  @Test
  fun runPromptTaskCanStartManagedPythonScriptAcrossTurns() {
    val workspaceRoot = temporaryFolder.newFolder("agent-managed-python")
    Files.createDirectories(workspaceRoot.toPath().resolve("scripts"))
    Files.write(
      workspaceRoot.toPath().resolve("scripts").resolve("run.py"),
      "print('hello')".toByteArray(StandardCharsets.UTF_8),
    )
    val registry = ScriptedProcessRegistry()
    val gateway = DynamicGateway { index ->
      when (index) {
        0 -> """{"type":"tool_call","tool_name":"ProcessStart","arguments":{"script_path":"scripts/run.py","python_executable":"python3","args":["--flag"]}}"""
        1 -> {
          val processId = registry.startedProcessId ?: error("ProcessStart should have run before ProcessWait.")
          """{"type":"tool_call","tool_name":"ProcessWait","arguments":{"process_id":"$processId","timeout_ms":250}}"""
        }

        2 -> """{"type":"final","answer":"Managed python process finished."}"""
        else -> error("Unexpected managed-python turn $index.")
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
          processRegistry = registry,
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 5, maxToolCalls = 3),
      clock = IncrementingClock(start = 7_750L)::next,
    )

    val result = runtime.execute(
      task = promptTask(
        input = "Run the Python script in the background and wait for it to finish.",
        metadata = mapOf("chatMode" to "DEVELOPER"),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("Managed python process finished.", result.stdout)
    assertTrue(gateway.requests[1].prompt.contains("python_exec"))
    assertTrue(gateway.requests[1].prompt.contains("scripts/run.py"))
    assertTrue(gateway.requests[1].prompt.contains("python3"))
  }

  @Test
  fun runPromptTaskRecoversFromProtocolErrorsWithoutSurfacingRawPayload() {
    val workspaceRoot = temporaryFolder.newFolder("agent-protocol-recovery")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"unexpected":"shape"}""",
        """{"type":"final","answer":"clean answer after retry"}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 8_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Use tools when needed, then answer cleanly."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("clean answer after retry", result.stdout)
    assertEquals("2", result.metadata["turnCount"])
    assertEquals(
      listOf("clean answer after retry"),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::text),
    )
    assertTrue(
      gateway.requests[1].prompt.contains(
        "Protocol error: either use native tool calling or return exactly one JSON object whose legacy action is progress, tool_call, or final.",
      ),
    )
    assertTrue(gateway.requests[1].prompt.contains("""{"unexpected":"shape"}"""))
    assertFalse(result.stdout.contains("unexpected"))
  }

  @Test
  fun runPromptTaskExecutesSequentialToolCallsAndIgnoresMixedFinalContent() {
    val workspaceRoot = temporaryFolder.newFolder("agent-mixed-turn")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "mixed turn".toByteArray(StandardCharsets.UTF_8),
    )
    Files.write(
      workspaceRoot.toPath().resolve("NOTES.md"),
      "second file".toByteArray(StandardCharsets.UTF_8),
    )
    val gateway = RecordingGateway(
      outputs = listOf(
        """
        {"type":"tool_call","tool_name":"Read","reason":"Need README contents first.","arguments":{"file_path":"README.md"}}
        {"type":"tool_call","tool_name":"Read","arguments":{"file_path":"NOTES.md"}}
        {"type":"final","answer":"premature answer that should be ignored"}
        """.trimIndent(),
        """{"type":"final","answer":"README says mixed turn and NOTES says second file"}""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 8_250L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README says mixed turn and NOTES says second file", result.stdout)
    assertTrue(gateway.requests[1].prompt.contains("mixed turn"))
    assertTrue(gateway.requests[1].prompt.contains("second file"))
    assertTrue(gateway.requests[1].prompt.contains("Protocol note: return only the next step on each turn."))
    assertTrue(gateway.requests[1].prompt.contains("prefer it over the legacy JSON tool_call fallback"))
    assertEquals("2", result.metadata["toolCallCount"])
    assertEquals(
      listOf("README says mixed turn and NOTES says second file"),
      eventSink.events
        .filterIsInstance<OpenCrayAssistantEvent>()
        .map(OpenCrayAssistantEvent::text),
    )
    assertEquals(
      listOf("Read", "Read"),
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .map { event -> event.call.toolName },
    )
    assertEquals(
      "Need README contents first.",
      eventSink.events
        .filterIsInstance<OpenCrayToolCallEvent>()
        .first()
        .call
        .reason,
    )
    assertFalse(result.stdout.contains("premature answer"))
  }

  @Test
  fun runPromptTaskFailsAfterRepeatedProtocolErrorsWithoutAssistantLeak() {
    val workspaceRoot = temporaryFolder.newFolder("agent-protocol-failure")
    val gateway = RecordingGateway(
      outputs = listOf(
        """{"unexpected":"first"}""",
        """not valid json at all""",
      ),
    )
    val eventSink = RecordingEventSink()
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 2, maxToolCalls = 2),
      eventSink = eventSink,
      clock = IncrementingClock(start = 8_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Return a valid JSON action."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("MODEL_ACTION_FORMAT_ERROR", result.errorCode)
    assertEquals("protocol_error_exhausted", result.metadata["responseFormat"])
    assertEquals("2", result.metadata["protocolErrorCount"])
    assertTrue(eventSink.events.none { event -> event is OpenCrayAssistantEvent })
    assertTrue(result.stdout.isBlank())
    assertFalse(result.errorMessage.orEmpty().contains("unexpected"))
    assertFalse(result.metadata["lastProtocolError"].orEmpty().contains("unexpected"))
  }

  @Test
  fun runPromptTaskLlmFailurePreservesContextMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("agent-llm-failure")
    val gateway = FailingGateway(
      status = LiteLlmGatewayStatus.FAILED,
      errorCode = "UPSTREAM_502",
      errorMessage = "Provider failure",
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
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Earlier question."),
          ),
        ),
      ),
      clock = IncrementingClock(start = 4_000L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Need a fresh answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("Provider failure", result.errorMessage)
    assertEquals("1", result.metadata["turnCount"])
    assertEquals("0", result.metadata["toolCallCount"])
    assertEquals("2", result.metadata["contextSourceMessageCount"])
    assertEquals("2", result.metadata["contextWindowMessageCount"])
    assertEquals("2", result.metadata["contextMessageCount"])
  }

  @Test
  fun runPromptTaskRecordsToolDiagnosticsWhenFinalReplyIsLostAfterSuccessfulTool() {
    val workspaceRoot = temporaryFolder.newFolder("agent-tool-diagnostics")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "diagnostic preview".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "fake",
      model = "fake-model",
      attemptIndex = 0,
    )
    var now = 9_000L
    var requestIndex = 0
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        val startedAt = now++
        val finishedAt = now++
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            outputText = """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""",
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 71,
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
              ),
            ),
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.FAILED,
            completionMode = LiteLlmCompletionMode.TERMINAL,
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.FAILED,
                errorCode = "PROVIDER_EMPTY_RESPONSE",
                startedAtEpochMs = startedAt,
                finishedAtEpochMs = finishedAt,
              ),
            ),
            errorCode = "PROVIDER_EMPTY_RESPONSE",
            errorMessage = "Provider returned an empty completion payload.",
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "openai_empty",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "false",
            ),
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 9_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.FAILED, result.status)
    assertEquals("PROVIDER_EMPTY_RESPONSE", result.errorCode)
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("openai_empty", result.metadata[LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.PARSED_TOOL_CALL_OBSERVED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_SUCCEEDED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.TOOL_CALL_EVENT_EMITTED])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.TOOL_RESULT_EVENT_EMITTED])
    assertEquals("Read", result.metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME])
  }

  @Test
  fun runPromptTaskExecutesStructuredToolCallWithoutTextProtocolPayload() {
    val workspaceRoot = temporaryFolder.newFolder("agent-structured-tool-call")
    Files.write(
      workspaceRoot.toPath().resolve("README.md"),
      "structured provider path".toByteArray(StandardCharsets.UTF_8),
    )
    val selection = LiteLlmRouteSelectionMetadata(
      profileId = "test-profile",
      routeId = "test-route",
      providerId = "anthropic",
      model = "claude-test",
      attemptIndex = 0,
    )
    var requestIndex = 0
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    val gateway = object : LiteLlmGateway {
      override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
        requests += request
        return if (requestIndex++ == 0) {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              toolCalls = listOf(
                LiteLlmStructuredToolCall(
                  id = "toolu_1",
                  toolName = "Read",
                  arguments = JsonObject(
                    mapOf("file_path" to JsonPrimitive("README.md")),
                  ),
                ),
              ),
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 0,
                startedAtEpochMs = 10_000L,
                finishedAtEpochMs = 10_001L,
              ),
            ),
            startedAtEpochMs = 10_000L,
            finishedAtEpochMs = 10_001L,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "anthropic_tool_use",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "true",
            ),
          )
        } else {
          LiteLlmGatewayResult(
            requestId = request.requestId,
            status = LiteLlmGatewayStatus.SUCCESS,
            completionMode = LiteLlmCompletionMode.PRIMARY,
            completion = LiteLlmStructuredCompletion(
              finalText = "README shows structured provider path.",
              rawText = "README shows structured provider path.",
            ),
            selectedRoute = selection,
            attempts = listOf(
              LiteLlmAttemptRecord(
                route = selection,
                outcome = LiteLlmAttemptOutcome.SUCCESS,
                outputChars = 31,
                startedAtEpochMs = 10_002L,
                finishedAtEpochMs = 10_003L,
              ),
            ),
            startedAtEpochMs = 10_002L,
            finishedAtEpochMs = 10_003L,
            metadata = mapOf(
              LiteLlmMetadataKeys.PROVIDER_RESPONSE_SHAPE to "anthropic_text",
              LiteLlmMetadataKeys.NATIVE_TOOL_CALL_OBSERVED to "false",
            ),
          )
        }
      }
    }
    val runtime = OpenCrayAgentRuntime(
      gateway = gateway,
      toolDispatcher = OpenCrayToolDispatcher(
        OpenCrayToolDispatcherConfig(
          workspaceRoots = setOf(workspaceRoot.toPath()),
        ),
      ),
      config = OpenCrayAgentRuntimeConfig(maxTurns = 4, maxToolCalls = 2),
      clock = IncrementingClock(start = 10_500L)::next,
    )

    val result = runtime.execute(
      task = promptTask(input = "Read the README and answer."),
      hooks = runtimeHooks(),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("README shows structured provider path.", result.stdout)
    assertEquals("native_text_final", result.metadata["responseFormat"])
    assertEquals("true", result.metadata[LiteLlmMetadataKeys.NATIVE_TOOL_CALL_REQUESTED])
    assertEquals("false", result.metadata[LiteLlmMetadataKeys.FALLBACK_PARSER_ATTEMPTED])
    assertTrue(requests[0].tools.any { tool -> tool.name == "Read" })
    assertTrue(requests[0].messages.any { message -> message.content?.contains("[Tool Protocol]") == true })
    assertTrue(requests[0].messages.any { message -> message.content == "Read the README and answer." })
    val secondRequestMessages = requests[1].messages
    assertTrue(
      secondRequestMessages.any { message ->
        message.role == LiteLlmGatewayMessageRole.ASSISTANT &&
          message.toolCalls.singleOrNull()?.toolName == "Read" &&
          message.toolCalls.singleOrNull()?.id == "toolu_1"
      },
    )
    assertTrue(
      secondRequestMessages.any { message ->
        message.role == LiteLlmGatewayMessageRole.TOOL &&
          message.toolResult?.toolCallId == "toolu_1" &&
          message.toolResult?.toolName == "Read" &&
          message.toolResult?.content?.contains("\"tool_name\"") == true &&
          message.toolResult?.content?.contains("Read") == true
      },
    )
    assertEquals("Read", result.metadata[LiteLlmMetadataKeys.LAST_SUCCESSFUL_TOOL_NAME])
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

  private fun promptTask(
    input: String,
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.PROMPT,
    input = input,
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "ALLOW_TEST",
    ),
    metadata = metadata,
    createdAtEpochMs = 500L,
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

  private fun runtimeHooks(
    onSuspend: (SuspensionRequest) -> Unit = {},
  ): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in OpenCrayAgentRuntimeTest.") },
    requestSuspend = onSuspend,
  )

  private class IncrementingClock(
    start: Long,
  ) {
    private var current: Long = start

    fun next(): Long = current++
  }

  private class RecordingGateway(
    outputs: List<String>,
  ) : LiteLlmGateway {
    private val queuedOutputs = ArrayDeque(outputs)
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 2_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = queuedOutputs.removeFirstOrNull()
        ?: error("No fake LLM output left for request ${request.requestId}.")
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class DynamicGateway(
    private val outputProvider: (Int) -> String,
  ) : LiteLlmGateway {
    val requests = mutableListOf<LiteLlmGatewayRequest>()
    private var now = 8_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      requests += request
      val output = outputProvider(requests.lastIndex)
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = LiteLlmGatewayStatus.SUCCESS,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = output,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.SUCCESS,
            outputChars = output.length,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class FailingGateway(
    private val status: LiteLlmGatewayStatus,
    private val errorCode: String,
    private val errorMessage: String,
  ) : LiteLlmGateway {
    private var now = 5_000L

    override fun execute(request: LiteLlmGatewayRequest): LiteLlmGatewayResult {
      val selection = LiteLlmRouteSelectionMetadata(
        profileId = "test-profile",
        routeId = "test-route",
        providerId = "fake",
        model = "fake-model",
        attemptIndex = 0,
      )
      val startedAt = now++
      val finishedAt = now++
      return LiteLlmGatewayResult(
        requestId = request.requestId,
        status = status,
        completionMode = LiteLlmCompletionMode.PRIMARY,
        outputText = null,
        errorCode = errorCode,
        errorMessage = errorMessage,
        selectedRoute = selection,
        attempts = listOf(
          LiteLlmAttemptRecord(
            route = selection,
            outcome = LiteLlmAttemptOutcome.FAILED,
            errorCode = errorCode,
            outputChars = 0,
            startedAtEpochMs = startedAt,
            finishedAtEpochMs = finishedAt,
          ),
        ),
        startedAtEpochMs = startedAt,
        finishedAtEpochMs = finishedAt,
      )
    }
  }

  private class RecordingEventSink : OpenCrayAgentRuntimeEventSink {
    val events = mutableListOf<OpenCrayAgentRunEvent>()

    override fun onRunEvent(task: AgentTask, event: OpenCrayAgentRunEvent) {
      events += event
    }
  }

  private class ScriptedProcessRegistry : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()
    val waitTimeouts = mutableListOf<Long>()
    var startedProcessId: String? = null
      private set

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startedProcessId = request.processId
      return ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        metadata = request.metadata,
      ).also { snapshot ->
        snapshotsById[request.processId] = snapshot
      }
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      waitTimeouts += timeoutMs
      val existing = snapshotsById[processId] ?: return null
      return existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "server ready",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
      ).also { snapshot ->
        snapshotsById[processId] = snapshot
      }
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]
  }
}
