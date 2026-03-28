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
        name = "Turn Response Policy",
        kind = PromptLayerKind.SYSTEM,
        content = input.turnResponsePolicyText,
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
        name = "Recent Working Observations",
        kind = PromptLayerKind.CONTEXT,
        content = input.recentToolObservationsText,
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
        content = renderToolProtocolLayer(
          toolDefinitions = input.toolDefinitions,
          nativeToolCallingEnabled = input.nativeToolCallingEnabled,
          parallelToolCallsEnabled = input.parallelToolCallsEnabled,
          legacyJsonFallbackEnabled = input.legacyJsonFallbackEnabled,
        ),
      )
      addLayer(
        name = TASK_METADATA_LAYER_NAME,
        kind = PromptLayerKind.CONTEXT,
        content = renderTaskMetadataLayer(task = input.task),
      )
      addLayer(
        name = CONVERSATION_LAYER_NAME,
        kind = PromptLayerKind.CONTEXT,
        content = renderConversationLayer(transcriptWindow = input.transcriptWindow),
      )
    }
    val systemLayers = layers.filter { layer -> layer.kind == PromptLayerKind.SYSTEM }
    val taskLayers = layers.filter { layer -> layer.kind != PromptLayerKind.SYSTEM }
    val contextLayers = taskLayers.filterNot { layer -> layer.name == CONVERSATION_LAYER_NAME }

    return AssembledPrompt(
      systemPrompt = renderLayers(systemLayers),
      contextPrompt = renderLayers(contextLayers),
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
        liveContextTrace = input.report.liveContextTrace,
        bootstrapTrace = input.report.bootstrapTrace,
        visibleSkillCount = input.report.visibleSkillCount,
        injectedSkillCount = input.report.injectedSkillCount,
        omittedSkillCount = input.report.omittedSkillCount,
        invalidSkillCount = input.report.invalidSkillCount,
        skillInventoryTrace = input.report.skillInventoryTrace,
        activeSkillTrace = input.report.activeSkillTrace,
        recentToolObservationCount = input.report.recentToolObservationCount,
        omittedRecentToolObservationCount = input.report.omittedRecentToolObservationCount,
        recentToolObservationLayerIncluded = input.report.recentToolObservationLayerIncluded,
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

  @Suppress("UNUSED_PARAMETER")
  private fun renderToolProtocolLayer(
    toolDefinitions: List<AgentToolDefinition>,
    nativeToolCallingEnabled: Boolean,
    parallelToolCallsEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
  ): String = buildString {
    val jsonProtocolEnabled = !nativeToolCallingEnabled
    val normalizedToolNames = toolDefinitions
      .map { definition -> definition.name.trim().lowercase() }
      .filter(String::isNotBlank)
      .toSet()
    val hasReadTool = hasAnyTool(normalizedToolNames, "read", "workspace_read_file")
    val hasListTool = hasAnyTool(normalizedToolNames, "ls", "workspace_list_files")
    val hasGrepTool = hasAnyTool(normalizedToolNames, "grep")
    val hasGlobTool = hasAnyTool(normalizedToolNames, "glob")
    val hasWriteTool = hasAnyTool(normalizedToolNames, "write", "workspace_write_file")
    val hasTodoWriteTool = hasAnyTool(normalizedToolNames, "todowrite")
    val hasBashTool = hasAnyTool(normalizedToolNames, "bash")
    val hasPythonExecTool = hasAnyTool(normalizedToolNames, "python_exec")
    val hasWebSearchTool = hasAnyTool(normalizedToolNames, "websearch")
    val hasWebFetchTool = hasAnyTool(normalizedToolNames, "webfetch")
    val hasImageGenerationTool = hasAnyTool(normalizedToolNames, "generateimage", "imagegenerate")
    val hasSpeechSynthesisTool = hasAnyTool(normalizedToolNames, "synthesizespeech", "texttospeech", "tts")
    val hasProcessStartTool = hasAnyTool(normalizedToolNames, "processstart")
    val hasProcessReadTool = hasAnyTool(normalizedToolNames, "processread")
    val hasProcessWaitTool = hasAnyTool(normalizedToolNames, "processwait")
    val hasTaskTool = hasAnyTool(normalizedToolNames, "task")
    val hasSpawnAgentTool = hasAnyTool(normalizedToolNames, "spawn_agent")
    val hasWaitAgentTool = hasAnyTool(normalizedToolNames, "wait_agent")
    val hasSendInputTool = hasAnyTool(normalizedToolNames, "send_input")
    val hasCloseAgentTool = hasAnyTool(normalizedToolNames, "close_agent")
    val hasMemorySearchTool = toolDefinitions.any { definition -> definition.name == "memory_search" }
    val hasMemoryGetTool = toolDefinitions.any { definition -> definition.name == "memory_get" }
    val hasImportTool = toolDefinitions.any { definition ->
      definition.name == "ImportFile" || definition.name == "workspace_import_file"
    }
    val hasChatAttachmentImportTool = toolDefinitions.any { definition ->
      definition.name == "import_chat_attachment"
    }
    val hasWorkspaceImageViewTool = toolDefinitions.any { definition ->
      definition.name == "view_workspace_image"
    }
    val hasWorkspacePdfViewTool = toolDefinitions.any { definition ->
      definition.name == "view_workspace_pdf"
    }
    val primaryToolCallExample = when {
      hasReadTool -> """{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}"""
      hasListTool -> """{"type":"tool_call","tool_name":"LS","arguments":{"path":"."}}"""
      hasGrepTool -> """{"type":"tool_call","tool_name":"Grep","arguments":{"pattern":"TODO","path":"."}}"""
      hasGlobTool -> """{"type":"tool_call","tool_name":"Glob","arguments":{"pattern":"**/*.kt","path":"."}}"""
      hasWriteTool -> """{"type":"tool_call","tool_name":"Write","reason":"Need to update the workspace before answering.","arguments":{"file_path":"notes.txt","content":"..."}}"""
      hasBashTool -> """{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}"""
      hasPythonExecTool -> """{"type":"tool_call","tool_name":"python_exec","arguments":{"script_path":"scripts/run.py","args":["--flag"]}}"""
      hasWebFetchTool -> """{"type":"tool_call","tool_name":"WebFetch","arguments":{"url":"https://example.com"}}"""
      hasImageGenerationTool -> """{"type":"tool_call","tool_name":"GenerateImage","arguments":{"prompt":"Landing page hero illustration","count":1}}"""
      hasSpeechSynthesisTool -> """{"type":"tool_call","tool_name":"SynthesizeSpeech","arguments":{"text":"Quick spoken summary for the user."}}"""
      hasProcessStartTool -> """{"type":"tool_call","tool_name":"ProcessStart","arguments":{"command":"npm","args":["run","dev"]}}"""
      else -> null
    }
    val toolCallExamples = linkedSetOf<String>().apply {
      primaryToolCallExample?.let(::add)
      if (hasBashTool) {
        add("""{"type":"tool_call","tool_name":"Bash","arguments":{"command":"git status"}}""")
      }
      if (hasPythonExecTool) {
        add("""{"type":"tool_call","tool_name":"python_exec","arguments":{"script_path":"scripts/run.py","args":["--flag"]}}""")
      }
      if (hasWebFetchTool) {
        add("""{"type":"tool_call","tool_name":"WebFetch","arguments":{"url":"https://example.com"}}""")
      }
      if (hasImageGenerationTool) {
        add("""{"type":"tool_call","tool_name":"GenerateImage","arguments":{"prompt":"Landing page hero illustration","count":1}}""")
      }
      if (hasSpeechSynthesisTool) {
        add("""{"type":"tool_call","tool_name":"SynthesizeSpeech","arguments":{"text":"Quick spoken summary for the user."}}""")
      }
      if (hasWriteTool) {
        add("""{"type":"tool_call","tool_name":"Write","reason":"Need to update the workspace before answering.","arguments":{"file_path":"notes.txt","content":"..."}}""")
      }
      if (hasProcessStartTool) {
        add("""{"type":"tool_call","tool_name":"ProcessStart","arguments":{"command":"npm","args":["run","dev"]}}""")
      }
    }
    appendLine("Decide the next step for this OpenCray task.")
    appendLine()
    when {
      nativeToolCallingEnabled -> {
        appendLine("Native tool calling is enabled for this run.")
        appendLine("Keep the user updated with short public commentary as you work.")
        appendLine("Before the first tool call, give a brief public plan that states the goal, key constraints, and immediate next step.")
        appendLine("Before making tool calls, send a brief public preamble explaining what you are about to do.")
        appendLine("When you need a tool, use the provider's native tool-calling interface.")
        appendLine("For native tool calling, put that preamble in assistant text alongside the native tool call when possible.")
        appendLine("If you need a short public status update before a tool call, put it in assistant text alongside that native tool call.")
        appendLine("When you are ready to answer the user, return a plain assistant text answer.")
      }

      else -> {
        appendLine("Keep the user updated with short public commentary as you work.")
        appendLine("Before the first tool call, give a brief public plan that states the goal, key constraints, and immediate next step.")
        appendLine("Before making tool calls, send a brief public preamble explaining what you are about to do.")
        appendLine("In this protocol, use a commentary action for that preamble.")
        appendLine("On each turn, return exactly one JSON object and nothing else.")
        appendLine("Use one of these shapes:")
      }
    }
    if (jsonProtocolEnabled) {
      appendLine("""{"type":"commentary","text":"Scanning README and Gradle files before editing."}""")
      toolCallExamples.forEach { example ->
        appendLine(example)
      }
      primaryToolCallExample?.let { toolCallExample ->
        appendLine("""{"actions":[{"type":"commentary","text":"Scanning README and Gradle files before editing."},$toolCallExample]}""")
      }
      appendLine("""{"actions":[{"type":"commentary","text":"Summarizing the confirmed workspace facts."},{"type":"final","answer":"Concise answer for the user."}]}""")
      appendLine("""{"type":"final","answer":"Concise answer for the user."}""")
      appendLine("""{"type":"final","answer":"Here is the workspace image.\n\n![diagram.png](attachment:docs/diagram.png)","attachments":[{"relative_path":"docs/diagram.png","kind":"image"}]}""")
      appendLine("""{"type":"final","answer":"Attached the workspace file.","attachments":[{"relative_path":"docs/report.pdf","kind":"file"}]}""")
      if (hasImageGenerationTool || hasSpeechSynthesisTool || hasWriteTool || hasImportTool || hasChatAttachmentImportTool) {
        appendLine("""{"type":"final","answer":"Attached the generated media.","attachments":[{"artifact_id":"artifact-example-1234abcd","kind":"image"}]}""")
        appendLine("""{"type":"final","answer":"Here is the generated image inline.\n\n![diagram.png](attachment:artifact-example-1234abcd)","attachments":[{"artifact_id":"artifact-example-1234abcd","kind":"image"}]}""")
        appendLine("""{"type":"final","answer":"Here is the uploaded image.\n\n![camera_first.jpg](attachment:user-image-1)","attachments":[{"chat_attachment_id":"user-image-1","kind":"image"}]}""")
      }
    }
    if (nativeToolCallingEnabled) {
      appendLine("A commentary update is a short public status update for the user.")
    } else {
      appendLine("A commentary action is a short public status update for the user.")
    }
    appendLine("Group related tool reads or searches under one preamble instead of repeating trivial updates for every tiny action.")
    appendLine("After you learn something important, connect the next preamble to that new context so the user can follow your reasoning and momentum.")
    appendLine("Never expose raw private chain-of-thought, hidden safety reasoning, or secrets inside commentary.")
    if (jsonProtocolEnabled) {
      if (parallelToolCallsEnabled) {
        appendLine("If you use an actions array, emit at most one commentary action first, then either one or more tool_call actions, or exactly one final action.")
      } else {
        appendLine("If you use an actions array, emit at most one commentary action first, then exactly one terminal action.")
      }
      appendLine("If you return type=tool_call, the runtime will execute it, append the tool result, and ask you for the next action.")
      appendLine("If you return only type=commentary, the runtime will record it and ask you for the next action on the following turn.")
    } else {
      appendLine("Do not describe a tool call in plain prose.")
    }
    if (parallelToolCallsEnabled) {
      appendLine("When multiple independent tools are needed, you may return multiple tool calls in one response.")
      appendLine("Only batch tools that do not depend on each other's outputs.")
    } else {
      appendLine("If you need multiple tools, call only the next tool now. After each tool result the runtime will ask for the next action.")
    }
    if (hasTodoWriteTool) {
      appendLine("For non-trivial work with multiple concrete steps, use TodoWrite to keep a short live plan.")
      appendLine("Omit todos to read the current plan without mutating it, and send todos=[] only when you intentionally want to clear the current plan.")
      appendLine("TodoWrite entries must keep unique content, allow at most one in_progress item, and only that in_progress item may set activeForm.")
      appendLine("Keep the plan aligned with reality after meaningful progress, and before returning the final answer make sure the plan state is accurate.")
    }
    if (hasBashTool) {
      appendLine("Use Bash for one-off shell commands that do not require Python. Bash runs through the host shell, so use PowerShell syntax on Windows hosts.")
    }
    if (hasWebSearchTool || hasWebFetchTool) {
      appendLine("For current information from the internet, prefer WebSearch when a search provider is configured, and use WebFetch when you already have a URL to read.")
    }
    if (hasProcessStartTool || hasProcessReadTool || hasProcessWaitTool) {
      appendLine("For commands you want to manage across multiple turns, prefer ProcessStart and then use ProcessRead or ProcessWait.")
    }
    if (hasPythonExecTool) {
      appendLine("For workspace-local Python scripts, prefer python_exec instead of Bash.")
      appendLine("For Python runtime inspection or diagnostics such as version checks, sys.path, imports, or environment behavior, do not use Bash. Create or reuse a small workspace-local probe script and run it with python_exec.")
    }
    if (hasTaskTool) {
      appendLine("Use Task for simple synchronous delegation when you want to wait immediately for one child result.")
    }
    if (hasSpawnAgentTool && hasWaitAgentTool) {
      appendLine("Use spawn_agent when you need an explicit child handle that you may wait on later.")
      appendLine("Use wait_agent to execute or resume a queued child handle after spawn_agent.")
    }
    if (hasSendInputTool) {
      appendLine("Use send_input only before a queued child starts running. It appends more parent instructions; it is not a mid-run interrupt.")
    }
    if (hasCloseAgentTool) {
      appendLine("Use close_agent to stop tracking a queued or waiting child handle when that child should not continue.")
    }
    if (hasTaskTool && hasSpawnAgentTool && hasWaitAgentTool) {
      appendLine("Prefer Task for one-off delegation. Prefer spawn_agent plus wait_agent when you need explicit control over the child handle.")
    }
    if (hasProcessStartTool && hasPythonExecTool) {
      appendLine("If you need to manage a long-running Python task across multiple turns, use ProcessStart with script_path only when the runtime supports managed Python process launches.")
    }
    if (hasBashTool && hasPythonExecTool) {
      appendLine("Do not use Bash to invoke python, python3, or py for workspace scripts or Python-related diagnostics.")
    }
    if (hasImportTool) {
      appendLine("When task metadata includes approvedReadRoots, you may inspect those roots with absolute paths.")
      appendLine("Approved external roots are read-only. Use ImportFile to copy files or folders into the writable workspace before editing, deleting, or other mutating operations.")
    }
    if (hasChatAttachmentImportTool) {
      appendLine("Uploaded chat attachments are chat resources, not workspace files.")
      appendLine("If the model can already inspect an uploaded image directly, do not import it unless you need a workspace copy.")
      appendLine("Use import_chat_attachment only when you intentionally want to save one existing chat attachment into the workspace.")
      appendLine("When you need to inspect a non-image chat attachment with normal file tools, first decide whether to call import_chat_attachment.")
    }
    if (hasWorkspaceImageViewTool) {
      appendLine("If you need to inspect what a readable workspace image actually contains, call view_workspace_image instead of guessing from the path, filename, or nearby text.")
      appendLine("view_workspace_image attaches that workspace image into the next model turn for direct visual inspection.")
      appendLine("After calling view_workspace_image, wait for the next turn and inspect the attached image directly before taking further action.")
    }
    if (hasWorkspacePdfViewTool) {
      appendLine("If you need to inspect what a readable workspace PDF actually contains, call view_workspace_pdf instead of guessing from the path, filename, or nearby text.")
      appendLine("view_workspace_pdf attaches that workspace PDF into the next model turn for direct inspection when the current model accepts PDF inputs.")
      appendLine("After calling view_workspace_pdf, wait for the next turn and inspect the attached PDF directly before taking further action.")
    }
    if (jsonProtocolEnabled) {
      appendLine("When a tool result produces attachment artifacts, you may send them in the final action by adding attachments with artifact_id.")
      appendLine("When you need to resend a file or image that already exists in the chat history, attach it with chat_attachment_id.")
      appendLine("Use chat_attachment_id to resend an existing chat upload back to the user. Use import_chat_attachment to copy that upload into the workspace.")
      appendLine("Do not claim that you attached or sent a file unless the final action attachments array actually includes it.")
      appendLine("Do not rely on markdown alone to send an attachment. Markdown attachment references only control inline placement in the rendered answer.")
      appendLine("If you want an attachment to appear inline inside the written answer, you must do both: include it in the attachments array and reference the same token with markdown using attachment:<token>.")
      appendLine("For ordinary file cards, you may omit markdown and just attach the file in the attachments array.")
      appendLine("For inline attachment markdown, use the attachment display name as the markdown label.")
      appendLine("Use the concrete relative_path, artifact_id, or chat_attachment_id as the attachment:<token> value. Do not use a generic placeholder such as attachment:artifact unless that literal token is the real identifier.")
      appendLine("When resending an existing chat upload, prefer chat_attachment_id over guessing by filename.")
      appendLine("Use relative_path only for files that already exist inside the workspace.")
      appendLine("Generated speech should usually be attached with kind=voice so the chat uses the built-in voice player.")
      appendLine("If you intentionally want an audio file card instead of a voice message, attach the same artifact_id with kind=file.")
    }
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    if (!parallelToolCallsEnabled) {
      appendLine("Do not return multiple tool calls in one response.")
    }
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

  private fun hasAnyTool(toolNames: Set<String>, vararg candidates: String): Boolean =
    candidates.any { candidate -> candidate in toolNames }

  private fun renderBootstrapLayer(file: com.opencray.runtime.bootstrap.BootstrapSnippet): String = buildString {
    appendLine("source_file=${file.relativePath}")
    appendLine("truncated=${file.truncated}")
    appendLine()
    append(file.content)
  }.trim()

  private fun renderTaskMetadataLayer(
    task: com.opencray.core.contracts.AgentTask,
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
  }.trim()

  private fun renderConversationLayer(
    transcriptWindow: TranscriptWindow,
  ): String = buildString {
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
    const val TASK_METADATA_LAYER_NAME: String = "Task Metadata"
    const val CONVERSATION_LAYER_NAME: String = "Conversation"
    const val HIDDEN_METADATA_PREFIX: String = "_host."
    const val RUNTIME_RULES: String =
      "Operate as a workspace-first coding agent. Prefer tools over guessing when the answer depends on files or local execution."

    fun isLlmVisibleMetadataKey(key: String): Boolean = !key.startsWith(HIDDEN_METADATA_PREFIX)
  }
}
