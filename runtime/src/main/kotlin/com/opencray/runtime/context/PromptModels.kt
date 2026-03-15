package com.opencray.runtime.context

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.memory.MemoryRecallResult

data class RuntimeConversationMessage(
  val role: RuntimeConversationRole,
  val content: String,
) {
  init {
    require(content.isNotBlank()) { "RuntimeConversationMessage content must not be blank." }
  }
}

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

data class AgentRuntimeSessionContext(
  val sessionPolicyText: String? = null,
  val soulProfile: RuntimeSoulProfile? = null,
  val recalledMemory: MemoryRecallResult = MemoryRecallResult(),
  val conversation: List<RuntimeConversationMessage> = emptyList(),
)

data class PromptAssemblyInput(
  val task: AgentTask,
  val baseSystemPrompt: String,
  val sessionContext: AgentRuntimeSessionContext,
  val toolDefinitions: List<AgentToolDefinition>,
  val liveConversation: List<RuntimeConversationMessage>,
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
  val matchedMemoryRecordCount: Int = 0,
  val injectedMemoryRecordCount: Int = 0,
  val omittedMemoryRecordCount: Int = 0,
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
