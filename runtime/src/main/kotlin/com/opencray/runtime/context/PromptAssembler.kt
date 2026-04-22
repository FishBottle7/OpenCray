package com.opencray.runtime.context

import com.opencray.runtime.AgentToolDefinition
import com.opencray.runtime.bootstrap.BootstrapPromptLayer

private data class ToolProtocolLayerRenderResult(
  val text: String,
  val trace: ToolProtocolTrace,
)

private enum class ToolProtocolDetailMode(
  val wireValue: String,
) {
  FULL("full"),
  COMPACT("compact"),
  MINIMAL("minimal"),
}

class PromptAssembler(
  private val budgetCoordinator: GlobalContextBudgetCoordinator = GlobalContextBudgetCoordinator(),
  private val bootstrapPromptLayer: BootstrapPromptLayer = BootstrapPromptLayer(),
  private val compactionSummaryPromptLayer: CompactionSummaryPromptLayer = CompactionSummaryPromptLayer(),
  private val pruningSummaryPromptLayer: TranscriptPruningSummaryPromptLayer = TranscriptPruningSummaryPromptLayer(),
  private val toolProtocolBudgetPolicy: ModelContextBudgetPolicy = ModelContextBudgetPolicy(),
) {
  fun assemble(input: ManagedPromptContext): AssembledPrompt {
    val toolProtocolLayer = renderToolProtocolLayer(
      toolDefinitions = input.toolDefinitions,
      nativeToolCallingEnabled = input.nativeToolCallingEnabled,
      parallelToolCallsEnabled = input.parallelToolCallsEnabled,
      legacyJsonFallbackEnabled = input.legacyJsonFallbackEnabled,
      llmMetadata = input.llmMetadata,
    )
    val initialLayers = buildList {
      addLayer(
        id = PromptLayerId.IDENTITY,
        name = "Identity",
        kind = PromptLayerKind.SYSTEM,
        content = input.baseSystemPrompt.trim(),
        transportGroup = transportGroupFor(PromptLayerId.IDENTITY, PromptLayerKind.SYSTEM),
      )
      addLayer(
        id = PromptLayerId.RUNTIME_RULES,
        name = "Runtime Rules",
        kind = PromptLayerKind.SYSTEM,
        content = RUNTIME_RULES,
        transportGroup = transportGroupFor(PromptLayerId.RUNTIME_RULES, PromptLayerKind.SYSTEM),
      )
      addLayer(
        id = PromptLayerId.SESSION_POLICY,
        name = "Session Policy",
        kind = PromptLayerKind.SYSTEM,
        content = input.sessionPolicyText,
        transportGroup = transportGroupFor(PromptLayerId.SESSION_POLICY, PromptLayerKind.SYSTEM),
      )
      addLayer(
        id = PromptLayerId.PERSONALIZATION,
        name = "Personalization",
        kind = PromptLayerKind.SYSTEM,
        content = input.personalizationText,
        transportGroup = transportGroupFor(PromptLayerId.PERSONALIZATION, PromptLayerKind.SYSTEM),
      )
      addLayer(
        id = PromptLayerId.TURN_RESPONSE_POLICY,
        name = "Turn Response Policy",
        kind = PromptLayerKind.SYSTEM,
        content = input.turnResponsePolicyText,
        transportGroup = transportGroupFor(PromptLayerId.TURN_RESPONSE_POLICY, PromptLayerKind.SYSTEM),
      )
      input.bootstrapFiles.forEach { file ->
        val renderedBootstrap = bootstrapPromptLayer.render(file)
        addLayer(
          id = PromptLayerId.BOOTSTRAP,
          name = renderedBootstrap.layerName,
          kind = PromptLayerKind.SYSTEM,
          content = renderedBootstrap.text,
          transportGroup = transportGroupFor(PromptLayerId.BOOTSTRAP, PromptLayerKind.SYSTEM),
        )
      }
      addLayer(
        id = PromptLayerId.WORKING_STATE,
        name = "Working State",
        kind = PromptLayerKind.CONTEXT,
        content = input.workingStateText,
        transportGroup = transportGroupFor(PromptLayerId.WORKING_STATE, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.RETRIEVED_MEMORY,
        name = "Retrieved Memory",
        kind = PromptLayerKind.CONTEXT,
        content = input.memoryText,
        transportGroup = transportGroupFor(PromptLayerId.RETRIEVED_MEMORY, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.DURABLE_COMPACTION,
        name = "Durable Compaction",
        kind = PromptLayerKind.CONTEXT,
        content = input.durableCompactionText,
        transportGroup = transportGroupFor(PromptLayerId.DURABLE_COMPACTION, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.SKILL_INVENTORY,
        name = "Skill Inventory",
        kind = PromptLayerKind.CONTEXT,
        content = input.skillInventoryText,
        transportGroup = transportGroupFor(PromptLayerId.SKILL_INVENTORY, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.ACTIVE_SKILL,
        name = "Active Skill",
        kind = PromptLayerKind.CONTEXT,
        content = input.activeSkillText,
        transportGroup = transportGroupFor(PromptLayerId.ACTIVE_SKILL, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.RECENT_TOOL_OBSERVATIONS,
        name = "Recent Working Observations",
        kind = PromptLayerKind.CONTEXT,
        content = input.recentToolObservationsText,
        transportGroup = transportGroupFor(PromptLayerId.RECENT_TOOL_OBSERVATIONS, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.PRUNING_SUMMARY,
        name = "Pruning Summary",
        kind = PromptLayerKind.CONTEXT,
        content = pruningSummaryPromptLayer.render(input.pruningSummary),
        transportGroup = transportGroupFor(PromptLayerId.PRUNING_SUMMARY, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.COMPACTION_SUMMARY,
        name = "Compaction Summary",
        kind = PromptLayerKind.CONTEXT,
        content = compactionSummaryPromptLayer.render(input.compactionSummary),
        transportGroup = transportGroupFor(PromptLayerId.COMPACTION_SUMMARY, PromptLayerKind.CONTEXT),
      )
      addLayer(
        id = PromptLayerId.TOOL_PROTOCOL,
        name = "Tool Protocol",
        kind = PromptLayerKind.PROTOCOL,
        content = toolProtocolLayer.text,
        transportGroup = transportGroupFor(PromptLayerId.TOOL_PROTOCOL, PromptLayerKind.PROTOCOL),
      )
      addLayer(
        id = PromptLayerId.CONVERSATION,
        name = CONVERSATION_LAYER_NAME,
        kind = PromptLayerKind.CONTEXT,
        content = renderConversationLayer(transcriptWindow = input.transcriptWindow),
        transportGroup = transportGroupFor(PromptLayerId.CONVERSATION, PromptLayerKind.CONTEXT),
      )
    }
    val coordinated = budgetCoordinator.rebalance(
      input = input,
      layers = initialLayers,
      estimateTokens = ::estimateTokenCount,
      renderConversationLayer = ::renderConversationLayer,
    )
    val layers = coordinated.layers
    val systemLayers = layers.filter { layer -> layer.kind == PromptLayerKind.SYSTEM }
    val taskLayers = layers.filter { layer -> layer.kind != PromptLayerKind.SYSTEM }
    val durableContextLayers = layers.filter { layer ->
      layer.transportGroup == PromptLayerTransportGroup.DURABLE_CONTEXT
    }
    val dynamicContextLayers = layers.filter { layer ->
      layer.transportGroup == PromptLayerTransportGroup.DYNAMIC_CONTEXT
    }
    val replayLayers = layers.filter { layer ->
      layer.transportGroup == PromptLayerTransportGroup.REPLAY_TRANSCRIPT
    }
    val contextLayers = durableContextLayers + dynamicContextLayers

    return AssembledPrompt(
      systemPrompt = renderLayers(systemLayers),
      contextPrompt = renderLayers(contextLayers),
      durableContextPrompt = renderLayers(durableContextLayers),
      dynamicContextPrompt = renderLayers(dynamicContextLayers),
      replayTranscriptPrompt = renderLayers(replayLayers),
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
        workingStateTrace = input.report.workingStateTrace,
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
        toolProtocolTrace = toolProtocolLayer.trace,
        budgetReport = coordinated.report,
      ),
    )
  }

  private fun MutableList<PromptLayer>.addLayer(
    id: PromptLayerId,
    name: String,
    kind: PromptLayerKind,
    content: String,
    transportGroup: PromptLayerTransportGroup,
  ) {
    val normalizedContent = content.trim()
    if (normalizedContent.isBlank()) {
      return
    }
    add(
      PromptLayer(
        id = id,
        name = name,
        kind = kind,
        content = normalizedContent,
        transportGroup = transportGroup,
      ),
    )
  }

  private fun transportGroupFor(
    id: PromptLayerId,
    kind: PromptLayerKind,
  ): PromptLayerTransportGroup {
    if (kind == PromptLayerKind.SYSTEM) {
      return PromptLayerTransportGroup.SYSTEM_PREFIX
    }
    return when (id) {
      PromptLayerId.DURABLE_COMPACTION,
      PromptLayerId.SKILL_INVENTORY,
      PromptLayerId.TOOL_PROTOCOL,
      -> PromptLayerTransportGroup.DURABLE_CONTEXT

      PromptLayerId.CONVERSATION -> PromptLayerTransportGroup.REPLAY_TRANSCRIPT

      // Automatic recall and the current active skill both vary with turn-local state.
      // Keep them behind the durable front zone until an explicit sticky/pinned contract exists.
      PromptLayerId.RETRIEVED_MEMORY,
      PromptLayerId.ACTIVE_SKILL,
      PromptLayerId.WORKING_STATE,
      PromptLayerId.RECENT_TOOL_OBSERVATIONS,
      PromptLayerId.PRUNING_SUMMARY,
      PromptLayerId.COMPACTION_SUMMARY,
      PromptLayerId.TASK_METADATA,
      -> PromptLayerTransportGroup.DYNAMIC_CONTEXT

      PromptLayerId.IDENTITY,
      PromptLayerId.RUNTIME_RULES,
      PromptLayerId.SESSION_POLICY,
      PromptLayerId.PERSONALIZATION,
      PromptLayerId.TURN_RESPONSE_POLICY,
      PromptLayerId.BOOTSTRAP,
      -> PromptLayerTransportGroup.SYSTEM_PREFIX
    }
  }

  @Suppress("UNUSED_PARAMETER")
  private fun renderToolProtocolLayer(
    toolDefinitions: List<AgentToolDefinition>,
    nativeToolCallingEnabled: Boolean,
    parallelToolCallsEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    llmMetadata: Map<String, String>,
  ): ToolProtocolLayerRenderResult = buildToolProtocolLayer(
    toolDefinitions = toolDefinitions,
    nativeToolCallingEnabled = nativeToolCallingEnabled,
    parallelToolCallsEnabled = parallelToolCallsEnabled,
    legacyJsonFallbackEnabled = legacyJsonFallbackEnabled,
    llmMetadata = llmMetadata,
  )

  @Suppress("LongMethod", "UNUSED_PARAMETER")
  private fun buildToolProtocolLayer(
    toolDefinitions: List<AgentToolDefinition>,
    nativeToolCallingEnabled: Boolean,
    parallelToolCallsEnabled: Boolean,
    legacyJsonFallbackEnabled: Boolean,
    llmMetadata: Map<String, String>,
  ): ToolProtocolLayerRenderResult {
    val detailMode = resolveToolProtocolDetailMode(llmMetadata)
    var exampleCount = 0
    var attachmentExampleCount = 0
    var toolSpecificGuidanceCount = 0
    val text = buildString {
      fun appendExample(
        value: String,
        attachmentExample: Boolean = false,
      ) {
        appendLine(value)
        exampleCount += 1
        if (attachmentExample) {
          attachmentExampleCount += 1
        }
      }

      fun appendToolGuidance(value: String) {
        appendLine(value)
        toolSpecificGuidanceCount += 1
      }

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
    val hasScheduledTaskTool = hasAnyTool(
      normalizedToolNames,
      "scheduledtaskcreate",
      "scheduled_task_create",
      "scheduledtasklist",
      "scheduled_task_list",
      "scheduledtaskget",
      "scheduled_task_get",
      "scheduledtaskupdate",
      "scheduled_task_update",
      "scheduledtaskdelete",
      "scheduled_task_delete",
    )
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
    val hasListSubAgentsTool = hasAnyTool(normalizedToolNames, "list_subagents")
    val hasMemorySearchTool = toolDefinitions.any { definition -> definition.name == "memory_search" }
    val hasMemoryGetTool = toolDefinitions.any { definition -> definition.name == "memory_get" }
    val hasSessionSearchTool = toolDefinitions.any { definition -> definition.name == "session_search" }
    val hasSessionGetTool = toolDefinitions.any { definition -> definition.name == "session_get" }
    val hasPastSessionSearchTool = toolDefinitions.any { definition -> definition.name == "past_session_search" }
    val hasPastSessionGetTool = toolDefinitions.any { definition -> definition.name == "past_session_get" }
    val hasAnySessionHistorySearchTool = hasSessionSearchTool || hasPastSessionSearchTool
    val hasImportTool = toolDefinitions.any { definition ->
      definition.name == "ImportFile" || definition.name == "workspace_import_file"
    }
    val hasChatAttachmentImportTool = toolDefinitions.any { definition ->
      definition.name == "import_chat_attachment"
    }
    val hasWorkspaceDocumentSearchTool = toolDefinitions.any { definition ->
      definition.name == "search_workspace_document"
    }
    val hasWorkspacePackageInspectTool = toolDefinitions.any { definition ->
      definition.name == "inspect_workspace_package"
    }
    val hasWorkspacePackageExtractTool = toolDefinitions.any { definition ->
      definition.name == "extract_workspace_package"
    }
    val hasWorkspaceDocumentViewTool = toolDefinitions.any { definition ->
      definition.name == "view_workspace_document"
    }
    val hasWorkspaceImageViewTool = toolDefinitions.any { definition ->
      definition.name == "view_workspace_image"
    }
    val hasWorkspacePdfViewTool = toolDefinitions.any { definition ->
      definition.name == "view_workspace_pdf"
    }
    val attachmentCapableToolAvailable =
      hasImageGenerationTool || hasSpeechSynthesisTool || hasWriteTool || hasImportTool || hasChatAttachmentImportTool
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
      appendExample("""{"type":"commentary","text":"Scanning README and Gradle files before editing."}""")
      primaryToolCallExample?.let(::appendExample)
      if (detailMode == ToolProtocolDetailMode.FULL) {
        toolCallExamples
          .drop(if (primaryToolCallExample != null) 1 else 0)
          .forEach(::appendExample)
      }
      if (detailMode != ToolProtocolDetailMode.MINIMAL) {
        primaryToolCallExample?.let { toolCallExample ->
          appendExample("""{"actions":[{"type":"commentary","text":"Scanning README and Gradle files before editing."},$toolCallExample]}""")
        }
        appendExample("""{"actions":[{"type":"commentary","text":"Summarizing the confirmed workspace facts."},{"type":"final","answer":"Concise answer for the user."}]}""")
      }
      appendExample("""{"type":"final","answer":"Concise answer for the user."}""")
      if (detailMode == ToolProtocolDetailMode.FULL) {
        appendExample(
          """{"type":"final","answer":"Here is the workspace image.\n\n![diagram.png](attachment:docs/diagram.png)","attachments":[{"relative_path":"docs/diagram.png","kind":"image"}]}""",
          attachmentExample = true,
        )
        appendExample(
          """{"type":"final","answer":"Attached the workspace file.","attachments":[{"relative_path":"docs/report.pdf","kind":"file"}]}""",
          attachmentExample = true,
        )
        if (attachmentCapableToolAvailable) {
          appendExample(
            """{"type":"final","answer":"Attached the generated media.","attachments":[{"artifact_id":"artifact-example-1234abcd","kind":"image"}]}""",
            attachmentExample = true,
          )
          appendExample(
            """{"type":"final","answer":"Here is the generated image inline.\n\n![diagram.png](attachment:artifact-example-1234abcd)","attachments":[{"artifact_id":"artifact-example-1234abcd","kind":"image"}]}""",
            attachmentExample = true,
          )
          appendExample(
            """{"type":"final","answer":"Here is the uploaded image.\n\n![camera_first.jpg](attachment:user-image-1)","attachments":[{"chat_attachment_id":"user-image-1","kind":"image"}]}""",
            attachmentExample = true,
          )
        }
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
      appendToolGuidance("For non-trivial work with multiple concrete steps, use TodoWrite to keep a short live plan.")
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("Omit todos to read the current plan without mutating it, and send todos=[] only when you intentionally want to clear the current plan.")
          appendToolGuidance("TodoWrite entries must keep unique content, allow at most one in_progress item, and only that in_progress item may set activeForm.")
          appendToolGuidance("Keep the plan aligned with reality after meaningful progress, and before returning the final answer make sure the plan state is accurate.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Keep TodoWrite aligned with reality. Allow at most one in_progress item, and only that item may set activeForm.")
        }
      }
    }
    if (hasScheduledTaskTool) {
      appendToolGuidance("When the user wants an automatic future follow-up, reminder, delayed retry, or recurring check-in, prefer ScheduledTaskCreate.")
      appendToolGuidance("For ScheduledTaskCreate, use trigger.at for one absolute time, trigger.after for one relative delay, or trigger.timezone plus trigger.start_at and trigger.rrule for recurrence. Do not calculate milliseconds manually.")
      appendToolGuidance("When the user asks what schedules already exist or when they will run next, use ScheduledTaskList or ScheduledTaskGet before guessing.")
      appendToolGuidance("When the user wants to change or remove an existing schedule, inspect it with ScheduledTaskGet first, then use ScheduledTaskUpdate or ScheduledTaskDelete.")
    }
    if (hasBashTool) {
      appendToolGuidance("Use Bash for one-off shell commands that do not require Python. Bash runs through the host shell, so use PowerShell syntax on Windows hosts.")
    }
    if (hasWebSearchTool || hasWebFetchTool) {
      appendToolGuidance("For current information from the internet, prefer WebSearch when a search provider is configured, and use WebFetch when you already have a URL to read.")
    }
    if (hasProcessStartTool || hasProcessReadTool || hasProcessWaitTool) {
      appendToolGuidance("For commands you want to manage across multiple turns, prefer ProcessStart and then use ProcessRead or ProcessWait.")
    }
    if (hasPythonExecTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("For workspace-local Python scripts, prefer python_exec instead of Bash.")
          appendToolGuidance("For Python runtime inspection or diagnostics such as version checks, sys.path, imports, or environment behavior, do not use Bash. Create or reuse a small workspace-local probe script and run it with python_exec.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("For workspace-local Python scripts or Python diagnostics, prefer python_exec and do not use Bash to invoke python, python3, or py.")
        }
      }
    }
    if (hasTaskTool) {
      appendToolGuidance("Use Task for simple synchronous delegation when you want to wait immediately for one child result.")
    }
    if (hasSpawnAgentTool && hasWaitAgentTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("Use spawn_agent when you need an explicit child handle and want that child to start immediately.")
          appendToolGuidance("Use wait_agent to block until a running child reaches its latest stable state and harvest its result.")
          appendToolGuidance("After user approval unlocks a paused child, the runtime resumes it through the detached recovery path; use wait_agent later to observe the new stable state.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use spawn_agent for explicit child handles that start immediately, and wait_agent later to observe or block on those delegated children.")
        }
      }
    }
    if (hasSendInputTool) {
      appendToolGuidance("Use send_input only while a child is queued or paused and waiting to resume. It queues mailbox input for the next child resume; it is not a mid-run interrupt.")
    }
    if (hasCloseAgentTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("Use close_agent to cancel a running or paused delegated child handle when that child should not continue, or to forget a completed one.")
          appendToolGuidance("Do not return a final answer while any delegated child handle is still open. Use wait_agent or close_agent first.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use close_agent to stop or forget delegated child handles, and do not finalize while any delegated child handle is still open.")
        }
      }
    }
    if (hasListSubAgentsTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("Use list_subagents to inspect the delegated child registry when you need to see handle ids, parent linkage, lifecycle state, approval wait state, or mailbox backlog before choosing wait_agent, send_input, or close_agent.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use list_subagents to inspect delegated child handle state before choosing wait_agent, send_input, or close_agent.")
        }
      }
    }
    if (hasTaskTool && hasSpawnAgentTool && hasWaitAgentTool) {
      appendToolGuidance("Prefer Task for one-off delegation. Prefer spawn_agent plus wait_agent when you need explicit control over the child handle.")
    }
    if (hasProcessStartTool && hasPythonExecTool) {
      appendToolGuidance("If you need to manage a long-running Python task across multiple turns, use ProcessStart with script_path only when the runtime supports managed Python process launches.")
    }
    if (hasBashTool && hasPythonExecTool && detailMode == ToolProtocolDetailMode.FULL) {
      appendToolGuidance("Do not use Bash to invoke python, python3, or py for workspace scripts or Python-related diagnostics.")
    }
    if (hasImportTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("When task metadata includes approvedReadRoots, you may inspect those roots with absolute paths.")
          appendToolGuidance("Approved external roots are read-only. Use ImportFile to copy files or folders into the writable workspace before editing, deleting, or other mutating operations.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Approved external roots are read-only. Use ImportFile before editing or deleting imported files.")
        }
      }
    }
    if (hasChatAttachmentImportTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("Uploaded chat attachments are chat resources, not workspace files.")
          appendToolGuidance("If the model can already inspect an uploaded image directly, do not import it unless you need a workspace copy.")
          appendToolGuidance("Use import_chat_attachment only when you intentionally want to save one existing chat attachment into the workspace.")
          appendToolGuidance("When you need to inspect a non-image chat attachment with normal file tools, first decide whether to call import_chat_attachment.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Uploaded chat attachments are not workspace files. Use import_chat_attachment only when you intentionally need a workspace copy.")
        }
      }
    }
    if (hasWorkspaceDocumentSearchTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need to locate relevant content inside a readable workspace PDF before attaching it, call search_workspace_document instead of guessing from the filename.")
          appendToolGuidance("search_workspace_document searches workspace PDFs locally and returns matching page numbers and excerpts.")
          appendToolGuidance("Use query, pages, page_from, and page_to to narrow the scan whenever you can.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use search_workspace_document to search readable workspace PDFs locally before guessing from filenames.")
        }
      }
    }
    if (hasWorkspacePackageInspectTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need to inspect the internal structure of a readable ZIP-based package such as zip, docx, xlsx, pptx, odt, ods, or odp, call inspect_workspace_package before guessing from the filename.")
          appendToolGuidance("inspect_workspace_package lists internal entries, previews specific safe text or XML parts, and returns package kind hints such as main document parts and relationship parts.")
          appendToolGuidance("Use glob and preview_entries to narrow inspection to the exact entries you need.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use inspect_workspace_package to inspect ZIP-based workspace packages before guessing from the filename.")
        }
      }
    }
    if (hasWorkspacePackageExtractTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need files from inside a readable ZIP-based package in the workspace, call extract_workspace_package with explicit entries or glob.")
          appendToolGuidance("extract_workspace_package requires entries or glob, writes only the selected package contents into destination_dir, and never defaults to full-package extraction.")
          appendToolGuidance("After extracting package files, use Read, Grep, Glob, or the workspace document view tools on the extracted workspace paths.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use extract_workspace_package with explicit entries or glob. It never defaults to full-package extraction.")
        }
      }
    }
    if (hasWorkspaceDocumentViewTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need to inspect what a readable workspace image or PDF actually contains, call view_workspace_document instead of guessing from the path, filename, or nearby text.")
          appendToolGuidance("When view_workspace_document is available, prefer it over the format-specific workspace view tools.")
          appendToolGuidance("view_workspace_document attaches that workspace image or PDF into the next model turn for direct inspection.")
          appendToolGuidance("After calling view_workspace_document, wait for the next turn and inspect the attached document directly before taking further action.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use view_workspace_document to inspect readable workspace images or PDFs directly, then wait for the next turn before acting.")
        }
      }
    }
    if (hasWorkspaceImageViewTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need to inspect what a readable workspace image actually contains, call view_workspace_image instead of guessing from the path, filename, or nearby text.")
          appendToolGuidance("view_workspace_image attaches that workspace image into the next model turn for direct visual inspection.")
          appendToolGuidance("After calling view_workspace_image, wait for the next turn and inspect the attached image directly before taking further action.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use view_workspace_image to inspect readable workspace images directly, then wait for the next turn before acting.")
        }
      }
    }
    if (hasWorkspacePdfViewTool) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("If you need to inspect what a readable workspace PDF actually contains, call view_workspace_pdf instead of guessing from the path, filename, or nearby text.")
          appendToolGuidance("view_workspace_pdf attaches that workspace PDF into the next model turn for direct inspection when the current model accepts PDF inputs.")
          appendToolGuidance("After calling view_workspace_pdf, wait for the next turn and inspect the attached PDF directly before taking further action.")
        }

        ToolProtocolDetailMode.COMPACT,
        ToolProtocolDetailMode.MINIMAL,
        -> {
          appendToolGuidance("Use view_workspace_pdf to inspect readable workspace PDFs directly, then wait for the next turn before acting.")
        }
      }
    }
    if (jsonProtocolEnabled) {
      when (detailMode) {
        ToolProtocolDetailMode.FULL -> {
          appendToolGuidance("When a tool result produces attachment artifacts, you may send them in the final action by adding attachments with artifact_id.")
          appendToolGuidance("When you need to resend a file or image that already exists in the chat history, attach it with chat_attachment_id.")
          appendToolGuidance("Use chat_attachment_id to resend an existing chat upload back to the user. Use import_chat_attachment to copy that upload into the workspace.")
          appendToolGuidance("Do not claim that you attached or sent a file unless the final action attachments array actually includes it.")
          appendToolGuidance("Do not rely on markdown alone to send an attachment. Markdown attachment references only control inline placement in the rendered answer.")
          appendToolGuidance("If you want an attachment to appear inline inside the written answer, you must do both: include it in the attachments array and reference the same token with markdown using attachment:<token>.")
          appendToolGuidance("For ordinary file cards, you may omit markdown and just attach the file in the attachments array.")
          appendToolGuidance("For inline attachment markdown, use the attachment display name as the markdown label.")
          appendToolGuidance("Use the concrete relative_path, artifact_id, or chat_attachment_id as the attachment:<token> value. Do not use a generic placeholder such as attachment:artifact unless that literal token is the real identifier.")
          appendToolGuidance("When resending an existing chat upload, prefer chat_attachment_id over guessing by filename.")
          appendToolGuidance("Use relative_path only for files that already exist inside the workspace.")
          appendToolGuidance("Generated speech should usually be attached with kind=voice so the chat uses the built-in voice player.")
          appendToolGuidance("If you intentionally want an audio file card instead of a voice message, attach the same artifact_id with kind=file.")
        }

        ToolProtocolDetailMode.COMPACT -> {
          appendToolGuidance("Do not claim that you attached or sent a file unless the final action attachments array actually includes it.")
          appendToolGuidance("Use relative_path for existing workspace files, artifact_id for generated artifacts, and chat_attachment_id to resend an existing chat upload.")
          appendToolGuidance("Do not rely on markdown alone to send an attachment. Use the attachments array, and reference attachment:<token> only when you want inline placement.")
          appendToolGuidance("For ordinary file cards, you may omit markdown and just attach the file in the attachments array.")
          appendToolGuidance("Generated speech should usually be attached with kind=voice.")
        }

        ToolProtocolDetailMode.MINIMAL -> {
          appendToolGuidance("If you send a file, image, or voice item, the final action attachments array must include it.")
          appendToolGuidance("Use relative_path for existing workspace files, artifact_id for generated artifacts, and chat_attachment_id for existing chat uploads.")
          appendToolGuidance("Do not rely on markdown alone to send an attachment.")
        }
      }
    }
    appendLine("A tool_call may include reason or justification, but it must not include a final answer.")
    if (!parallelToolCallsEnabled) {
      appendLine("Do not return multiple tool calls in one response.")
    }
    if (hasMemorySearchTool && hasAnySessionHistorySearchTool) {
      appendToolGuidance("Durable memory tools expose long-lived remembered records. Prior-session history tools expose bounded transcript snippets from earlier sessions.")
    }
    if (hasMemorySearchTool) {
      if (hasAnySessionHistorySearchTool) {
        appendToolGuidance("Use memory_search for durable remembered facts, stored preferences, dates, people, paths, or todos that should survive across sessions.")
      } else {
        appendToolGuidance("When the user asks about prior work, earlier decisions, remembered preferences, dates, people, paths, or todos, search projected memory first instead of guessing from partial context.")
      }
      if (hasMemoryGetTool) {
        appendToolGuidance("Use memory_search to locate the relevant memory path, then memory_get to read only the narrow line range you need.")
      }
    }
    if (hasSessionSearchTool) {
      appendToolGuidance("When the user asks what happened in an earlier chat or prior session, search projected session history instead of guessing from the current transcript.")
      if (hasSessionGetTool) {
        appendToolGuidance("Use session_search to locate the relevant prior-session path, then session_get to read only the narrow line range you need. session_search excludes the current session by default.")
      }
    }
    if (hasPastSessionSearchTool) {
      appendToolGuidance("When you need explicit continuity from other archived sessions, call past_session_search. This retrieval surface is tool-driven and not auto-injected.")
      if (hasPastSessionGetTool) {
        appendToolGuidance("Use past_session_search to get matched session summaries and key references, then call past_session_get only for the cited line range.")
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

    return ToolProtocolLayerRenderResult(
      text = text,
      trace = ToolProtocolTrace(
        detailMode = detailMode.wireValue,
        reducedForBudget = detailMode != ToolProtocolDetailMode.FULL,
        exampleCount = exampleCount,
        attachmentExampleCount = attachmentExampleCount,
        toolSpecificGuidanceCount = toolSpecificGuidanceCount,
        availableToolCount = toolDefinitions.size,
      ),
    )
  }

  private fun resolveToolProtocolDetailMode(
    llmMetadata: Map<String, String>,
  ): ToolProtocolDetailMode {
    llmMetadata.explicitToolProtocolDetailMode()?.let { detailMode ->
      return detailMode
    }
    val envelope = toolProtocolBudgetPolicy.resolve(llmMetadata)
    return when {
      envelope.targetInputBudgetTokens <= TOOL_PROTOCOL_MINIMAL_TARGET_TOKENS -> ToolProtocolDetailMode.MINIMAL
      envelope.targetInputBudgetTokens <= TOOL_PROTOCOL_COMPACT_TARGET_TOKENS -> ToolProtocolDetailMode.COMPACT
      else -> ToolProtocolDetailMode.FULL
    }
  }

  private fun Map<String, String>.explicitToolProtocolDetailMode(): ToolProtocolDetailMode? =
    sequenceOf("toolProtocolDetailMode", "tool_protocol_detail_mode")
      .mapNotNull { key -> this[key] }
      .map { value -> value.trim().lowercase() }
      .firstNotNullOfOrNull { rawValue ->
        when (rawValue) {
          ToolProtocolDetailMode.FULL.wireValue -> ToolProtocolDetailMode.FULL
          ToolProtocolDetailMode.COMPACT.wireValue -> ToolProtocolDetailMode.COMPACT
          ToolProtocolDetailMode.MINIMAL.wireValue -> ToolProtocolDetailMode.MINIMAL
          else -> null
        }
      }

  private fun hasAnyTool(toolNames: Set<String>, vararg candidates: String): Boolean =
    candidates.any { candidate -> candidate in toolNames }

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
      id = layer.id,
      name = layer.name,
      kind = layer.kind,
      transportGroup = layer.transportGroup,
      characterCount = characterCount,
      estimatedTokenCount = estimateTokenCount(layer.content),
    )
  }

  private fun estimateTokenCount(content: String): Int = (content.length + 3) / 4

  private companion object {
    const val TOOL_PROTOCOL_COMPACT_TARGET_TOKENS: Int = 1_100
    const val TOOL_PROTOCOL_MINIMAL_TARGET_TOKENS: Int = 700
    const val CONVERSATION_LAYER_NAME: String = "Conversation"
    const val RUNTIME_RULES: String =
      "Operate as a workspace-first coding agent. Prefer tools over guessing when the answer depends on files or local execution."
  }
}
