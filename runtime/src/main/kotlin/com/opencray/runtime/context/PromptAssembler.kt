package com.opencray.runtime.context

import com.opencray.runtime.AgentToolDefinition

class PromptAssembler {
  fun assemble(input: ManagedPromptContext): AssembledPrompt {
    val layers = buildList {
      addLayer(
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = input.baseSystemPrompt.trim(),
      )
      addLayer(
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = RUNTIME_RULES,
      )
      addLayer(
        name = "Session Policy",
        kind = PromptLayerKind.SYSTEM,
        content = input.sessionPolicyText,
      )
      addLayer(
        name = "Personalization",
        kind = PromptLayerKind.SYSTEM,
        content = input.personalizationText,
      )
      addLayer(
        name = "Retrieved Memory",
        kind = PromptLayerKind.CONTEXT,
        content = input.memoryText,
      )
      addLayer(
        name = "Pruning Summary",
        kind = PromptLayerKind.CONTEXT,
        content = input.pruningSummary?.text.orEmpty(),
      )
      addLayer(
        name = "Compaction Summary",
        kind = PromptLayerKind.CONTEXT,
        content = input.compactionSummary?.text.orEmpty(),
      )
      addLayer(
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = renderToolProtocolLayer(input.toolDefinitions),
      )
      addLayer(
        name = "Task Context",
        kind = PromptLayerKind.CONTEXT,
        content = renderTaskContextLayer(
          task = input.task,
          transcriptWindow = input.transcriptWindow,
        ),
      )
    }
    val systemLayers = layers.filter { layer -> layer.kind == PromptLayerKind.SYSTEM }
    val taskLayers = layers.filter { layer -> layer.kind != PromptLayerKind.SYSTEM }

    return AssembledPrompt(
      systemPrompt = renderLayers(systemLayers),
      taskPrompt = renderLayers(taskLayers),
      layers = layers,
      report = ContextAssemblyReport(
        layers = layers.map(::toLayerReport),
        sourceTranscriptMessageCount = input.report.sourceTranscriptMessageCount,
        windowedTranscriptMessageCount = input.report.windowedTranscriptMessageCount,
        omittedTranscriptMessageCount = input.report.omittedTranscriptMessageCount,
        truncatedTranscriptMessageCount = input.report.truncatedTranscriptMessageCount,
        prunedTranscriptMessageCount = input.report.prunedTranscriptMessageCount,
        rewrittenTranscriptMessageCount = input.report.rewrittenTranscriptMessageCount,
        duplicateBackgroundTranscriptMessageCount = input.report.duplicateBackgroundTranscriptMessageCount,
        bulkyToolTranscriptRewriteCount = input.report.bulkyToolTranscriptRewriteCount,
        attachmentLikeTranscriptRewriteCount = input.report.attachmentLikeTranscriptRewriteCount,
        pruningSummaryIncluded = input.report.pruningSummaryIncluded,
        compactedTranscriptMessageCount = input.report.compactedTranscriptMessageCount,
        compactionSummaryIncluded = input.report.compactionSummaryIncluded,
        matchedMemoryRecordCount = input.report.matchedMemoryRecordCount,
        injectedMemoryRecordCount = input.report.injectedMemoryRecordCount,
        omittedMemoryRecordCount = input.report.omittedMemoryRecordCount,
        memoryRecallTrace = input.report.memoryRecallTrace,
      ),
    )
  }

  private fun MutableList<PromptLayer>.addLayer(
    name: String,
    kind: PromptLayerKind,
    content: String,
  ) {
    val normalizedContent = content.trim()
    if (normalizedContent.isBlank()) {
      return
    }
    add(
      PromptLayer(
        name = name,
        kind = kind,
        content = normalizedContent,
      ),
    )
  }

  private fun renderToolProtocolLayer(toolDefinitions: List<AgentToolDefinition>): String = buildString {
    appendLine("Decide the next step for this OpenCray task.")
    appendLine()
    appendLine("On each turn, return exactly one JSON action and nothing else.")
    appendLine("Use one of these shapes:")
    appendLine("""{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""")
    appendLine("""{"type":"tool_call","tool_name":"Write","reason":"Need to update the workspace before answering.","arguments":{"file_path":"notes.txt","content":"..."}}""")
    appendLine("""{"type":"final","answer":"Concise answer for the user."}""")
    appendLine("If you return type=tool_call, the runtime will execute it, append the tool result, and ask you for the next action.")
    appendLine("If you need multiple tools, call only the next tool now. After each tool result the runtime will ask for the next action.")
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    appendLine("Do not return multiple tool calls in one response.")
    appendLine()
    appendLine("Available tools:")
    toolDefinitions.forEach { definition ->
      appendLine(definition.renderForPrompt())
    }
    appendLine()
    append("Only return type=final when you are ready to answer the user.")
  }.trim()

  private fun renderTaskContextLayer(
    task: com.opencray.core.contracts.AgentTask,
    transcriptWindow: TranscriptWindow,
  ): String = buildString {
    appendLine("Task metadata:")
    appendLine("task_id=${task.id}")
    appendLine("task_type=${task.type.name}")
    val visibleMetadata = task.metadata
      .filterKeys(::isLlmVisibleMetadataKey)
    if (visibleMetadata.isNotEmpty()) {
      visibleMetadata.toSortedMap().forEach { (key, value) ->
        appendLine("$key=$value")
      }
    }
    appendLine()
    appendLine("Conversation:")
    if (transcriptWindow.omittedMessageCount > 0) {
      appendLine("[system]")
      appendLine("Omitted ${transcriptWindow.omittedMessageCount} older message(s) to keep the runtime window bounded.")
      appendLine()
    }
    if (transcriptWindow.messages.isEmpty()) {
      appendLine("[system]")
      append("No prior conversation context.")
      return@buildString
    }
    transcriptWindow.messages.forEach { entry ->
      appendLine("[${entry.role.name.lowercase()}]")
      appendLine(entry.content)
      appendLine()
    }
  }.trim()

  private fun renderLayers(layers: List<PromptLayer>): String = layers.joinToString(separator = "\n\n") { layer ->
    "[${layer.name}]\n${layer.content}"
  }

  private fun toLayerReport(layer: PromptLayer): ContextLayerReport {
    val characterCount = layer.content.length
    return ContextLayerReport(
      name = layer.name,
      kind = layer.kind,
      characterCount = characterCount,
      estimatedTokenCount = (characterCount + 3) / 4,
    )
  }

  private companion object {
    const val HIDDEN_METADATA_PREFIX: String = "_host."
    const val RUNTIME_RULES: String =
      "Operate as a workspace-first coding agent. Prefer tools over guessing when the answer depends on files or local execution."

    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(HIDDEN_METADATA_PREFIX)
  }
}
