package com.opencray.runtime.context

import com.opencray.runtime.AgentToolDefinition

class PromptAssembler(
  private val transcriptWindowBuilder: TranscriptWindowBuilder = TranscriptWindowBuilder(),
) {
  fun assemble(input: PromptAssemblyInput): AssembledPrompt {
    val transcriptWindow = transcriptWindowBuilder.build(input.liveConversation)
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
        content = input.sessionContext.sessionPolicyText.orEmpty().trim(),
      )
      addLayer(
        name = "Personalization",
        kind = PromptLayerKind.SYSTEM,
        content = renderSoulProfile(input.sessionContext.soulProfile),
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
          transcriptWindow = transcriptWindow,
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
        sourceTranscriptMessageCount = input.liveConversation.count { message -> message.content.isNotBlank() },
        windowedTranscriptMessageCount = transcriptWindow.messages.size,
        omittedTranscriptMessageCount = transcriptWindow.omittedMessageCount,
        truncatedTranscriptMessageCount = transcriptWindow.truncatedMessageCount,
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

  private fun renderSoulProfile(profile: RuntimeSoulProfile?): String = buildString {
    if (profile == null) {
      return@buildString
    }
    profile.displayName?.trim()?.takeIf(String::isNotBlank)?.let { value ->
      appendLine("display_name=$value")
    }
    profile.presetName?.trim()?.takeIf(String::isNotBlank)?.let { value ->
      appendLine("preset=$value")
    }
    profile.voice?.trim()?.takeIf(String::isNotBlank)?.let { value ->
      appendLine("voice=$value")
    }
    profile.customGuidance?.trim()?.takeIf(String::isNotBlank)?.let { value ->
      appendLine("custom_guidance=$value")
    }
  }.trim()

  private fun renderToolProtocolLayer(toolDefinitions: List<AgentToolDefinition>): String = buildString {
    appendLine("Decide the next step for this OpenCray task.")
    appendLine()
    appendLine("Return exactly one JSON object and nothing else.")
    appendLine("Use one of these shapes:")
    appendLine("""{"type":"tool_call","tool_name":"Read","arguments":{"file_path":"README.md"}}""")
    appendLine("""{"type":"final","answer":"Concise answer for the user."}""")
    appendLine()
    appendLine("Available tools:")
    toolDefinitions.forEach { definition ->
      appendLine(definition.renderForPrompt())
    }
    appendLine()
    append("If you already have enough evidence, respond with type=final.")
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
