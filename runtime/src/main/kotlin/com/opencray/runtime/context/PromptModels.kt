package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.bootstrap.BootstrapContext
import com.opencray.runtime.bootstrap.BootstrapSnippet
import com.opencray.runtime.bootstrap.BootstrapTrace
import com.opencray.runtime.compaction.DurableCompactionContext
import com.opencray.runtime.compaction.DurableCompactionTrace
import com.opencray.runtime.memory.MemoryFlushTrace
import com.opencray.runtime.memory.MemoryRecallTrace
import com.opencray.runtime.memory.MemoryRecallResult
import com.opencray.runtime.skills.ActiveSkillCapsule
import com.opencray.runtime.skills.ActiveSkillTrace
import com.opencray.runtime.skills.SkillCatalog
import com.opencray.runtime.skills.SkillInventory
import com.opencray.runtime.skills.SkillInventoryTrace
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeConversationMessage(
  val role: RuntimeConversationRole,
  val content: String,
) {
  init {
    require(content.isNotBlank()) { "RuntimeConversationMessage content must not be blank." }
  }
}

@Serializable
enum class RuntimeConversationRole {
  SYSTEM,
  USER,
  ASSISTANT,
  TOOL,
}

data class RuntimeSoulProfile(
  val presetName: String? = null,
  val displayName: String? = null,
  val voice: String? = null,
  val customGuidance: String? = null,
  val extensions: Map<String, String> = emptyMap(),
)

data class LiveContextTrace(
  val mode: String? = null,
  val soulEnabled: Boolean? = null,
  val memoryRecallEnabled: Boolean? = null,
) {
  val isEmpty: Boolean
    get() = mode.isNullOrBlank() && soulEnabled == null && memoryRecallEnabled == null
}

data class AgentRuntimeSessionContext(
  val sessionPolicyText: String? = null,
  val soulProfile: RuntimeSoulProfile? = null,
  val liveContextTrace: LiveContextTrace = LiveContextTrace(),
  val bootstrapContext: BootstrapContext = BootstrapContext(),
  val recalledMemory: MemoryRecallResult = MemoryRecallResult(),
  val memoryFlushTrace: MemoryFlushTrace = MemoryFlushTrace(),
  val durableCompaction: DurableCompactionContext = DurableCompactionContext(),
  val skillInventory: SkillInventory = SkillInventory(),
  val skillCatalog: SkillCatalog = SkillCatalog(),
  val conversation: List<RuntimeConversationMessage> = emptyList(),
)

data class PromptAssemblyInput(
  val task: AgentTask,
  val baseSystemPrompt: String,
  val sessionContext: AgentRuntimeSessionContext,
  val activeSkillCapsule: ActiveSkillCapsule? = null,
  val toolDefinitions: List<AgentToolDefinition>,
  val liveConversation: List<RuntimeConversationMessage>,
)

data class ManagedPromptContext(
  val task: AgentTask,
  val baseSystemPrompt: String,
  val sessionPolicyText: String = "",
  val personalizationText: String = "",
  val bootstrapFiles: List<BootstrapSnippet> = emptyList(),
  val memoryText: String = "",
  val durableCompactionText: String = "",
  val skillInventoryText: String = "",
  val activeSkillText: String = "",
  val recentToolObservationsText: String = "",
  val pruningSummary: TranscriptPruningSummary? = null,
  val compactionSummary: CompactionSummary? = null,
  val toolDefinitions: List<AgentToolDefinition> = emptyList(),
  val transcriptWindow: TranscriptWindow = TranscriptWindow(
    messages = emptyList(),
    omittedMessageCount = 0,
    truncatedMessageCount = 0,
  ),
  val report: ContextSelectionReport = ContextSelectionReport(),
)

data class ContextSelectionReport(
  val sourceTranscriptMessageCount: Int = 0,
  val windowedTranscriptMessageCount: Int = 0,
  val omittedTranscriptMessageCount: Int = 0,
  val truncatedTranscriptMessageCount: Int = 0,
  val prunedTranscriptMessageCount: Int = 0,
  val rewrittenTranscriptMessageCount: Int = 0,
  val duplicateBackgroundTranscriptMessageCount: Int = 0,
  val bulkyToolTranscriptRewriteCount: Int = 0,
  val attachmentLikeTranscriptRewriteCount: Int = 0,
  val pruningSummaryIncluded: Boolean = false,
  val compactedTranscriptMessageCount: Int = 0,
  val compactionSummaryIncluded: Boolean = false,
  val matchedMemoryRecordCount: Int = 0,
  val injectedMemoryRecordCount: Int = 0,
  val omittedMemoryRecordCount: Int = 0,
  val memoryRecallTrace: MemoryRecallTrace = MemoryRecallTrace(),
  val memoryFlushTrace: MemoryFlushTrace = MemoryFlushTrace(),
  val durableCompactionTrace: DurableCompactionTrace = DurableCompactionTrace(),
  val liveContextTrace: LiveContextTrace = LiveContextTrace(),
  val bootstrapTrace: BootstrapTrace = BootstrapTrace(),
  val visibleSkillCount: Int = 0,
  val injectedSkillCount: Int = 0,
  val omittedSkillCount: Int = 0,
  val invalidSkillCount: Int = 0,
  val skillInventoryTrace: SkillInventoryTrace = SkillInventoryTrace(),
  val activeSkillTrace: ActiveSkillTrace = ActiveSkillTrace(),
  val recentToolObservationCount: Int = 0,
  val omittedRecentToolObservationCount: Int = 0,
  val recentToolObservationLayerIncluded: Boolean = false,
)

data class PromptLayer(
  val name: String,
  val kind: PromptLayerKind,
  val content: String,
) {
  init {
    require(name.isNotBlank()) { "PromptLayer name must not be blank." }
    require(content.isNotBlank()) { "PromptLayer content must not be blank." }
  }
}

enum class PromptLayerKind {
  SYSTEM,
  PROTOCOL,
  CONTEXT,
}

data class AssembledPrompt(
  val systemPrompt: String,
  val taskPrompt: String,
  val layers: List<PromptLayer>,
  val report: ContextAssemblyReport,
)

data class ContextAssemblyReport(
  val layers: List<ContextLayerReport>,
  val sourceTranscriptMessageCount: Int,
  val windowedTranscriptMessageCount: Int,
  val omittedTranscriptMessageCount: Int,
  val truncatedTranscriptMessageCount: Int,
  val prunedTranscriptMessageCount: Int = 0,
  val rewrittenTranscriptMessageCount: Int = 0,
  val duplicateBackgroundTranscriptMessageCount: Int = 0,
  val bulkyToolTranscriptRewriteCount: Int = 0,
  val attachmentLikeTranscriptRewriteCount: Int = 0,
  val pruningSummaryIncluded: Boolean = false,
  val compactedTranscriptMessageCount: Int = 0,
  val compactionSummaryIncluded: Boolean = false,
  val matchedMemoryRecordCount: Int = 0,
  val injectedMemoryRecordCount: Int = 0,
  val omittedMemoryRecordCount: Int = 0,
  val memoryRecallTrace: MemoryRecallTrace = MemoryRecallTrace(),
  val memoryFlushTrace: MemoryFlushTrace = MemoryFlushTrace(),
  val durableCompactionTrace: DurableCompactionTrace = DurableCompactionTrace(),
  val liveContextTrace: LiveContextTrace = LiveContextTrace(),
  val bootstrapTrace: BootstrapTrace = BootstrapTrace(),
  val visibleSkillCount: Int = 0,
  val injectedSkillCount: Int = 0,
  val omittedSkillCount: Int = 0,
  val invalidSkillCount: Int = 0,
  val skillInventoryTrace: SkillInventoryTrace = SkillInventoryTrace(),
  val activeSkillTrace: ActiveSkillTrace = ActiveSkillTrace(),
  val recentToolObservationCount: Int = 0,
  val omittedRecentToolObservationCount: Int = 0,
  val recentToolObservationLayerIncluded: Boolean = false,
) {
  val transcriptMessageCount: Int
    get() = windowedTranscriptMessageCount
}

data class ContextLayerReport(
  val name: String,
  val kind: PromptLayerKind,
  val characterCount: Int,
  val estimatedTokenCount: Int,
)

data class TranscriptWindow(
  val messages: List<RuntimeConversationMessage>,
  val omittedMessageCount: Int,
  val truncatedMessageCount: Int,
)

data class TranscriptWindowSelection(
  val window: TranscriptWindow,
  val normalizedMessages: List<RuntimeConversationMessage>,
  val omittedMessages: List<RuntimeConversationMessage>,
)

data class PrunedTranscript(
  val messages: List<RuntimeConversationMessage>,
  val summary: TranscriptPruningSummary? = null,
)

data class TranscriptPruningSummary(
  val text: String,
  val removedMessageCount: Int = 0,
  val rewrittenMessageCount: Int = 0,
  val duplicateBackgroundMessageCount: Int = 0,
  val bulkyToolMessageCount: Int = 0,
  val attachmentLikeMessageCount: Int = 0,
) {
  init {
    require(text.isNotBlank()) { "TranscriptPruningSummary text must not be blank." }
    require(removedMessageCount + rewrittenMessageCount >= 1) {
      "TranscriptPruningSummary must describe at least one pruning change."
    }
  }
}

data class CompactionSummary(
  val text: String,
  val compactedMessageCount: Int,
  val omittedUserMessageCount: Int = 0,
  val omittedAssistantMessageCount: Int = 0,
  val omittedToolMessageCount: Int = 0,
  val omittedSystemMessageCount: Int = 0,
) {
  init {
    require(text.isNotBlank()) { "CompactionSummary text must not be blank." }
    require(compactedMessageCount >= 1) { "CompactionSummary compactedMessageCount must be >= 1." }
  }
}
