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
      input.bootstrapFiles.forEach { file ->
        addLayer(
          name = "Bootstrap ${file.name}",
          kind = PromptLayerKind.SYSTEM,
          content = renderBootstrapLayer(file),
        )
      }
      addLayer(
        name = "Retrieved Memory",
        kind = PromptLayerKind.CONTEXT,
        content = input.memoryText,
      )
      addLayer(
        name = "Durable Compaction",
        kind = PromptLayerKind.CONTEXT,
        content = input.durableCompactionText,
      )
      addLayer(
        name = "Skill Inventory",
        kind = PromptLayerKind.CONTEXT,
        content = input.skillInventoryText,
      )
      addLayer(
        name = "Active Skill",
        kind = PromptLayerKind.CONTEXT,
        content = input.activeSkillText,
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
        memoryFlushTrace = input.report.memoryFlushTrace,
        durableCompactionTrace = input.report.durableCompactionTrace,
        bootstrapTrace = input.report.bootstrapTrace,
        visibleSkillCount = input.report.visibleSkillCount,
        injectedSkillCount = input.report.injectedSkillCount,
        omittedSkillCount = input.report.omittedSkillCount,
        invalidSkillCount = input.report.invalidSkillCount,
        skillInventoryTrace = input.report.skillInventoryTrace,
        activeSkillTrace = input.report.activeSkillTrace,
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
    val hasMemorySearchTool = toolDefinitions.any { definition -> definition.name == "memory_search" }
    val hasMemoryGetTool = toolDefinitions.any { definition -> definition.name == "memory_get" }
    val hasImportTool = toolDefinitions.any { definition ->
      definition.name == "ImportFile" || definition.name == "workspace_import_file"
    }
    appendLine("Decide the next step for this OpenCray task.")
    appendLine()
    appendLine("On each turn, return exactly one JSON object and nothing else.")
    appendLine("Use one of these shapes:")
    appendLine("""{"type":"progress","text":"Scanning README and Gradle files before editing."}""")
    appendLine("""{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""")
    appendLine("""{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}""")
    appendLine("""{"type":"tool_call","tool_name":"WebFetch","arguments":{"url":"https://example.com"}}""")
    appendLine("""{"type":"tool_call","tool_name":"Write","reason":"Need to update the workspace before answering.","arguments":{"file_path":"notes.txt","content":"..."}}""")
    appendLine("""{"actions":[{"type":"progress","text":"Scanning README and Gradle files before editing."},{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}]}""")
    appendLine("""{"actions":[{"type":"progress","text":"Summarizing the confirmed workspace facts."},{"type":"final","answer":"Concise answer for the user."}]}""")
    appendLine("""{"type":"final","answer":"Concise answer for the user."}""")
    appendLine("A progress action is a short public status update for the user.")
    appendLine("Never expose raw private chain-of-thought, hidden safety reasoning, or secrets inside progress.")
    appendLine("If you use an actions array, emit at most one progress action first, then exactly one terminal action.")
    appendLine("If you return type=tool_call, the runtime will execute it, append the tool result, and ask you for the next action.")
    appendLine("If you return only type=progress, the runtime will record it and ask you for the next action on the following turn.")
    appendLine("If you need multiple tools, call only the next tool now. After each tool result the runtime will ask for the next action.")
    appendLine("Use Bash for one-off shell commands. Bash runs through the host shell, so use PowerShell syntax on Windows hosts.")
    appendLine("For current information from the internet, prefer WebSearch when a search provider is configured, and use WebFetch when you already have a URL to read.")
    appendLine("For commands you want to manage across multiple turns, prefer ProcessStart and then use ProcessRead or ProcessWait.")
    appendLine("For long-running Python scripts, prefer ProcessStart with script_path instead of python_exec.")
    if (hasImportTool) {
      appendLine("When task metadata includes approvedReadRoots, you may inspect those roots with absolute paths.")
      appendLine("Approved external roots are read-only. Use ImportFile to copy files or folders into the writable workspace before editing, deleting, or other mutating operations.")
    }
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    appendLine("Do not return multiple tool calls in one response.")
    if (hasMemorySearchTool) {
      appendLine("When the user asks about prior work, earlier decisions, remembered preferences, dates, people, paths, or todos, search projected memory first instead of guessing from partial context.")
      if (hasMemoryGetTool) {
        appendLine("Use memory_search to locate the relevant memory path, then memory_get to read only the narrow line range you need.")
      }
    }
    appendLine()
    appendLine("Available tools:")
    toolDefinitions.forEach { definition ->
      appendLine(definition.renderForPrompt())
    }
    appendLine()
    append("Only return type=final when you are ready to answer the user.")
  }.trim()

  private fun renderBootstrapLayer(file: com.opencray.runtime.bootstrap.BootstrapSnippet): String = buildString {
    appendLine("source_file=${file.relativePath}")
    appendLine("truncated=${file.truncated}")
    appendLine()
    append(file.content)
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
