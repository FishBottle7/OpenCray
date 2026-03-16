package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.AgentToolDefinition
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
              name = "workspace_read_file",
              description = "Read a file from the workspace.",
            ),
            AgentToolDefinition(
              name = "Bash",
              description = "Run a shell command.",
            ),
            AgentToolDefinition(
              name = "WebFetch",
              description = "Fetch a web page.",
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
    assertTrue(prompt.taskPrompt.contains("On each turn, return exactly one JSON action"))
    assertTrue(prompt.taskPrompt.contains("the runtime will execute it, append the tool result"))
    assertTrue(prompt.taskPrompt.contains("If you need multiple tools, call only the next tool now"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"Bash"))
    assertTrue(prompt.taskPrompt.contains("tool_name\":\"WebFetch"))
    assertTrue(prompt.taskPrompt.contains("Use Bash for one-off shell commands"))
    assertTrue(prompt.taskPrompt.contains("prefer WebSearch when a search provider is configured"))
    assertTrue(prompt.taskPrompt.contains("use PowerShell syntax on Windows hosts"))
    assertTrue(prompt.taskPrompt.contains("prefer ProcessStart and then use ProcessRead or ProcessWait"))
    assertTrue(prompt.taskPrompt.contains("prefer ProcessStart with script_path instead of python_exec"))
    assertTrue(prompt.taskPrompt.contains("reason or justification"))
    assertTrue(prompt.taskPrompt.contains("it must not include a final answer"))
    assertTrue(prompt.taskPrompt.contains("Available tools:"))
    assertTrue(prompt.taskPrompt.contains("[Task Context]"))
    assertTrue(prompt.taskPrompt.contains("[Compaction Summary]"))
    assertTrue(prompt.taskPrompt.contains("Compacted 1 older message(s) outside the active transcript window."))
    assertTrue(prompt.taskPrompt.contains("task_id=task-context"))
    assertTrue(prompt.taskPrompt.contains("Omitted 1 older message(s)"))
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
    assertTrue(prompt.taskPrompt.contains("removed=1, rewritten=1"))
    assertTrue(prompt.report.pruningSummaryIncluded)
    assertEquals(1, prompt.report.prunedTranscriptMessageCount)
    assertEquals(1, prompt.report.rewrittenTranscriptMessageCount)
    assertEquals(1, prompt.report.attachmentLikeTranscriptRewriteCount)
  }

  @Test
  fun assembleOmitsOptionalLayersWhenEmpty() {
    val contextManager = ContextManager()
    val assembler = PromptAssembler()

    val prompt = assembler.assemble(
      contextManager.prepare(
        PromptAssemblyInput(
          task = promptTask(),
          baseSystemPrompt = "Base identity.",
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
}
