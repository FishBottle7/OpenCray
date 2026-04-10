package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapFileTrace
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionTrace
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallFilterReason
import com.opencray.runtime.memory.MemoryRecallOmissionReason
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallOmittedTrace
import com.opencray.runtime.memory.MemoryRecallSelectedTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryTrace
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.skills.VisibleSkillTrace
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnUserAffect
import com.opencray.runtime.workingstate.WorkingState
import com.opencray.runtime.workingstate.WorkingStateEntry
import com.opencray.runtime.workingstate.WorkingStateObjective
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerTest {
  @Test
  fun assembleBuildsNamedSystemAndTaskLayers() {
    val contextManager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 2,
          maxCharsPerMessage = 48,
        ),
      ),
    )
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(
            sessionPolicyText = "Keep the current session aligned with earlier decisions.",
            soulProfile = RuntimeSoulProfile(
              presetName = "BUILDER",
              displayName = "Night Shift",
              customGuidance = "Be terse and implementation-first.",
            ),
          ),
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Read",
              description = "Read a file from the workspace.",
            ),
            AgentToolDefinition(
              name = "Bash",
              description = "Run a shell command.",
            ),
            AgentToolDefinition(
              name = "python_exec",
              description = "Run a workspace Python script.",
            ),
            AgentToolDefinition(
              name = "ProcessStart",
              description = "Start a managed process.",
            ),
            AgentToolDefinition(
              name = "TodoWrite",
              description = "Keep a short live plan for multi-step work.",
            ),
            AgentToolDefinition(
              name = "WebFetch",
              description = "Fetch a web page.",
            ),
            AgentToolDefinition(
              name = "GenerateImage",
              description = "Generate an image.",
            ),
            AgentToolDefinition(
              name = "SynthesizeSpeech",
              description = "Generate a voice clip.",
            ),
            AgentToolDefinition(
              name = "import_chat_attachment",
              description = "Import a chat attachment into the workspace.",
            ),
            AgentToolDefinition(
              name = "search_workspace_document",
              description = "Search a workspace PDF directly.",
            ),
            AgentToolDefinition(
              name = "inspect_workspace_package",
              description = "Inspect a ZIP-based workspace package directly.",
            ),
            AgentToolDefinition(
              name = "extract_workspace_package",
              description = "Extract selected files from a ZIP-based workspace package.",
            ),
            AgentToolDefinition(
              name = "view_workspace_document",
              description = "Inspect a workspace image or PDF directly.",
            ),
            AgentToolDefinition(
              name = "view_workspace_image",
              description = "Inspect a workspace image directly.",
            ),
            AgentToolDefinition(
              name = "view_workspace_pdf",
              description = "Inspect a workspace PDF directly.",
            ),
            AgentToolDefinition(
              name = "list_subagents",
              description = "Inspect delegated child handles currently known to this runtime.",
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
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Identity]"))
    assertTrue(prompt.systemPrompt.contains("[Runtime Rules]"))
    assertTrue(prompt.systemPrompt.contains("[Session Policy]"))
    assertTrue(prompt.systemPrompt.contains("[Personalization]"))
    assertTrue(prompt.systemPrompt.contains("display_name=Night Shift"))
    assertTrue(prompt.taskPrompt.contains("[Tool Protocol]"))
    assertTrue(prompt.taskPrompt.contains("On each turn, return exactly one JSON object"))
    assertTrue(prompt.taskPrompt.contains("the runtime will execute it, append the tool result"))
    assertTrue(prompt.taskPrompt.contains("If you need multiple tools, call only the next tool now"))
    assertTrue(prompt.taskPrompt.contains("Keep the user updated with short public commentary as you work"))
    assertTrue(prompt.taskPrompt.contains("Before the first tool call, give a brief public plan"))
    assertTrue(prompt.taskPrompt.contains("Before making tool calls, send a brief public preamble"))
    assertTrue(prompt.taskPrompt.contains("use a commentary action for that preamble"))
    assertTrue(prompt.taskPrompt.contains("A commentary action is a short public status update"))
    assertTrue(prompt.taskPrompt.contains("Group related tool reads or searches under one preamble"))
    assertTrue(prompt.taskPrompt.contains("connect the next preamble to that new context"))
    assertTrue(prompt.taskPrompt.contains("use TodoWrite to keep a short live plan"))
    assertTrue(prompt.taskPrompt.contains("Omit todos to read the current plan without mutating it"))
    assertTrue(prompt.taskPrompt.contains("at most one in_progress item"))
    assertTrue(prompt.taskPrompt.contains("before returning the final answer make sure the plan state is accurate"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"Bash"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"python_exec"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"WebFetch"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"GenerateImage"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"SynthesizeSpeech"))
    assertTrue(prompt.taskPrompt.contains("Use Bash for one-off shell commands that do not require Python"))
    assertTrue(prompt.taskPrompt.contains("prefer WebSearch when a search provider is configured"))
    assertTrue(prompt.taskPrompt.contains("use PowerShell syntax on Windows hosts"))
    assertTrue(prompt.taskPrompt.contains("prefer ProcessStart and then use ProcessRead or ProcessWait"))
    assertTrue(prompt.taskPrompt.contains("prefer python_exec instead of Bash"))
    assertTrue(prompt.taskPrompt.contains("For Python runtime inspection or diagnostics such as version checks, sys.path, imports, or environment behavior, do not use Bash"))
    assertTrue(prompt.taskPrompt.contains("use ProcessStart with script_path only when the runtime supports managed Python process launches"))
    assertTrue(prompt.taskPrompt.contains("Do not use Bash to invoke python, python3, or py for workspace scripts or Python-related diagnostics"))
    assertTrue(prompt.taskPrompt.contains("reason or justification"))
    assertTrue(prompt.taskPrompt.contains("it must not include a final answer"))
    assertTrue(prompt.taskPrompt.contains("\"attachments\":[{\"relative_path\":\"docs/diagram.png\",\"kind\":\"image\"}]"))
    assertTrue(prompt.taskPrompt.contains("\"attachments\":[{\"relative_path\":\"docs/report.pdf\",\"kind\":\"file\"}]"))
    assertTrue(prompt.taskPrompt.contains("\"attachments\":[{\"artifact_id\":\"artifact-example-1234abcd\",\"kind\":\"image\"}]"))
    assertTrue(prompt.taskPrompt.contains("\"attachments\":[{\"chat_attachment_id\":\"user-image-1\",\"kind\":\"image\"}]"))
    assertTrue(prompt.taskPrompt.contains("When a tool result produces attachment artifacts"))
    assertTrue(prompt.taskPrompt.contains("chat_attachment_id"))
    assertTrue(prompt.taskPrompt.contains("Uploaded chat attachments are chat resources, not workspace files."))
    assertTrue(prompt.taskPrompt.contains("If the model can already inspect an uploaded image directly, do not import it unless you need a workspace copy."))
    assertTrue(prompt.taskPrompt.contains("Use import_chat_attachment only when you intentionally want to save one existing chat attachment into the workspace."))
    assertTrue(prompt.taskPrompt.contains("call search_workspace_document instead of guessing from the filename"))
    assertTrue(prompt.taskPrompt.contains("search_workspace_document searches workspace PDFs locally and returns matching page numbers and excerpts."))
    assertTrue(prompt.taskPrompt.contains("Use query, pages, page_from, and page_to to narrow the scan whenever you can."))
    assertTrue(prompt.taskPrompt.contains("call inspect_workspace_package before guessing from the filename"))
    assertTrue(prompt.taskPrompt.contains("inspect_workspace_package lists internal entries"))
    assertTrue(prompt.taskPrompt.contains("Use glob and preview_entries to narrow inspection"))
    assertTrue(prompt.taskPrompt.contains("call extract_workspace_package with explicit entries or glob"))
    assertTrue(prompt.taskPrompt.contains("extract_workspace_package requires entries or glob"))
    assertTrue(prompt.taskPrompt.contains("never defaults to full-package extraction"))
    assertTrue(prompt.taskPrompt.contains("call view_workspace_document instead of guessing from the path, filename, or nearby text."))
    assertTrue(prompt.taskPrompt.contains("prefer it over the format-specific workspace view tools"))
    assertTrue(prompt.taskPrompt.contains("view_workspace_document attaches that workspace image or PDF into the next model turn for direct inspection."))
    assertTrue(prompt.taskPrompt.contains("After calling view_workspace_document, wait for the next turn and inspect the attached document directly before taking further action."))
    assertTrue(prompt.taskPrompt.contains("If you need to inspect what a readable workspace image actually contains, call view_workspace_image instead of guessing from the path, filename, or nearby text."))
    assertTrue(prompt.taskPrompt.contains("view_workspace_image attaches that workspace image into the next model turn for direct visual inspection."))
    assertTrue(prompt.taskPrompt.contains("After calling view_workspace_image, wait for the next turn and inspect the attached image directly before taking further action."))
    assertTrue(prompt.taskPrompt.contains("If you need to inspect what a readable workspace PDF actually contains, call view_workspace_pdf instead of guessing from the path, filename, or nearby text."))
    assertTrue(prompt.taskPrompt.contains("view_workspace_pdf attaches that workspace PDF into the next model turn for direct inspection when the current model accepts PDF inputs."))
    assertTrue(prompt.taskPrompt.contains("After calling view_workspace_pdf, wait for the next turn and inspect the attached PDF directly before taking further action."))
    assertTrue(prompt.taskPrompt.contains("Do not rely on markdown alone to send an attachment"))
    assertTrue(prompt.taskPrompt.contains("you must do both"))
    assertTrue(prompt.taskPrompt.contains("For ordinary file cards, you may omit markdown and just attach the file in the attachments array."))
    assertTrue(prompt.taskPrompt.contains("attachment:<token>"))
    assertTrue(prompt.taskPrompt.contains("relative_path, artifact_id, or chat_attachment_id"))
    assertTrue(prompt.taskPrompt.contains("Do not use a generic placeholder such as attachment:artifact"))
    assertTrue(prompt.taskPrompt.contains("Use list_subagents to inspect the delegated child registry"))
    assertTrue(prompt.taskPrompt.contains("Generated speech should usually be attached with kind=voice"))
    assertTrue(prompt.taskPrompt.contains("Available tools:"))
    assertTrue(prompt.taskPrompt.contains("[Task Metadata]"))
    assertTrue(prompt.taskPrompt.contains("[Conversation]"))
    assertTrue(prompt.taskPrompt.contains("[Compaction Summary]"))
    assertTrue(prompt.taskPrompt.contains("Compacted 1 older message(s) outside the active transcript window."))
    assertTrue(prompt.taskPrompt.contains("task_id=task-context"))
    assertTrue(prompt.taskPrompt.contains("Omitted 1 older message(s)"))
    assertTrue(prompt.contextPrompt.contains("[Task Metadata]"))
    assertFalse(prompt.contextPrompt.contains("[Conversation]"))
    assertFalse(prompt.contextPrompt.contains("Latest request."))
    assertEquals(3, prompt.report.sourceTranscriptMessageCount)
    assertEquals(2, prompt.report.windowedTranscriptMessageCount)
    assertEquals(2, prompt.report.transcriptMessageCount)
    assertEquals(1, prompt.report.omittedTranscriptMessageCount)
    assertEquals(1, prompt.report.truncatedTranscriptMessageCount)
    assertEquals(0, prompt.report.prunedTranscriptMessageCount)
    assertEquals(0, prompt.report.rewrittenTranscriptMessageCount)
    assertFalse(prompt.report.pruningSummaryIncluded)
    assertEquals(1, prompt.report.compactedTranscriptMessageCount)
    assertTrue(prompt.report.compactionSummaryIncluded)
    assertEquals(0, prompt.report.injectedMemoryRecordCount)
  }

  @Test
  fun assembleReadOnlyToolProtocolOmitsUnavailableMutableAndExecutionTools() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Read",
              description = "Read a file from the workspace.",
            ),
            AgentToolDefinition(
              name = "LS",
              description = "List files in the workspace.",
            ),
            AgentToolDefinition(
              name = "Grep",
              description = "Search file contents.",
            ),
            AgentToolDefinition(
              name = "Glob",
              description = "Match workspace paths.",
            ),
          ),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo carefully."),
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("tool_name\":\"Read"))
    assertFalse(prompt.taskPrompt.contains("tool_name\":\"Bash"))
    assertFalse(prompt.taskPrompt.contains("tool_name\":\"python_exec"))
    assertFalse(prompt.taskPrompt.contains("tool_name\":\"WebFetch"))
    assertFalse(prompt.taskPrompt.contains("tool_name\":\"Write"))
    assertFalse(prompt.taskPrompt.contains("TodoWrite"))
    assertFalse(prompt.taskPrompt.contains("tool_name\":\"ProcessStart"))
    assertFalse(prompt.taskPrompt.contains("Use Bash for one-off shell commands"))
    assertFalse(prompt.taskPrompt.contains("prefer WebSearch when a search provider is configured"))
    assertFalse(prompt.taskPrompt.contains("prefer ProcessStart and then use ProcessRead or ProcessWait"))
    assertFalse(prompt.taskPrompt.contains("prefer python_exec instead of Bash"))
    assertFalse(prompt.taskPrompt.contains("Do not use Bash to invoke python, python3, or py"))
  }

  @Test
  fun assembleSubagentToolGuidanceTreatsWaitAgentAsObserver() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Task",
              description = "Delegate one bounded subtask to a child runtime.",
            ),
            AgentToolDefinition(
              name = "spawn_agent",
              description = "Start one bounded subagent handle immediately.",
            ),
            AgentToolDefinition(
              name = "wait_agent",
              description = "Wait for one delegated child handle.",
            ),
            AgentToolDefinition(
              name = "send_input",
              description = "Queue a follow-up message for a delegated child.",
            ),
            AgentToolDefinition(
              name = "close_agent",
              description = "Close one delegated child handle.",
            ),
            AgentToolDefinition(
              name = "list_subagents",
              description = "Inspect delegated child handles currently known to this runtime.",
            ),
          ),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Delegate this work."),
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("Use spawn_agent when you need an explicit child handle and want that child to start immediately."))
    assertTrue(prompt.taskPrompt.contains("Use wait_agent to block until a running child reaches its latest stable state and harvest its result."))
    assertTrue(prompt.taskPrompt.contains("After user approval unlocks a paused child, the runtime resumes it through the detached recovery path; use wait_agent later to observe the new stable state."))
    assertFalse(prompt.taskPrompt.contains("Use wait_agent to block until a running child reaches its latest stable state, or to resume a paused child after user approval."))
  }

  @Test
  fun assembleIncludesPruningSummaryLayerWhenTranscriptWasPruned() {
    val contextManager = ContextManager(
      contextPruner = ContextPruner(
        ContextPrunerConfig(
          maxToolChars = 128,
          maxToolLines = 4,
          maxAttachmentChars = 64,
          maxPreviewChars = 64,
        ),
      ),
    )
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = emptyList(),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the prior output."),
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
            RuntimeConversationMessage(RuntimeConversationRole.TOOL, "Repeated note."),
            RuntimeConversationMessage(
              RuntimeConversationRole.TOOL,
              "data:image/png;base64," + "A".repeat(160),
            ),
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Pruning Summary]"))
    assertTrue(prompt.taskPrompt.contains("removed=0, rewritten=1"))
    assertTrue(prompt.report.pruningSummaryIncluded)
    assertEquals(0, prompt.report.prunedTranscriptMessageCount)
    assertEquals(1, prompt.report.rewrittenTranscriptMessageCount)
    assertEquals(1, prompt.report.attachmentLikeTranscriptRewriteCount)
  }

  @Test
  fun assembleNativeToolCallingProtocolPrefersNativeToolsAndPlainFinalAnswers() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          nativeToolCallingEnabled = true,
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Read",
              description = "Read a file from the workspace.",
            ),
          ),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo carefully."),
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("Native tool calling is enabled for this run."))
    assertTrue(prompt.taskPrompt.contains("Keep the user updated with short public commentary as you work"))
    assertTrue(prompt.taskPrompt.contains("Before the first tool call, give a brief public plan"))
    assertTrue(prompt.taskPrompt.contains("Before making tool calls, send a brief public preamble"))
    assertTrue(prompt.taskPrompt.contains("use the provider's native tool-calling interface"))
    assertTrue(prompt.taskPrompt.contains("put that preamble in assistant text alongside the native tool call"))
    assertTrue(prompt.taskPrompt.contains("return a plain assistant text answer"))
    assertFalse(prompt.taskPrompt.contains("legacy JSON fallback"))
    assertFalse(prompt.taskPrompt.contains("\"type\":\"final\""))
    assertTrue(prompt.contextPrompt.contains("[Tool Protocol]"))
    assertFalse(prompt.contextPrompt.contains("[Conversation]"))
  }

  @Test
  fun assembleKeepsNativeToolGuidanceWhenLegacyFallbackCompatibilityIsEnabled() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          nativeToolCallingEnabled = true,
          legacyJsonFallbackEnabled = true,
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Read",
              description = "Read a file from the workspace.",
            ),
          ),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the repo carefully."),
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("Keep the user updated with short public commentary as you work"))
    assertTrue(prompt.taskPrompt.contains("Before the first tool call, give a brief public plan"))
    assertTrue(prompt.taskPrompt.contains("Before making tool calls, send a brief public preamble"))
    assertTrue(prompt.taskPrompt.contains("use the provider's native tool-calling interface"))
    assertFalse(prompt.taskPrompt.contains("legacy JSON fallback"))
    assertFalse(prompt.taskPrompt.contains("\"type\":\"tool_call\""))
    assertFalse(prompt.taskPrompt.contains("\"type\":\"final\""))
  }

  @Test
  fun assembleOmitsOptionalLayersWhenEmpty() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity that still needs to fit even under a very small model budget. ".repeat(60).trim(),
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Identity]"))
    assertFalse(prompt.systemPrompt.contains("[Session Policy]"))
    assertFalse(prompt.systemPrompt.contains("[Personalization]"))
    assertFalse(prompt.taskPrompt.contains("[Compaction Summary]"))
    assertTrue(prompt.taskPrompt.contains("No prior conversation context."))
    assertEquals(0, prompt.report.sourceTranscriptMessageCount)
    assertEquals(0, prompt.report.windowedTranscriptMessageCount)
    assertEquals(0, prompt.report.transcriptMessageCount)
    assertFalse(prompt.report.pruningSummaryIncluded)
    assertFalse(prompt.report.compactionSummaryIncluded)
    assertEquals(0, prompt.report.injectedMemoryRecordCount)
  }

  @Test
  fun assembleIncludesTurnResponsePolicyAsDedicatedSystemLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            soulProfile = RuntimeSoulProfile(
              presetName = "WARM",
              extensions = mapOf(
                "supportive_reassurance_allowed" to "true",
                "reassurance_preference_offset" to "1",
              ),
            ),
            turnSemanticSignal = SoulTurnSemanticSignal(
              isTaskBearingRequest = false,
              userAffect = SoulTurnUserAffect.DISTRESSED,
              userRequestsRelationalSupport = true,
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Turn Response Policy]"))
    assertTrue(prompt.systemPrompt.contains("reassurance_mode=supportive"))
    assertTrue(prompt.systemPrompt.contains("response_shape=supportive_reply"))
  }

  @Test
  fun assembleOmitsHostOnlyTaskMetadataFromPrompt() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask().copy(
            metadata = mapOf(
              "chatMode" to "AUTO",
              "_host.pendingMessageId" to "assistant-1",
            ),
          ),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("chatMode=AUTO"))
    assertFalse(prompt.taskPrompt.contains("_host.pendingMessageId"))
    assertFalse(prompt.taskPrompt.contains("assistant-1"))
  }

  @Test
  fun assembleInjectsRetrievedMemoryAsDedicatedContextLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
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
              matchedRecordCount = 3,
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
                  MemoryRecallSelectedTrace(
                    id = "memory-project",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    score = 360,
                    matchedTerms = listOf("gradle"),
                    contentPreview = "Project uses the Gradle wrapper from the repo root.",
                  ),
                ),
                omitted = listOf(
                  MemoryRecallOmittedTrace(
                    id = "memory-omitted",
                    kind = MemoryKind.PROJECT_FACT,
                    scope = MemoryScope.WORKSPACE,
                    score = 280,
                    matchedTerms = listOf("gradle"),
                    omissionReason = MemoryRecallOmissionReason.MAX_RECORDS,
                    contentPreview = "Project keeps legacy Gradle scripts under scripts/.",
                  ),
                ),
                filteredCounts = mapOf(
                  MemoryRecallFilterReason.SCOPE_MISMATCH to 1,
                ),
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Retrieved Memory]"))
    assertTrue(prompt.taskPrompt.contains("Default to concise Chinese replies."))
    assertTrue(prompt.taskPrompt.contains("Project uses the Gradle wrapper from the repo root."))
    assertTrue(prompt.taskPrompt.contains("Omitted 1 additional memory record(s) due to recall budget."))
    assertFalse(prompt.taskPrompt.contains("[Compaction Summary]"))
    assertTrue(prompt.taskPrompt.indexOf("[Retrieved Memory]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertFalse(prompt.report.pruningSummaryIncluded)
    assertEquals(3, prompt.report.matchedMemoryRecordCount)
    assertEquals(2, prompt.report.injectedMemoryRecordCount)
    assertEquals(1, prompt.report.omittedMemoryRecordCount)
    assertFalse(prompt.report.compactionSummaryIncluded)
    assertEquals(listOf("chinese", "gradle"), prompt.report.memoryRecallTrace.queryTerms)
    assertEquals(listOf("memory-user", "memory-project"), prompt.report.memoryRecallTrace.selected.map { trace -> trace.id })
    assertEquals(listOf("memory-omitted"), prompt.report.memoryRecallTrace.omitted.map { trace -> trace.id })
    assertEquals(1, prompt.report.memoryRecallTrace.filteredCounts[MemoryRecallFilterReason.SCOPE_MISMATCH])
  }

  @Test
  fun assembleInjectsWorkingStateAsDedicatedContextLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          runId = "run-prompt-1",
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            workingState = WorkingState(
              objective = WorkingStateObjective(
                taskId = "task-prompt-1",
                runId = "run-prompt-1",
                primaryGoal = "Complete the working-state rollout.",
                currentSubgoal = "Wire the prompt layer and tests.",
                status = "in_progress",
              ),
              findings = listOf(
                WorkingStateEntry(
                  text = "PromptAssembler has no dedicated working-state layer yet.",
                  sourceType = "code_inspection",
                ),
              ),
              nextActions = listOf(
                WorkingStateEntry(text = "Add focused unit tests."),
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Working State]"))
    assertTrue(prompt.taskPrompt.contains("task_id=task-prompt-1"))
    assertTrue(prompt.taskPrompt.contains("run_id=run-prompt-1"))
    assertTrue(prompt.taskPrompt.contains("primary_goal=Complete the working-state rollout."))
    assertTrue(prompt.taskPrompt.contains("[Recent Findings]"))
    assertTrue(prompt.taskPrompt.contains("[Next Actions]"))
    assertTrue(prompt.taskPrompt.indexOf("[Working State]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertTrue(prompt.report.workingStateTrace.included)
    assertTrue(prompt.report.workingStateTrace.objectivePresent)
    assertEquals(1, prompt.report.workingStateTrace.findingCount)
    assertEquals(1, prompt.report.workingStateTrace.nextActionCount)
  }

  @Test
  fun assembleInjectsBootstrapFilesAsNamedSystemLayers() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            bootstrapContext = BootstrapContext(
              mode = BootstrapMode.FULL,
              files = listOf(
                BootstrapSnippet(
                  name = "AGENTS.md",
                  relativePath = "AGENTS.md",
                  content = "# Agents\nFollow the workspace instructions.",
                  sourceCharCount = "# Agents\nFollow the workspace instructions.".length,
                  truncated = false,
                ),
                BootstrapSnippet(
                  name = "PROJECT.md",
                  relativePath = "PROJECT.md",
                  content = "# Project\nThis repo uses Gradle.",
                  sourceCharCount = "# Project\nThis repo uses Gradle.".length,
                  truncated = false,
                ),
              ),
              trace = BootstrapTrace(
                mode = "full",
                visibleFileCount = 2,
                injectedFileCount = 2,
                omittedFileCount = 0,
                truncatedFileCount = 0,
                files = listOf(
                  BootstrapFileTrace(
                    name = "AGENTS.md",
                    relativePath = "AGENTS.md",
                    sourceCharCount = 42,
                    injectedCharCount = 42,
                    truncated = false,
                  ),
                  BootstrapFileTrace(
                    name = "PROJECT.md",
                    relativePath = "PROJECT.md",
                    sourceCharCount = 31,
                    injectedCharCount = 31,
                    truncated = false,
                  ),
                ),
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.systemPrompt.contains("[Bootstrap AGENTS.md]"))
    assertTrue(prompt.systemPrompt.contains("source_file=AGENTS.md"))
    assertTrue(prompt.systemPrompt.contains("Follow the workspace instructions."))
    assertTrue(prompt.systemPrompt.contains("[Bootstrap PROJECT.md]"))
    assertTrue(prompt.systemPrompt.contains("This repo uses Gradle."))
    assertEquals("full", prompt.report.bootstrapTrace.mode)
    assertEquals(2, prompt.report.bootstrapTrace.visibleFileCount)
    assertEquals(2, prompt.report.bootstrapTrace.injectedFileCount)
  }

  @Test
  fun assembleInjectsDurableCompactionAsDedicatedContextLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
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
                latestCompactedAtEpochMs = 1_234L,
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Durable Compaction]"))
    assertTrue(prompt.taskPrompt.contains("Older session history has been durably compacted into summaries."))
    assertTrue(prompt.taskPrompt.contains("Compacted 6 older message(s) outside the active transcript window."))
    assertTrue(prompt.taskPrompt.indexOf("[Durable Compaction]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertTrue(prompt.report.durableCompactionTrace.compactedThisRun)
    assertEquals(18, prompt.report.durableCompactionTrace.sourceTranscriptMessageCount)
    assertEquals(12, prompt.report.durableCompactionTrace.retainedTranscriptMessageCount)
    assertEquals(6, prompt.report.durableCompactionTrace.latestCompactedMessageCount)
    assertEquals(1, prompt.report.durableCompactionTrace.includedSummaryCount)
    assertEquals(6, prompt.report.durableCompactionTrace.totalCompactedMessageCount)
  }

  @Test
  fun assembleInjectsSkillInventoryAsDedicatedContextLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            skillInventory = SkillInventory(
              skills = listOf(
                VisibleSkill(
                  name = "ui-ux-pro-max",
                  description = "Use this skill when the user asks for high-end product UI design or targeted UX improvements.",
                  relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
                  invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
                  userInvocable = true,
                  executionContext = SkillExecutionContext.INLINE,
                ),
                VisibleSkill(
                  name = "fun-brainstorming",
                  description = "Use this skill before architectural or feature brainstorming work.",
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
                    descriptionPreview = "Use this skill when the user asks for high-end product UI design.",
                  ),
                  VisibleSkillTrace(
                    name = "fun-brainstorming",
                    relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                    invocationControl = "explicit-and-implicit",
                    userInvocable = true,
                    executionContext = "fork",
                    descriptionPreview = "Use this skill before architectural or feature brainstorming work.",
                  ),
                ),
                totalVisibleSkillCount = 2,
                implicitSkillCount = 1,
                invalidSkillCount = 1,
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Skill Inventory]"))
    assertTrue(prompt.taskPrompt.contains("Visible skills are available from configured skills roots."))
    assertTrue(prompt.taskPrompt.contains("name=ui-ux-pro-max"))
    assertTrue(prompt.taskPrompt.contains("invocation=explicit-only"))
    assertTrue(prompt.taskPrompt.contains("execution_context=inline"))
    assertTrue(prompt.taskPrompt.contains("name=fun-brainstorming"))
    assertTrue(prompt.taskPrompt.contains("execution_context=fork"))
    assertTrue(prompt.taskPrompt.contains("Ignored 1 invalid skill file(s) during inventory assembly."))
    assertTrue(prompt.taskPrompt.indexOf("[Skill Inventory]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertEquals(2, prompt.report.visibleSkillCount)
    assertEquals(2, prompt.report.injectedSkillCount)
    assertEquals(0, prompt.report.omittedSkillCount)
    assertEquals(1, prompt.report.invalidSkillCount)
    assertEquals(listOf("ui-ux-pro-max", "fun-brainstorming"), prompt.report.skillInventoryTrace.visible.map { trace -> trace.name })
    assertEquals(1, prompt.report.skillInventoryTrace.implicitSkillCount)
  }

  @Test
  fun assembleInjectsActiveSkillAsDedicatedContextLayer() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(),
          activeSkillCapsule = ActiveSkillCapsule(
            name = "ui-ux-pro-max",
            description = "High-end UI review workflow.",
            relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
            invocationControl = "explicit-only",
            executionContext = "inline",
            activationSource = "skill_read",
            markdownBody = """
              # UI UX Pro Max

              1. Audit the current interface.
              2. Produce a concrete design system.
            """.trimIndent(),
            toolPermissionSummary = listOf("read:allow", "write:allow"),
            allowedToolKeys = setOf("read", "write"),
          ),
          toolDefinitions = emptyList(),
          liveConversation = emptyList(),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Active Skill]"))
    assertTrue(prompt.taskPrompt.contains("A skill is now active for this run."))
    assertTrue(prompt.taskPrompt.contains("name=ui-ux-pro-max"))
    assertTrue(prompt.taskPrompt.contains("allowed_tools=read,write"))
    assertTrue(prompt.taskPrompt.contains("[Instructions]"))
    assertTrue(prompt.taskPrompt.indexOf("[Active Skill]") < prompt.taskPrompt.indexOf("[Tool Protocol]"))
    assertEquals("ui-ux-pro-max", prompt.report.activeSkillTrace.name)
    assertEquals(".codex/skills/ui-ux-pro-max/SKILL.md", prompt.report.activeSkillTrace.relativePath)
    assertEquals("skill_read", prompt.report.activeSkillTrace.activationSource)
    assertTrue(prompt.report.activeSkillTrace.toolRestrictionEnabled)
    assertEquals(listOf("read", "write"), prompt.report.activeSkillTrace.allowedToolKeys)
  }

  @Test
  fun assembleBudgetCoordinatorDropsArchiveAndSupportLayersBeforeWorkingState() {
    val contextManager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 6,
          maxCharsPerMessage = 320,
        ),
      ),
    )
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            workingState = WorkingState(
              objective = WorkingStateObjective(
                taskId = "task-budget-1",
                runId = "run-budget-1",
                primaryGoal = "Finish the context-budget slice.",
                currentSubgoal = "Protect working state under prompt pressure.",
                status = "in_progress",
              ),
              nextActions = listOf(
                WorkingStateEntry(text = "Verify the budget coordinator with focused tests."),
              ),
            ),
            recalledMemory = MemoryRecallResult(
              memories = listOf(
                RetrievedMemory(
                  id = "memory-budget-1",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  status = MemoryStatus.ACTIVE,
                  content = "The user prefers context systems that keep procedural continuity explicit.",
                  lastConfirmedAtEpochMs = 10L,
                  score = 420,
                ),
              ),
              matchedRecordCount = 1,
            ),
            durableCompaction = DurableCompactionContext(
              text = ("Older archived summary. ".repeat(80)).trim(),
            ),
            bootstrapContext = BootstrapContext(
              mode = BootstrapMode.FULL,
              files = listOf(
                BootstrapSnippet(
                  name = "AGENTS.md",
                  relativePath = "AGENTS.md",
                  content = ("Workspace instruction block. ".repeat(40)).trim(),
                  sourceCharCount = ("Workspace instruction block. ".repeat(40)).trim().length,
                  truncated = false,
                ),
              ),
            ),
            skillInventory = SkillInventory(
              skills = listOf(
                VisibleSkill(
                  name = "fun-brainstorming",
                  description = ("Use this skill before architecture work. ".repeat(20)).trim(),
                  relativePath = ".codex/skills/fun-brainstorming/SKILL.md",
                  invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
                  userInvocable = true,
                  executionContext = SkillExecutionContext.FORK,
                ),
              ),
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Review the current runtime architecture."),
            RuntimeConversationMessage(
              RuntimeConversationRole.ASSISTANT,
              "I will inspect the context layers and then verify the budget behavior with focused tests.",
            ),
          ),
          llmMetadata = budgetMetadata(
            contextWindowTokens = 3_600,
            reservedOutputTokens = 128,
            safetyMarginTokens = 96,
            effectiveInputPercent = "0.346",
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Working State]"))
    assertTrue(prompt.taskPrompt.contains("[Retrieved Memory]"))
    assertFalse(prompt.taskPrompt.contains("[Durable Compaction]"))
    assertFalse(prompt.taskPrompt.contains("[Skill Inventory]"))
    assertFalse(prompt.taskPrompt.contains("[Bootstrap AGENTS.md]"))
    assertEquals(ContextBudgetPressureMode.TIGHT, prompt.report.budgetReport.pressureMode)
    assertTrue(prompt.report.budgetReport.omittedLayerNames.contains("Durable Compaction"))
    assertTrue(prompt.report.budgetReport.omittedLayerNames.contains("Skill Inventory"))
    assertTrue(prompt.report.budgetReport.omittedLayerNames.contains("Bootstrap AGENTS.md"))
    assertFalse(prompt.report.budgetReport.omittedLayerNames.contains("Working State"))
  }

  @Test
  fun assembleBudgetCoordinatorTrimsConversationReplayBeforeDroppingMemory() {
    val contextManager = ContextManager(
      transcriptWindowBuilder = TranscriptWindowBuilder(
        TranscriptWindowConfig(
          maxMessages = 6,
          maxCharsPerMessage = 320,
        ),
      ),
    )
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            recalledMemory = MemoryRecallResult(
              memories = listOf(
                RetrievedMemory(
                  id = "memory-budget-2",
                  kind = MemoryKind.USER_PREFERENCE,
                  scope = MemoryScope.USER,
                  status = MemoryStatus.ACTIVE,
                  content = "Keep working state explicit instead of hiding progress inside generic summaries.",
                  lastConfirmedAtEpochMs = 10L,
                  score = 420,
                ),
              ),
              matchedRecordCount = 1,
            ),
          ),
          toolDefinitions = emptyList(),
          liveConversation = listOf(
            RuntimeConversationMessage(
              RuntimeConversationRole.USER,
              "First replay note about the runtime rollout and how older context used to crowd out working state.",
            ),
            RuntimeConversationMessage(
              RuntimeConversationRole.ASSISTANT,
              "Second replay note describing the previous prompt composition and why a global budget coordinator is still needed.",
            ),
            RuntimeConversationMessage(
              RuntimeConversationRole.USER,
              "Third replay note asking whether bounded transcript replay can shrink before memory injection disappears.",
            ),
            RuntimeConversationMessage(
              RuntimeConversationRole.ASSISTANT,
              "Latest reply note confirming that recent replay should shrink first while explicit memory stays available for the model.",
            ),
          ),
          llmMetadata = budgetMetadata(
            contextWindowTokens = 3_600,
            reservedOutputTokens = 128,
            safetyMarginTokens = 96,
            effectiveInputPercent = "0.346",
          ),
        ),
      ),
    )

    assertTrue(prompt.taskPrompt.contains("[Retrieved Memory]"))
    assertTrue(prompt.taskPrompt.contains("Keep working state explicit instead of hiding progress inside generic summaries."))
    assertFalse(prompt.taskPrompt.contains("First replay note about the runtime rollout"))
    assertFalse(prompt.taskPrompt.contains("Second replay note describing the previous prompt composition"))
    assertTrue(prompt.taskPrompt.contains("Third replay note asking whether bounded transcript replay can shrink before memory injection disappears."))
    assertTrue(prompt.taskPrompt.contains("Latest reply note confirming that recent replay should shrink first"))
    val conversationBudgetReport = prompt.report.budgetReport.layers.first { layer ->
      layer.id == PromptLayerId.CONVERSATION
    }
    val memoryBudgetReport = prompt.report.budgetReport.layers.first { layer ->
      layer.id == PromptLayerId.RETRIEVED_MEMORY
    }
    assertTrue(conversationBudgetReport.reduced)
    assertTrue(conversationBudgetReport.appliedOperators.contains("trim_oldest_conversation_messages"))
    assertFalse(memoryBudgetReport.omitted)
    assertFalse(prompt.report.budgetReport.omittedLayerNames.contains("Retrieved Memory"))
  }

  fun assembleToolProtocolReducerAdjustsDetailModeAcrossBudgetBands() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()
    val toolDefinitions = listOf(
      AgentToolDefinition(
        name = "Read",
        description = "Read a file from the workspace.",
      ),
      AgentToolDefinition(
        name = "Bash",
        description = "Run a shell command.",
      ),
      AgentToolDefinition(
        name = "python_exec",
        description = "Run a workspace Python script.",
      ),
      AgentToolDefinition(
        name = "Write",
        description = "Write a file into the workspace.",
      ),
      AgentToolDefinition(
        name = "GenerateImage",
        description = "Generate an image.",
      ),
      AgentToolDefinition(
        name = "SynthesizeSpeech",
        description = "Generate a voice clip.",
      ),
      AgentToolDefinition(
        name = "import_chat_attachment",
        description = "Import a chat attachment into the workspace.",
      ),
      AgentToolDefinition(
        name = "view_workspace_document",
        description = "Inspect a workspace image or PDF directly.",
      ),
      AgentToolDefinition(
        name = "view_workspace_image",
        description = "Inspect a workspace image directly.",
      ),
      AgentToolDefinition(
        name = "view_workspace_pdf",
        description = "Inspect a workspace PDF directly.",
      ),
    )

    fun assembleForBudget(metadata: Map<String, String> = emptyMap()): AssembledPrompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = toolDefinitions,
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Inspect the protocol budget behavior."),
          ),
          llmMetadata = metadata,
        ),
      ),
    )

    val fullPrompt = assembleForBudget()
    val compactPrompt = assembleForBudget(
      budgetMetadata(
        contextWindowTokens = 3_600,
        reservedOutputTokens = 128,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.32",
      ),
    )
    val minimalPrompt = assembleForBudget(
      budgetMetadata(
        contextWindowTokens = 900,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.15",
      ),
    )

    val fullToolProtocolLayer = fullPrompt.report.layers.first { layer -> layer.id == PromptLayerId.TOOL_PROTOCOL }
    val compactToolProtocolLayer = compactPrompt.report.layers.first { layer -> layer.id == PromptLayerId.TOOL_PROTOCOL }
    val minimalToolProtocolLayer = minimalPrompt.report.layers.first { layer -> layer.id == PromptLayerId.TOOL_PROTOCOL }

    assertEquals("full", fullPrompt.report.toolProtocolTrace.detailMode)
    assertEquals("compact", compactPrompt.report.toolProtocolTrace.detailMode)
    assertEquals("minimal", minimalPrompt.report.toolProtocolTrace.detailMode)
    assertFalse(fullPrompt.report.toolProtocolTrace.reducedForBudget)
    assertTrue(compactPrompt.report.toolProtocolTrace.reducedForBudget)
    assertTrue(minimalPrompt.report.toolProtocolTrace.reducedForBudget)
    assertTrue(fullToolProtocolLayer.estimatedTokenCount > compactToolProtocolLayer.estimatedTokenCount)
    assertTrue(compactToolProtocolLayer.estimatedTokenCount > minimalToolProtocolLayer.estimatedTokenCount)
    assertTrue(fullPrompt.report.toolProtocolTrace.exampleCount > compactPrompt.report.toolProtocolTrace.exampleCount)
    assertTrue(compactPrompt.report.toolProtocolTrace.exampleCount > minimalPrompt.report.toolProtocolTrace.exampleCount)
    assertTrue(fullPrompt.report.toolProtocolTrace.attachmentExampleCount > 0)
    assertEquals(0, compactPrompt.report.toolProtocolTrace.attachmentExampleCount)
    assertEquals(0, minimalPrompt.report.toolProtocolTrace.attachmentExampleCount)
    assertTrue(minimalPrompt.taskPrompt.contains("On each turn, return exactly one JSON object and nothing else."))
    assertTrue(minimalPrompt.taskPrompt.contains("Only return type=final when you are ready to answer the user."))
    assertFalse(minimalPrompt.taskPrompt.contains("\"attachments\":[{\"chat_attachment_id\":\"user-image-1\",\"kind\":\"image\"}]"))
    assertFalse(minimalPrompt.taskPrompt.contains("If you need to inspect what a readable workspace image or PDF actually contains"))
    assertTrue(compactPrompt.taskPrompt.contains("Use relative_path for existing workspace files, artifact_id for generated artifacts, and chat_attachment_id to resend an existing chat upload."))
  }

  @Test
  fun assembleToolProtocolReducerHonorsExplicitDetailOverride() {
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      ContextManager().prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "You are OpenCray for testing.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = listOf(
            AgentToolDefinition(
              name = "Read",
              description = "Read a file from the workspace.",
            ),
            AgentToolDefinition(
              name = "Write",
              description = "Write a file into the workspace.",
            ),
            AgentToolDefinition(
              name = "GenerateImage",
              description = "Generate an image.",
            ),
          ),
          liveConversation = listOf(
            RuntimeConversationMessage(
              RuntimeConversationRole.USER,
              "Inspect the override behavior.",
            ),
          ),
          llmMetadata = mapOf(
            "context_window_tokens" to "131072",
            "toolProtocolDetailMode" to "minimal",
          ),
        ),
      ),
    )

    assertEquals("minimal", prompt.report.toolProtocolTrace.detailMode)
    assertFalse(
      prompt.taskPrompt.contains(
        "\"attachments\":[{\"artifact_id\":\"artifact-example-1234abcd\",\"kind\":\"image\"}]",
      ),
    )
  }

  @Test
  fun assembleBudgetCoordinatorStructurallyReducesWorkingStateBeforeDroppingIt() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()
    val workingState = WorkingState(
      objective = WorkingStateObjective(
        taskId = "task-working-budget",
        runId = "run-working-budget",
        primaryGoal = "Investigate the context budget reducer behavior for large procedural state payloads.",
        currentSubgoal = "Keep the latest blocker and next step visible while shedding lower-value findings.",
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
      updatedAtEpochMs = 123_456L,
    )

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(
            workingState = workingState,
          ),
          toolDefinitions = emptyList(),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Keep the reducer honest."),
          ),
          llmMetadata = budgetMetadata(
            contextWindowTokens = 900,
            reservedOutputTokens = 256,
            safetyMarginTokens = 96,
            effectiveInputPercent = "0.15",
          ),
        ),
      ),
    )

    val workingStateBudgetReport = prompt.report.budgetReport.layers.first { layer ->
      layer.id == PromptLayerId.WORKING_STATE
    }

    assertTrue(prompt.taskPrompt.contains("[Working State]"))
    assertTrue(prompt.taskPrompt.contains("primary_goal=Investigate the context budget reducer behavior for large procedural state payloads."))
    assertTrue(prompt.taskPrompt.contains("Recent action 8"))
    assertTrue(prompt.taskPrompt.contains("Decision 4"))
    assertTrue(prompt.taskPrompt.contains("Blocker 3"))
    assertTrue(prompt.taskPrompt.contains("Next action 4"))
    assertFalse(prompt.taskPrompt.contains("[Recent Findings]"))
    assertFalse(prompt.taskPrompt.contains("Finding 1"))
    assertFalse(prompt.taskPrompt.contains("Recent action 1"))
    assertFalse(prompt.taskPrompt.contains("Decision 1"))
    assertFalse(prompt.taskPrompt.contains("Blocker 1"))
    assertFalse(prompt.taskPrompt.contains("Next action 1"))
    assertFalse(prompt.taskPrompt.contains("updated_at_epoch_ms=123456"))
    assertTrue(workingStateBudgetReport.reduced)
    assertFalse(workingStateBudgetReport.omitted)
    assertTrue(workingStateBudgetReport.appliedOperators.contains("reduce_working_state_minimal"))
  }

  @Test
  fun assembleBudgetCoordinatorReportsOverflowWhenMandatoryLayersStillDoNotFit() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
          sessionContext = AgentRuntimeSessionContext(),
          toolDefinitions = emptyList(),
          liveConversation = listOf(
            RuntimeConversationMessage(RuntimeConversationRole.USER, "Keep the latest task prompt intact."),
          ),
          llmMetadata = budgetMetadata(
            contextWindowTokens = 900,
            reservedOutputTokens = 256,
            safetyMarginTokens = 96,
            effectiveInputPercent = "0.15",
          ),
        ),
      ),
    )

    assertEquals(ContextBudgetPressureMode.EMERGENCY, prompt.report.budgetReport.pressureMode)
    assertTrue(prompt.report.budgetReport.unresolvedOverflow)
    assertTrue(prompt.taskPrompt.contains("[Tool Protocol]"))
    assertTrue(prompt.taskPrompt.contains("[Conversation]"))
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

  private fun budgetMetadata(
    contextWindowTokens: Int,
    reservedOutputTokens: Int,
    safetyMarginTokens: Int,
    effectiveInputPercent: String,
  ): Map<String, String> = mapOf(
    "context_window_tokens" to contextWindowTokens.toString(),
    "reserved_output_tokens" to reservedOutputTokens.toString(),
    "prompt_safety_margin_tokens" to safetyMarginTokens.toString(),
    "effective_input_percent" to effectiveInputPercent,
  )
}
