package com.opencray.runtime.subagent

import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapFileTrace
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.ContextInjectionPolicy
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.context.RuntimeSoulProfile
import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryFlushOutcome
import com.opencray.runtime.memory.MemoryFlushTrace
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillCatalogEntry
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.runtime.soul.SoulTurnSemanticSignal
import com.opencray.runtime.soul.SoulTurnUserAffect
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentContextBuilderTest {
  private val builder = SubAgentContextBuilder()

  @Test
  fun minimalContextDropsParentTranscriptAndHeavyContextLayers() {
    val parentContext = parentSessionContext()
    val activeSkillCapsule = activeSkillCapsule()

    val result = builder.build(
      request(
        parentSessionContext = parentContext,
        contextMode = SubAgentContextMode.MINIMAL,
        activeSkillCapsule = activeSkillCapsule,
      ),
    )

    assertEquals("", result.delegatedSummaryBlock)
    assertTrue(result.sessionContext.conversation.isEmpty())
    assertNull(result.sessionContext.turnSemanticSignal)
    assertEquals(parentContext.sessionPolicyText, result.sessionContext.sessionPolicyText)
    assertEquals(parentContext.soulProfile, result.sessionContext.soulProfile)
    assertTrue(result.sessionContext.recalledMemory.memories.isEmpty())
    assertTrue(result.sessionContext.memoryFlushTrace.isEmpty)
    assertFalse(result.sessionContext.durableCompaction.included)
    assertEquals(0, result.sessionContext.skillInventory.visibleSkillCount)
    assertTrue(result.sessionContext.skillCatalog.skillsByName.isEmpty())
    assertFalse(result.sessionContext.injectionPolicy.soulTurnPolicyEnabled)
    assertFalse(result.sessionContext.injectionPolicy.automaticMemoryInjectionEnabled)
    assertFalse(result.sessionContext.injectionPolicy.memoryDerivedPolicyEnabled)
    assertEquals(SubAgentContextMode.MINIMAL.wireValue, result.sessionContext.liveContextTrace.mode)
    assertEquals(true, result.sessionContext.liveContextTrace.soulEnabled)
    assertEquals(false, result.sessionContext.liveContextTrace.memoryRecallEnabled)
    assertEquals(BootstrapMode.LIGHTWEIGHT, result.sessionContext.bootstrapContext.mode)
    assertEquals(
      listOf("AGENTS.md", "PROJECT.md"),
      result.sessionContext.bootstrapContext.files.map(BootstrapSnippet::name),
    )
    assertEquals(4, result.sessionContext.bootstrapContext.trace.visibleFileCount)
    assertEquals(2, result.sessionContext.bootstrapContext.trace.injectedFileCount)
    assertEquals(2, result.sessionContext.bootstrapContext.trace.omittedFileCount)
    assertSame(activeSkillCapsule, result.activeSkillCapsule)
  }

  @Test
  fun delegatedContextCarriesCompactSummaryInsteadOfParentTranscript() {
    val parentContext = parentSessionContext()

    val result = builder.build(
      request(
        parentSessionContext = parentContext,
        contextMode = SubAgentContextMode.DELEGATED,
        parentGoalSummary = "Audit the current repo layout.",
        parentConfirmedFacts = listOf(
          "README says to use the Gradle wrapper.",
          "The runtime module owns tool dispatch.",
        ),
        parentObservationLines = listOf(
          "Read file_path=README.md lines=1-2 limit=all truncated=false",
          "LS path=runtime entries=3",
        ),
      ),
    )

    assertTrue(result.delegatedSummaryBlock.isNotBlank())
    assertEquals(1, result.sessionContext.conversation.size)
    assertEquals(RuntimeConversationRole.TOOL, result.sessionContext.conversation.single().role)
    assertEquals(result.delegatedSummaryBlock, result.sessionContext.conversation.single().content)
    assertTrue(result.delegatedSummaryBlock.contains("Delegated parent context for this child run."))
    assertTrue(result.delegatedSummaryBlock.contains("user_goal=Audit the current repo layout."))
    assertTrue(result.delegatedSummaryBlock.contains("confirmed_facts:"))
    assertTrue(result.delegatedSummaryBlock.contains("- README says to use the Gradle wrapper."))
    assertTrue(result.delegatedSummaryBlock.contains("recent_observations:"))
    assertTrue(result.delegatedSummaryBlock.contains("- Read file_path=README.md"))
    assertFalse(result.delegatedSummaryBlock.contains("Inspect the repository."))
    assertFalse(result.delegatedSummaryBlock.contains("tool_result Read"))
    assertTrue(result.sessionContext.recalledMemory.memories.isEmpty())
    assertEquals(BootstrapMode.LIGHTWEIGHT, result.sessionContext.bootstrapContext.mode)
  }

  @Test
  fun mirroredContextPreservesParentContextLayersButDropsTurnSignal() {
    val parentContext = parentSessionContext()
    val activeSkillCapsule = activeSkillCapsule()
    val parentConversation = listOf(
      RuntimeConversationMessage(
        role = RuntimeConversationRole.USER,
        content = "Live parent transcript should be mirrored instead of the stale session snapshot.",
      ),
    )

    val result = builder.build(
      request(
        parentSessionContext = parentContext,
        contextMode = SubAgentContextMode.MIRRORED,
        parentConversation = parentConversation,
        activeSkillCapsule = activeSkillCapsule,
      ),
    )

    assertEquals("", result.delegatedSummaryBlock)
    assertNull(result.sessionContext.turnSemanticSignal)
    assertEquals(parentContext.sessionPolicyText, result.sessionContext.sessionPolicyText)
    assertEquals(parentContext.soulProfile, result.sessionContext.soulProfile)
    assertEquals(parentContext.injectionPolicy, result.sessionContext.injectionPolicy)
    assertEquals(parentContext.memoryToolsEnabled, result.sessionContext.memoryToolsEnabled)
    assertEquals(parentContext.bootstrapContext, result.sessionContext.bootstrapContext)
    assertEquals(parentContext.recalledMemory, result.sessionContext.recalledMemory)
    assertEquals(parentContext.memoryFlushTrace, result.sessionContext.memoryFlushTrace)
    assertEquals(parentContext.durableCompaction, result.sessionContext.durableCompaction)
    assertEquals(parentContext.skillInventory, result.sessionContext.skillInventory)
    assertEquals(parentContext.skillCatalog, result.sessionContext.skillCatalog)
    assertEquals(parentConversation, result.sessionContext.conversation)
    assertEquals(SubAgentContextMode.MIRRORED.wireValue, result.sessionContext.liveContextTrace.mode)
    assertEquals(true, result.sessionContext.liveContextTrace.soulEnabled)
    assertEquals(true, result.sessionContext.liveContextTrace.memoryRecallEnabled)
    assertSame(activeSkillCapsule, result.activeSkillCapsule)
  }

  private fun request(
    parentSessionContext: AgentRuntimeSessionContext,
    contextMode: SubAgentContextMode,
    parentGoalSummary: String? = null,
    parentConfirmedFacts: List<String> = emptyList(),
    parentObservationLines: List<String> = emptyList(),
    parentConversation: List<RuntimeConversationMessage> = parentSessionContext.conversation,
    activeSkillCapsule: ActiveSkillCapsule? = null,
  ): SubAgentContextBuildRequest = SubAgentContextBuildRequest(
    parentSessionContext = parentSessionContext,
    childTask = SubAgentTask(
      description = "Inspect README",
      prompt = "Read README.md and summarize it.",
      subagentType = "researcher",
      contextMode = contextMode,
      parentRunId = "run-parent",
      parentTaskId = "task-parent",
      parentTurn = 2,
    ),
    parentGoalSummary = parentGoalSummary,
    parentConfirmedFacts = parentConfirmedFacts,
    parentObservationLines = parentObservationLines,
    parentConversation = parentConversation,
    activeSkillCapsule = activeSkillCapsule,
  )

  private fun parentSessionContext(): AgentRuntimeSessionContext {
    val bootstrapFiles = listOf(
      bootstrapSnippet(name = "AGENTS.md", relativePath = "AGENTS.md"),
      bootstrapSnippet(name = "PROJECT.md", relativePath = "PROJECT.md"),
      bootstrapSnippet(name = "SOUL.md", relativePath = "SOUL.md"),
      bootstrapSnippet(name = "TOOLS.md", relativePath = "TOOLS.md"),
    )
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
    val catalog = SkillCatalog(
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
    return AgentRuntimeSessionContext(
      sessionPolicyText = "SAFE",
      soulProfile = RuntimeSoulProfile(
        displayName = "OpenCray",
        customGuidance = "Stay concise and precise.",
      ),
      turnSemanticSignal = SoulTurnSemanticSignal(
        isTaskBearingRequest = true,
        userAffect = SoulTurnUserAffect.STRAINED,
      ),
      injectionPolicy = ContextInjectionPolicy(),
      memoryToolsEnabled = true,
      liveContextTrace = LiveContextTrace(
        mode = "full",
        soulEnabled = true,
        memoryRecallEnabled = true,
      ),
      bootstrapContext = BootstrapContext(
        mode = BootstrapMode.FULL,
        files = bootstrapFiles,
        trace = BootstrapTrace(
          mode = BootstrapMode.FULL.wireValue,
          visibleFileCount = 4,
          injectedFileCount = 4,
          omittedFileCount = 0,
          truncatedFileCount = 0,
          files = bootstrapFiles.map { snippet ->
            BootstrapFileTrace(
              name = snippet.name,
              relativePath = snippet.relativePath,
              sourceCharCount = snippet.sourceCharCount,
              injectedCharCount = snippet.content.length,
              truncated = snippet.truncated,
            )
          },
        ),
      ),
      recalledMemory = MemoryRecallResult(
        memories = listOf(
          RetrievedMemory(
            id = "memory-1",
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            status = MemoryStatus.ACTIVE,
            content = "Use the Gradle wrapper from the repository root.",
            source = MemoryEvidenceSource.TOOL_OBSERVATION,
            sourceSessionId = "session-1",
            workspaceId = "workspace-1",
            lastConfirmedAtEpochMs = 100L,
            matchedTerms = listOf("gradle"),
            score = 42,
          ),
        ),
        matchedRecordCount = 1,
        trace = MemoryRecallTrace(queryTerms = listOf("gradle")),
      ),
      memoryFlushTrace = MemoryFlushTrace(
        outcome = MemoryFlushOutcome.WRITTEN,
        omittedMessageCount = 4,
        omittedCharCount = 120,
        signature = "flush-signature",
        candidateCount = 1,
        writtenRecordCount = 1,
        writtenKinds = listOf("project_fact"),
        writtenRecordIds = listOf("memory-1"),
      ),
      durableCompaction = DurableCompactionContext(
        text = "Older repository exploration was compacted.",
      ),
      skillInventory = inventory,
      skillCatalog = catalog,
      conversation = listOf(
        RuntimeConversationMessage(
          role = RuntimeConversationRole.USER,
          content = "Inspect the repository.",
        ),
        RuntimeConversationMessage(
          role = RuntimeConversationRole.TOOL,
          content = "tool_result Read success {\"filePath\":\"README.md\"}",
        ),
      ),
    )
  }

  private fun activeSkillCapsule(): ActiveSkillCapsule = ActiveSkillCapsule(
    name = "audit",
    description = "Audit repository changes.",
    relativePath = ".codex/skills/audit/SKILL.md",
    invocationControl = "explicit_only",
    executionContext = "inline",
    activationSource = "skill_read",
    markdownBody = "# Audit\n\nFocus on regressions.",
  )

  private fun bootstrapSnippet(
    name: String,
    relativePath: String,
  ): BootstrapSnippet {
    val content = "# $name\n\nGuidance"
    return BootstrapSnippet(
      name = name,
      relativePath = relativePath,
      content = content,
      sourceCharCount = content.length,
      truncated = false,
    )
  }
}
