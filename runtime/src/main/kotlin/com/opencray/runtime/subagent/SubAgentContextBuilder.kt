package com.opencray.runtime.subagent

import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapMode
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.LiveContextTrace
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import com.opencray.runtime.memory.MemoryFlushTrace
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillInventory

data class SubAgentContextBuildRequest(
  val parentSessionContext: AgentRuntimeSessionContext,
  val childTask: SubAgentTask,
  val parentGoalSummary: String? = null,
  val parentConfirmedFacts: List<String> = emptyList(),
  val parentObservationLines: List<String> = emptyList(),
  val parentConversation: List<RuntimeConversationMessage> = parentSessionContext.conversation,
  val activeSkillCapsule: ActiveSkillCapsule? = null,
)

data class SubAgentContextBuildResult(
  val sessionContext: AgentRuntimeSessionContext,
  val activeSkillCapsule: ActiveSkillCapsule? = null,
  val delegatedSummaryBlock: String = "",
)

class SubAgentContextBuilder {
  fun build(request: SubAgentContextBuildRequest): SubAgentContextBuildResult {
    val delegatedSummaryBlock = delegatedSummaryBlock(request)
    val sessionContext = when (request.childTask.contextMode) {
      SubAgentContextMode.MINIMAL -> minimalChildContext(request)
      SubAgentContextMode.DELEGATED -> delegatedChildContext(
        request = request,
        delegatedSummaryBlock = delegatedSummaryBlock,
      )
      SubAgentContextMode.MIRRORED -> mirroredChildContext(request)
    }
    return SubAgentContextBuildResult(
      sessionContext = sessionContext,
      activeSkillCapsule = request.activeSkillCapsule,
      delegatedSummaryBlock = delegatedSummaryBlock,
    )
  }

  private fun minimalChildContext(
    request: SubAgentContextBuildRequest,
  ): AgentRuntimeSessionContext = request.parentSessionContext.copy(
    turnSemanticSignal = null,
    injectionPolicy = childInjectionPolicy(request.parentSessionContext),
    liveContextTrace = childLiveContextTrace(
      request = request,
      memoryRecallEnabled = false,
    ),
    bootstrapContext = lightweightBootstrap(request.parentSessionContext.bootstrapContext),
    recalledMemory = MemoryRecallResult(),
    memoryFlushTrace = MemoryFlushTrace(),
    durableCompaction = DurableCompactionContext(),
    skillInventory = SkillInventory(),
    skillCatalog = SkillCatalog(),
    conversation = emptyList(),
  )

  private fun delegatedChildContext(
    request: SubAgentContextBuildRequest,
    delegatedSummaryBlock: String,
  ): AgentRuntimeSessionContext = minimalChildContext(request).copy(
    conversation = delegatedSummaryBlock
      .takeIf(String::isNotBlank)
      ?.let { summary ->
        listOf(
          RuntimeConversationMessage(
            role = RuntimeConversationRole.TOOL,
            content = summary,
          ),
        )
      }
      .orEmpty(),
  )

  private fun mirroredChildContext(
    request: SubAgentContextBuildRequest,
  ): AgentRuntimeSessionContext = request.parentSessionContext.copy(
    turnSemanticSignal = null,
    liveContextTrace = childLiveContextTrace(
      request = request,
      memoryRecallEnabled = request.parentSessionContext.injectionPolicy.automaticMemoryInjectionEnabled,
    ),
    conversation = request.parentConversation,
  )

  private fun childInjectionPolicy(
    parent: AgentRuntimeSessionContext,
  ) = parent.injectionPolicy.copy(
    soulTurnPolicyEnabled = false,
    automaticMemoryInjectionEnabled = false,
    memoryDerivedPolicyEnabled = false,
  )

  private fun childLiveContextTrace(
    request: SubAgentContextBuildRequest,
    memoryRecallEnabled: Boolean,
  ): LiveContextTrace = LiveContextTrace(
    mode = request.childTask.contextMode.wireValue,
    soulEnabled = request.parentSessionContext.injectionPolicy.soulContractEnabled,
    memoryRecallEnabled = memoryRecallEnabled,
  )

  private fun delegatedSummaryBlock(request: SubAgentContextBuildRequest): String {
    if (request.childTask.contextMode != SubAgentContextMode.DELEGATED) {
      return ""
    }
    val facts = request.parentConfirmedFacts
      .map(String::trim)
      .filter(String::isNotBlank)
      .take(6)
    val observations = request.parentObservationLines
      .map(String::trim)
      .filter(String::isNotBlank)
      .take(6)
    val goal = request.parentGoalSummary?.trim().orEmpty()
    if (goal.isBlank() && facts.isEmpty() && observations.isEmpty()) {
      return ""
    }
    return buildString {
      appendLine("Delegated parent context for this child run.")
      if (goal.isNotBlank()) {
        append("user_goal=")
        appendLine(goal)
      }
      if (facts.isNotEmpty()) {
        appendLine("confirmed_facts:")
        facts.forEach { fact -> appendLine("- $fact") }
      }
      if (observations.isNotEmpty()) {
        appendLine("recent_observations:")
        observations.forEach { observation -> appendLine("- $observation") }
      }
    }.trim()
  }

  private fun lightweightBootstrap(parent: BootstrapContext): BootstrapContext {
    if (parent.isEmpty) {
      return BootstrapContext(mode = BootstrapMode.NONE)
    }
    val files = parent.files.filter { snippet ->
      snippet.name == "AGENTS.md" || snippet.name == "PROJECT.md"
    }
    val visibleFileCount = maxOf(parent.trace.visibleFileCount, parent.files.size)
    val injectedFileCount = files.size
    val omittedFileCount = (visibleFileCount - injectedFileCount).coerceAtLeast(0)
    val mode = if (files.isEmpty()) BootstrapMode.NONE else BootstrapMode.LIGHTWEIGHT
    return BootstrapContext(
      mode = mode,
      files = files,
      trace = BootstrapTrace(
        mode = mode.wireValue,
        visibleFileCount = visibleFileCount,
        injectedFileCount = injectedFileCount,
        omittedFileCount = omittedFileCount,
        truncatedFileCount = files.count { snippet -> snippet.truncated },
      ),
    )
  }
}
