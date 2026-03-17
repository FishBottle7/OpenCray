package com.opencray.runtime.session

import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SessionTranscriptRules {
  const val MAX_MUTATION_TOOL_INTERACTIONS: Int = 2
  const val MAX_DISCOVERY_TOOL_INTERACTIONS: Int = 2
  const val MAX_EXECUTION_TOOL_INTERACTIONS: Int = 1
  const val MAX_STATEFUL_TOOL_INTERACTIONS: Int = 1
  const val MAX_PROGRESS_OBSERVATIONS: Int = 3
  const val MAX_GENERIC_TOOL_INTERACTIONS: Int = 1

  fun normalize(messages: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> =
    prune(repair(messages))

  fun repair(messages: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> {
    if (messages.isEmpty()) {
      return emptyList()
    }
    val repaired = ArrayList<RuntimeConversationMessage>(messages.size)
    messages.forEach { message ->
      val normalized = normalizeMessage(message)
      if (repaired.lastOrNull() == normalized) {
        return@forEach
      }
      repaired += normalized
    }
    return repaired
  }

  fun prune(messages: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> {
    if (messages.isEmpty()) {
      return emptyList()
    }
    val indexesToKeep = linkedSetOf<Int>()
    val groupedObservations = linkedMapOf<String, MutableList<ToolReplayObservation>>()

    messages.forEachIndexed { index, message ->
      val observation = classifyToolObservation(index, message)
      if (observation == null) {
        indexesToKeep += index
      } else {
        groupedObservations.getOrPut(observation.groupKey) { mutableListOf() } += observation
      }
    }

    groupedObservations.values
      .filterByCategory(ToolReplayCategory.MUTATION, MAX_MUTATION_TOOL_INTERACTIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }
    groupedObservations.values
      .filterByCategory(ToolReplayCategory.DISCOVERY, MAX_DISCOVERY_TOOL_INTERACTIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }
    groupedObservations.values
      .filterByCategory(ToolReplayCategory.EXECUTION, MAX_EXECUTION_TOOL_INTERACTIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }
    groupedObservations.values
      .filterByCategory(ToolReplayCategory.STATEFUL, MAX_STATEFUL_TOOL_INTERACTIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }
    groupedObservations.values
      .filterByCategory(ToolReplayCategory.PROGRESS, MAX_PROGRESS_OBSERVATIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }
    groupedObservations.values
      .filterByCategory(ToolReplayCategory.GENERIC, MAX_GENERIC_TOOL_INTERACTIONS)
      .forEach { interaction -> interaction.forEach { indexesToKeep += it.index } }

    return messages.filterIndexed { index, _ -> index in indexesToKeep }
  }

  private fun normalizeMessage(message: RuntimeConversationMessage): RuntimeConversationMessage {
    val normalizedContent = message.content.trim()
    return if (normalizedContent == message.content) {
      message
    } else {
      RuntimeConversationMessage(
        role = message.role,
        content = normalizedContent,
      )
    }
  }

  private fun isTerminalToolObservation(content: String): Boolean {
    val normalized = content.trim()
    return normalized.startsWith("approval_rejected") ||
      normalized.startsWith("run_cancelled") ||
      normalized.startsWith("run_interrupted") ||
      normalized.startsWith("retry_abandoned")
  }

  private fun classifyToolObservation(
    index: Int,
    message: RuntimeConversationMessage,
  ): ToolReplayObservation? {
    if (message.role != RuntimeConversationRole.TOOL) {
      return null
    }
    val normalizedContent = message.content.trim()
    if (isTerminalToolObservation(normalizedContent)) {
      return null
    }
    if (normalizedContent.startsWith("tool_call ")) {
      return parseReplayToolObservation(
        index = index,
        payload = normalizedContent.removePrefix("tool_call ").trim(),
      )
    }
    if (normalizedContent.startsWith("tool_result ")) {
      return parseReplayToolObservation(
        index = index,
        payload = normalizedContent.removePrefix("tool_result ").trim(),
      )
    }
    if (normalizedContent.startsWith("progress ")) {
      return parseReplayProgressObservation(
        index = index,
        payload = normalizedContent.removePrefix("progress ").trim(),
      )
    }
    return ToolReplayObservation(
      index = index,
      groupKey = "generic:$index",
      category = ToolReplayCategory.GENERIC,
    )
  }

  private fun parseReplayProgressObservation(
    index: Int,
    payload: String,
  ): ToolReplayObservation {
    val decoded = runCatching { replayJson.parseToJsonElement(payload).jsonObject }.getOrNull()
      ?: return ToolReplayObservation(
        index = index,
        groupKey = "progress:$index",
        category = ToolReplayCategory.PROGRESS,
      )

    val groupKey = listOf(
      decoded.stringValue("run_id").orEmpty(),
      decoded.stringValue("task_id").orEmpty(),
      decoded.stringValue("turn").orEmpty(),
      decoded.stringValue("stage").orEmpty(),
    ).joinToString(separator = "|")
      .takeIf(String::isNotBlank)
      ?.let { key -> "progress:$key" }
      ?: "progress:$index"
    return ToolReplayObservation(
      index = index,
      groupKey = groupKey,
      category = ToolReplayCategory.PROGRESS,
    )
  }

  private fun parseReplayToolObservation(
    index: Int,
    payload: String,
  ): ToolReplayObservation {
    val decoded = runCatching { replayJson.parseToJsonElement(payload).jsonObject }.getOrNull()
      ?: return ToolReplayObservation(
        index = index,
        groupKey = "generic:$index",
        category = ToolReplayCategory.GENERIC,
      )

    val toolName = decoded.stringValue("tool_name")
    return ToolReplayObservation(
      index = index,
      groupKey = buildInteractionKey(decoded, toolName) ?: "generic:$index",
      category = toolName?.let(::categoryForToolName) ?: ToolReplayCategory.GENERIC,
    )
  }

  private fun buildInteractionKey(
    payload: JsonObject,
    toolName: String?,
  ): String? {
    val runId = payload.stringValue("run_id")
    val taskId = payload.stringValue("task_id")
    val turn = payload.stringValue("turn")
    val normalizedToolName = toolName?.trim()?.takeIf(String::isNotBlank)
    if (runId == null && taskId == null && turn == null && normalizedToolName == null) {
      return null
    }
    return listOf(
      runId.orEmpty(),
      taskId.orEmpty(),
      turn.orEmpty(),
      normalizedToolName.orEmpty(),
    ).joinToString(separator = "|")
  }

  private fun categoryForToolName(toolName: String): ToolReplayCategory = when (toolName) {
    "Write",
    "write",
    "Edit",
    "edit",
    "MultiEdit",
    "multiedit",
    "workspace_write_file",
    "workspace_move_file",
    "workspace_delete_file",
    -> ToolReplayCategory.MUTATION

    "Read",
    "read",
    "LS",
    "ls",
    "list",
    "Grep",
    "grep",
    "Glob",
    "glob",
    "WebSearch",
    "websearch",
    "WebFetch",
    "webfetch",
    "workspace_list_files",
    "workspace_read_file",
    "skills_list",
    "skill_read",
    "memory_search",
    "memory_get",
    "mcp_list_servers",
    -> ToolReplayCategory.DISCOVERY

    "Bash",
    "bash",
    "command_exec",
    "python_exec",
    "ProcessStart",
    "processstart",
    "ProcessList",
    "processlist",
    "ProcessRead",
    "processread",
    "ProcessWait",
    "processwait",
    "ProcessTerminate",
    "processterminate",
    -> ToolReplayCategory.EXECUTION

    "TodoWrite",
    "todowrite",
    -> ToolReplayCategory.STATEFUL
    else -> ToolReplayCategory.GENERIC
  }

  private fun Iterable<List<ToolReplayObservation>>.filterByCategory(
    category: ToolReplayCategory,
    maxInteractions: Int,
  ): List<List<ToolReplayObservation>> =
    filter { interaction -> interaction.firstOrNull()?.category == category }
      .takeLast(maxInteractions)

  private fun JsonObject.stringValue(key: String): String? =
    this[key]
      ?.jsonPrimitive
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private data class ToolReplayObservation(
    val index: Int,
    val groupKey: String,
    val category: ToolReplayCategory,
  )

  private enum class ToolReplayCategory {
    MUTATION,
    DISCOVERY,
    EXECUTION,
    STATEFUL,
    PROGRESS,
    GENERIC,
  }

  private val replayJson: Json = Json { ignoreUnknownKeys = true }
}
