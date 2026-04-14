package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.runtime.bootstrap.BootstrapPromptLayer
import com.opencray.runtime.bootstrap.BootstrapPromptLayerConfig
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionPromptLayer
import com.opencray.runtime.compaction.DurableCompactionPromptLayerConfig
import com.opencray.runtime.compaction.DurableCompactionTrace
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPromptLayer
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStatus
import com.opencray.runtime.memory.RetrievedMemory
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillPromptLayer
import com.opencray.runtime.skills.ActiveSkillPromptLayerConfig
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryPromptLayer
import com.opencray.runtime.skills.SkillInventoryPromptLayerConfig
import com.opencray.runtime.skills.VisibleSkill
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalContextBudgetCoordinatorTest {
  @Test
  fun rebalanceStructurallyReducesRetrievedMemoryBeforeOmittingItWhenCompactSubsetFits() {
    val memoryPromptLayer = MemoryPromptLayer()
    val recalledMemory = MemoryRecallResult(
      memories = (1..4).map { index ->
        RetrievedMemory(
          id = "memory-$index",
          kind = if (index % 2 == 0) MemoryKind.DURABLE_INSTRUCTION else MemoryKind.PROJECT_FACT,
          scope = if (index % 2 == 0) MemoryScope.WORKSPACE else MemoryScope.USER,
          status = MemoryStatus.ACTIVE,
          content = "Memory $index durable detail block",
          lastConfirmedAtEpochMs = 100L + index,
          score = 500 - index,
        )
      },
      matchedRecordCount = 4,
    )
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      selectedMemory = recalledMemory,
      memoryText = memoryPromptLayer.render(recalledMemory),
      llmMetadata = budgetMetadata(
        contextWindowTokens = 900,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.15",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(18).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.RETRIEVED_MEMORY,
        name = "Retrieved Memory",
        kind = PromptLayerKind.CONTEXT,
        content = input.memoryText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      memoryPromptLayer = memoryPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val memoryLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.RETRIEVED_MEMORY }
    val memoryBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.RETRIEVED_MEMORY }

    assertTrue(memoryLayer.content.contains("Memory 1 durable detail block"))
    assertTrue(memoryLayer.content.contains("Memory 2 durable detail block"))
    assertFalse(memoryLayer.content.contains("Memory 3 durable detail block"))
    assertFalse(memoryLayer.content.contains("Memory 4 durable detail block"))
    assertTrue(memoryBudgetReport.reduced)
    assertFalse(memoryBudgetReport.omitted)
    assertTrue(memoryBudgetReport.appliedOperators.contains("reduce_retrieved_memory_compact"))
    assertEquals(ContextBudgetLayerFinalState.COMPACT, memoryBudgetReport.finalState)
  }

  @Test
  fun rebalanceStructurallyReducesRecentToolObservationsBeforeOmittingThemWhenCompactSubsetFits() {
    val observationSupport = RecentToolObservationSupport(
      config = RecentToolObservationConfig(
        maxEntries = 4,
        maxCompactEntries = 2,
        maxMinimalEntries = 1,
        maxReadChars = 512,
        maxReadLines = 24,
        maxListChars = 512,
        maxListLines = 16,
        maxCompactBodyChars = 128,
        maxCompactBodyLines = 6,
      ),
    )
    val fullObservationLayer = observationSupport.renderLayer(
      layer = RecentToolObservationLayer(
        text = "seed",
        items = listOf(
          RecentToolObservationItem(
            signature = "read-readme",
            summaryLine = "- Read file_path=README.md lines=1-4/20",
            body = "README intro\nProject overview\nSetup notes",
          ),
          RecentToolObservationItem(
            signature = "grep-needle",
            summaryLine = "- Grep pattern=needle path=src matches=2",
            body = "src/App.kt:12:needle\nsrc/App.kt:40:needle",
          ),
          RecentToolObservationItem(
            signature = "skills-find-ui",
            summaryLine = "- SkillsFind query=ui results=1 provider=skills.sh remote=1 local=0",
            body = "ui-ux-pro-max\tremote\tinstall_ref=ui-ux-pro-max\tsource=skills.sh",
          ),
        ),
        omittedObservationCount = 1,
      ),
      detailMode = RecentToolObservationDetailMode.FULL,
    )
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      recentToolObservationLayer = fullObservationLayer,
      recentToolObservationsText = fullObservationLayer.text,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 900,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.15",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(18).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.RECENT_TOOL_OBSERVATIONS,
        name = "Recent Working Observations",
        kind = PromptLayerKind.CONTEXT,
        content = input.recentToolObservationsText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      recentToolObservationSupport = observationSupport,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val observationLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.RECENT_TOOL_OBSERVATIONS }
    val observationBudgetReport = coordinated.report.layers.first { layer ->
      layer.id == PromptLayerId.RECENT_TOOL_OBSERVATIONS
    }

    assertFalse(observationLayer.content.contains("Read file_path=README.md"))
    assertTrue(observationLayer.content.contains("SkillsFind query=ui results=1"))
    assertTrue(observationBudgetReport.reduced)
    assertFalse(observationBudgetReport.omitted)
    assertTrue(
      observationBudgetReport.appliedOperators.contains("reduce_recent_tool_observations_compact") ||
        observationBudgetReport.appliedOperators.contains("reduce_recent_tool_observations_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(observationBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesActiveSkillBeforeOmittingItWhenCompactSubsetFits() {
    val activeSkillPromptLayer = ActiveSkillPromptLayer(
      config = ActiveSkillPromptLayerConfig(
        maxBodyChars = 3_200,
        maxPermissionEntries = 8,
        maxCompactBodyChars = 480,
        maxCompactPermissionEntries = 2,
        maxMinimalBodyChars = 160,
        maxMinimalPermissionEntries = 1,
      ),
    )
    val activeSkillCapsule = ActiveSkillCapsule(
      name = "ui-ux-pro-max",
      description = "High-end UI review workflow.",
      relativePath = ".codex/skills/ui-ux-pro-max/SKILL.md",
      invocationControl = "explicit-only",
      executionContext = "fork",
      activationSource = "skill_read",
      markdownBody = """
        # UI UX Pro Max

        1. Audit the current interface in detail.
        2. Produce a concrete design system.
        3. Verify the implementation against the design system.
        4. Document the remaining gaps clearly.
        5. ${"Inspect typography, spacing, motion, hierarchy, and color direction carefully. ".repeat(24).trim()}
      """.trimIndent(),
      toolPermissionSummary = listOf("read:allow", "write:allow", "search:allow"),
      allowedToolKeys = setOf("read", "write", "search"),
    )
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      activeSkillCapsule = activeSkillCapsule,
      activeSkillText = activeSkillPromptLayer.render(activeSkillCapsule).text,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1400,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.7",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(8).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.ACTIVE_SKILL,
        name = "Active Skill",
        kind = PromptLayerKind.CONTEXT,
        content = input.activeSkillText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      activeSkillPromptLayer = activeSkillPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val activeSkillLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.ACTIVE_SKILL }
    val activeSkillBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.ACTIVE_SKILL }

    assertTrue(activeSkillLayer.content.contains("name=ui-ux-pro-max"))
    assertTrue(activeSkillLayer.content.contains("allowed_tools=read,search,write"))
    assertTrue(activeSkillLayer.content.contains("[Instructions]"))
    assertTrue(activeSkillBudgetReport.reduced)
    assertFalse(activeSkillBudgetReport.omitted)
    assertTrue(
      activeSkillBudgetReport.appliedOperators.contains("reduce_active_skill_compact") ||
        activeSkillBudgetReport.appliedOperators.contains("reduce_active_skill_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(activeSkillBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesBootstrapBeforeOmittingItWhenCompactSubsetFits() {
    val bootstrapPromptLayer = BootstrapPromptLayer(
      config = BootstrapPromptLayerConfig(
        maxCompactChars = 160,
        maxMinimalChars = 80,
      ),
    )
    val bootstrapContent = """
      # Agents

      ${"Keep the repo aligned with the current runtime slice and verify focused tests before answering. ".repeat(10).trim()}
    """.trimIndent()
    val bootstrapSnippet = BootstrapSnippet(
      name = "AGENTS.md",
      relativePath = "AGENTS.md",
      content = bootstrapContent,
      sourceCharCount = bootstrapContent.length + 160,
      truncated = true,
    )
    val renderedBootstrap = bootstrapPromptLayer.render(bootstrapSnippet)
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      bootstrapFiles = listOf(bootstrapSnippet),
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1_200,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.42",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(10).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.BOOTSTRAP,
        name = renderedBootstrap.layerName,
        kind = PromptLayerKind.SYSTEM,
        content = renderedBootstrap.text,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      bootstrapPromptLayer = bootstrapPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val bootstrapLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.BOOTSTRAP }
    val bootstrapBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.BOOTSTRAP }

    assertTrue(bootstrapLayer.content.contains("source_file=AGENTS.md"))
    assertTrue(bootstrapLayer.content.contains("prompt_truncated=true"))
    assertTrue(bootstrapLayer.content.length < renderedBootstrap.text.length)
    assertTrue(bootstrapBudgetReport.reduced)
    assertFalse(bootstrapBudgetReport.omitted)
    assertTrue(
      bootstrapBudgetReport.appliedOperators.contains("reduce_bootstrap_compact") ||
        bootstrapBudgetReport.appliedOperators.contains("reduce_bootstrap_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(bootstrapBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesSkillInventoryBeforeOmittingItWhenCompactSubsetFits() {
    val skillInventoryPromptLayer = SkillInventoryPromptLayer(
      config = SkillInventoryPromptLayerConfig(
        maxSkills = 5,
        maxDescriptionChars = 160,
        maxCompactSkills = 2,
        maxCompactDescriptionChars = 64,
        maxMinimalSkills = 1,
        maxMinimalDescriptionChars = 48,
      ),
    )
    val inventory = SkillInventory(
      skills = (1..4).map { index ->
        VisibleSkill(
          name = "skill-$index",
          description = "Skill $index " + "provides detailed workflow guidance ".repeat(10).trim(),
          relativePath = ".codex/skills/skill-$index/SKILL.md",
          invocationControl = if (index % 2 == 0) {
            SkillInvocationControl.EXPLICIT_ONLY
          } else {
            SkillInvocationControl.EXPLICIT_AND_IMPLICIT
          },
          userInvocable = true,
          executionContext = if (index % 2 == 0) {
            SkillExecutionContext.INLINE
          } else {
            SkillExecutionContext.FORK
          },
        )
      },
      invalidSkillCount = 1,
    )
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      skillInventory = inventory,
      skillInventoryText = skillInventoryPromptLayer.render(inventory).text,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1_200,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.42",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(10).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.SKILL_INVENTORY,
        name = "Skill Inventory",
        kind = PromptLayerKind.CONTEXT,
        content = input.skillInventoryText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      skillInventoryPromptLayer = skillInventoryPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val inventoryLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.SKILL_INVENTORY }
    val inventoryBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.SKILL_INVENTORY }

    assertTrue(inventoryLayer.content.contains("Visible skills:"))
    assertTrue(inventoryLayer.content.contains("name=skill-1"))
    assertFalse(inventoryLayer.content.contains("user_invocable=true"))
    assertFalse(inventoryLayer.content.contains("name=skill-4"))
    assertTrue(inventoryBudgetReport.reduced)
    assertFalse(inventoryBudgetReport.omitted)
    assertTrue(
      inventoryBudgetReport.appliedOperators.contains("reduce_skill_inventory_compact") ||
        inventoryBudgetReport.appliedOperators.contains("reduce_skill_inventory_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(inventoryBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesDurableCompactionBeforeOmittingItWhenCompactSubsetFits() {
    val durableCompactionPromptLayer = DurableCompactionPromptLayer(
      config = DurableCompactionPromptLayerConfig(
        maxCompactChars = 160,
      ),
    )
    val durableCompaction = DurableCompactionContext(
      text = """
        Older session history has been durably compacted into summaries.
        [Compacted History]
        ${"Compacted summary detail block. ".repeat(14).trim()}
      """.trimIndent(),
      trace = DurableCompactionTrace(
        compactedThisRun = true,
        sourceTranscriptMessageCount = 24,
        retainedTranscriptMessageCount = 12,
        latestCompactedMessageCount = 6,
        includedSummaryCount = 2,
        omittedSummaryCount = 1,
        totalCompactedMessageCount = 12,
        latestCompactedAtEpochMs = 1_234L,
      ),
    )
    val fullCompactionText = durableCompactionPromptLayer.render(durableCompaction)
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      durableCompaction = durableCompaction,
      durableCompactionText = fullCompactionText,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1_200,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.42",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(10).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.DURABLE_COMPACTION,
        name = "Durable Compaction",
        kind = PromptLayerKind.CONTEXT,
        content = fullCompactionText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      durableCompactionPromptLayer = durableCompactionPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val durableCompactionLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.DURABLE_COMPACTION }
    val durableCompactionBudgetReport = coordinated.report.layers.first { layer ->
      layer.id == PromptLayerId.DURABLE_COMPACTION
    }

    assertTrue(durableCompactionLayer.content.contains("Durable compaction archive is available."))
    assertTrue(durableCompactionLayer.content.contains("total_compacted_messages=12"))
    assertTrue(durableCompactionLayer.content.length < fullCompactionText.length)
    assertTrue(durableCompactionBudgetReport.reduced)
    assertFalse(durableCompactionBudgetReport.omitted)
    assertTrue(
      durableCompactionBudgetReport.appliedOperators.contains("reduce_durable_compaction_compact") ||
        durableCompactionBudgetReport.appliedOperators.contains("reduce_durable_compaction_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(durableCompactionBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesPruningSummaryBeforeOmittingItWhenCompactSubsetFits() {
    val pruningSummaryPromptLayer = TranscriptPruningSummaryPromptLayer()
    val pruningSummary = TranscriptPruningSummary(
      text = buildString {
        appendLine("Applied prompt-local pruning before windowing: removed=2, rewritten=3.")
        appendLine("Dropped consecutive duplicate background messages: 1.")
        appendLine("Rewritten payloads: tool_output=2, attachment_like=1.")
        append("Detailed pruning note: " + "tool payload pruning evidence ".repeat(14).trim())
      },
      removedMessageCount = 2,
      rewrittenMessageCount = 3,
      duplicateBackgroundMessageCount = 1,
      bulkyToolMessageCount = 2,
      attachmentLikeMessageCount = 1,
    )
    val fullPruningText = pruningSummaryPromptLayer.render(pruningSummary)
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      pruningSummary = pruningSummary,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1_200,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.42",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(10).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.PRUNING_SUMMARY,
        name = "Pruning Summary",
        kind = PromptLayerKind.CONTEXT,
        content = fullPruningText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      pruningSummaryPromptLayer = pruningSummaryPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val pruningLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.PRUNING_SUMMARY }
    val pruningBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.PRUNING_SUMMARY }

    assertTrue(pruningLayer.content.contains("removed=2"))
    assertTrue(pruningLayer.content.length < fullPruningText.length)
    assertFalse(pruningLayer.content.contains("Detailed pruning note"))
    assertTrue(pruningBudgetReport.reduced)
    assertFalse(pruningBudgetReport.omitted)
    assertTrue(
      pruningBudgetReport.appliedOperators.contains("reduce_pruning_summary_compact") ||
        pruningBudgetReport.appliedOperators.contains("reduce_pruning_summary_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(pruningBudgetReport)
  }

  @Test
  fun rebalanceStructurallyReducesCompactionSummaryBeforeOmittingItWhenCompactSubsetFits() {
    val compactionSummaryPromptLayer = CompactionSummaryPromptLayer()
    val compactionSummary = CompactionSummary(
      text = buildString {
        appendLine("Compacted 6 older message(s) outside the active transcript window.")
        appendLine("Omitted roles: user=2, assistant=2, tool=2, system=0.")
        appendLine("Omitted terminal outcomes: approval_approved=1, run_interrupted=1.")
        appendLine("Omitted tool activity: discovery=2, execution=1.")
        append("Most recent omitted assistant reply: " + "context replay detail ".repeat(16).trim())
      },
      compactedMessageCount = 6,
      omittedUserMessageCount = 2,
      omittedAssistantMessageCount = 2,
      omittedToolMessageCount = 2,
      omittedSystemMessageCount = 0,
    )
    val fullCompactionSummaryText = compactionSummaryPromptLayer.render(compactionSummary)
    val input = ManagedPromptContext(
      task = promptTask(),
      baseSystemPrompt = "Base identity.",
      compactionSummary = compactionSummary,
      llmMetadata = budgetMetadata(
        contextWindowTokens = 1_200,
        reservedOutputTokens = 256,
        safetyMarginTokens = 96,
        effectiveInputPercent = "0.42",
      ),
    )
    val layers = listOf(
      PromptLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = "Base identity.",
      ),
      PromptLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = "Follow runtime rules.",
      ),
      PromptLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = "Protocol ".repeat(10).trim(),
      ),
      PromptLayer(
        id = PromptLayerId.TASK_METADATA,
        name = "Task Metadata",
        kind = PromptLayerKind.CONTEXT,
        content = "Task metadata: task_id=task-context",
      ),
      PromptLayer(
        id = PromptLayerId.COMPACTION_SUMMARY,
        name = "Compaction Summary",
        kind = PromptLayerKind.CONTEXT,
        content = fullCompactionSummaryText,
      ),
    )

    val coordinated = GlobalContextBudgetCoordinator(
      compactionSummaryPromptLayer = compactionSummaryPromptLayer,
    ).rebalance(
      input = input,
      layers = layers,
      estimateTokens = { text -> text.length },
      renderConversationLayer = { window ->
        window.messages.joinToString(separator = "\n") { message -> message.content }
      },
    )

    val compactionLayer = coordinated.layers.first { layer -> layer.id == PromptLayerId.COMPACTION_SUMMARY }
    val compactionBudgetReport = coordinated.report.layers.first { layer -> layer.id == PromptLayerId.COMPACTION_SUMMARY }

    assertTrue(compactionLayer.content.contains("compacted_messages=6") || compactionLayer.content.contains("Compacted 6 older message(s)."))
    assertTrue(compactionLayer.content.length < fullCompactionSummaryText.length)
    assertFalse(compactionLayer.content.contains("Most recent omitted assistant reply"))
    assertTrue(compactionBudgetReport.reduced)
    assertFalse(compactionBudgetReport.omitted)
    assertTrue(
      compactionBudgetReport.appliedOperators.contains("reduce_compaction_summary_compact") ||
        compactionBudgetReport.appliedOperators.contains("reduce_compaction_summary_minimal"),
    )
    assertReducedLayerFinalStateMatchesOperators(compactionBudgetReport)
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

  private fun assertReducedLayerFinalStateMatchesOperators(
    report: ContextBudgetLayerReport,
  ) {
    val expectedFinalState = when {
      report.appliedOperators.any { operator -> operator.endsWith("_minimal") } ->
        ContextBudgetLayerFinalState.MINIMAL
      report.appliedOperators.any { operator -> operator.endsWith("_compact") } ->
        ContextBudgetLayerFinalState.COMPACT
      else -> error("Expected a compact or minimal reduction operator for ${report.id}.")
    }
    assertEquals(expectedFinalState, report.finalState)
  }
}
