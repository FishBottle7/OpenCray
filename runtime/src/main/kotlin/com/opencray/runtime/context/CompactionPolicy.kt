package com.opencray.runtime.context

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CompactionPolicy(
  val minCompactedMessages: Int = 1,
  val maxSummaryChars: Int = 480,
  val maxPreviewChars: Int = 120,
  val maxToolCategoryEntries: Int = 3,
) {
  init {
    require(minCompactedMessages >= 1) { "CompactionPolicy minCompactedMessages must be >= 1." }
    require(maxSummaryChars >= 64) { "CompactionPolicy maxSummaryChars must be >= 64." }
    require(maxPreviewChars >= 24) { "CompactionPolicy maxPreviewChars must be >= 24." }
    require(maxToolCategoryEntries >= 1) { "CompactionPolicy maxToolCategoryEntries must be >= 1." }
  }

  fun summarize(omittedMessages: List<RuntimeConversationMessage>): CompactionSummary? {
    if (omittedMessages.size < minCompactedMessages) {
      return null
    }

    val omittedUserCount = omittedMessages.count { it.role == RuntimeConversationRole.USER }
    val omittedAssistantCount = omittedMessages.count { it.role == RuntimeConversationRole.ASSISTANT }
    val omittedToolCount = omittedMessages.count { it.role == RuntimeConversationRole.TOOL }
    val omittedSystemCount = omittedMessages.count { it.role == RuntimeConversationRole.SYSTEM }
    val terminalOutcomeCounts = linkedMapOf<String, Int>()
    val toolCategoryCounts = linkedMapOf<ToolSummaryCategory, LinkedHashSet<String>>()

    omittedMessages.forEachIndexed { index, message ->
      terminalOutcomeLabel(message.content)?.let { label ->
        terminalOutcomeCounts[label] = (terminalOutcomeCounts[label] ?: 0) + 1
      }
      classifyToolActivity(message, index)?.let { activity ->
        toolCategoryCounts.getOrPut(activity.category) { linkedSetOf() } += activity.groupKey
      }
    }

    val lines = mutableListOf<String>()
    lines += "Compacted ${omittedMessages.size} older message(s) outside the active transcript window."
    lines += buildRoleSummaryLine(
      omittedUserCount = omittedUserCount,
      omittedAssistantCount = omittedAssistantCount,
      omittedToolCount = omittedToolCount,
      omittedSystemCount = omittedSystemCount,
    )
    if (terminalOutcomeCounts.isNotEmpty()) {
      lines += "Omitted terminal outcomes: " +
        terminalOutcomeCounts.entries.joinToString(separator = ", ") { (label, count) -> "$label=$count" } +
        "."
    }
    if (toolCategoryCounts.isNotEmpty()) {
      lines += "Omitted tool activity: " +
        toolCategoryCounts.entries
          .sortedByDescending { (_, groups) -> groups.size }
          .take(maxToolCategoryEntries)
          .joinToString(separator = ", ") { (category, groups) ->
            "${category.label}=${groups.size}"
          } +
        "."
    }
    omittedMessages.lastOrNull { it.role == RuntimeConversationRole.USER }?.let { message ->
      lines += "Most recent omitted user request: ${preview(message.content)}"
    }
    omittedMessages.lastOrNull { message ->
      message.role == RuntimeConversationRole.ASSISTANT &&
        !message.isAssistantToolCallMessage()
    }?.let { message ->
      lines += "Most recent omitted assistant reply: ${preview(message.content)}"
    }

    return CompactionSummary(
      text = boundSummary(lines),
      compactedMessageCount = omittedMessages.size,
      omittedUserMessageCount = omittedUserCount,
      omittedAssistantMessageCount = omittedAssistantCount,
      omittedToolMessageCount = omittedToolCount,
      omittedSystemMessageCount = omittedSystemCount,
    )
  }

  private fun buildRoleSummaryLine(
    omittedUserCount: Int,
    omittedAssistantCount: Int,
    omittedToolCount: Int,
    omittedSystemCount: Int,
  ): String = buildString {
    append("Omitted roles:")
    append(" user=")
    append(omittedUserCount)
    append(", assistant=")
    append(omittedAssistantCount)
    append(", tool=")
    append(omittedToolCount)
    append(", system=")
    append(omittedSystemCount)
    append('.')
  }

  private fun terminalOutcomeLabel(content: String): String? {
    val normalized = content.trim()
    return when {
      normalized.startsWith("approval_approved") -> "approval_approved"
      normalized.startsWith("approval_rejected") -> "approval_rejected"
      normalized.startsWith("run_cancelled") -> "run_cancelled"
      normalized.startsWith("run_interrupted") -> "run_interrupted"
      normalized.startsWith("retry_abandoned") -> "retry_abandoned"
      else -> null
    }
  }

  private fun classifyToolActivity(
    message: RuntimeConversationMessage,
    index: Int,
  ): ToolActivity? {
    val normalized = message.content.trim()
    message.toolResultJsonPayloadOrNull()?.let { payload ->
      return classifyJsonToolActivity(
        payload = payload,
        fallbackGroupKey = "tool_result:$index",
      )
    }
    message.toolCallJsonPayloadOrNull()?.let { payload ->
      return classifyJsonToolActivity(
        payload = payload,
        fallbackGroupKey = "tool_call:$index",
      )
    }
    message.commentaryJsonPayloadOrNull()?.let {
      return ToolActivity(groupKey = "commentary:$index", category = ToolSummaryCategory.GENERIC)
    }
    when (message.kind) {
      RuntimeConversationMessageKind.COMMENTARY,
      RuntimeConversationMessageKind.TOOL_RESULT,
      RuntimeConversationMessageKind.TOOL_CALL,
      -> Unit
      RuntimeConversationMessageKind.PLAIN -> Unit
    }
    plainReplayJsonObjectOrNull(normalized)
      ?.takeIf(JsonObject::isSubAgentReplayPayload)
      ?.let {
        return classifyReplaySubAgentActivity(
          payload = normalized,
          fallbackGroupKey = "subagent:$index",
        )
      }
    return if (message.role == RuntimeConversationRole.TOOL) {
      ToolActivity(groupKey = "tool:$index", category = ToolSummaryCategory.GENERIC)
    } else {
      null
    }
  }

  private fun classifyJsonToolActivity(
    payload: String,
    fallbackGroupKey: String,
  ): ToolActivity {
    val decoded = runCatching {
      replayJson.parseToJsonElement(payload).jsonObject
    }.getOrNull()
    val toolName = decoded
      ?.get("tool_name")
      ?.jsonPrimitive
      ?.content
      ?.trim()
      ?.takeIf(String::isNotBlank)
    val groupKey = if (decoded == null) {
      fallbackGroupKey
    } else {
      listOf(
        decoded["run_id"]?.jsonPrimitive?.content.orEmpty(),
        decoded["task_id"]?.jsonPrimitive?.content.orEmpty(),
        decoded["turn"]?.jsonPrimitive?.content.orEmpty(),
        toolName.orEmpty(),
      ).joinToString(separator = "|")
    }
    return ToolActivity(
      groupKey = groupKey,
      category = toolCategoryForName(toolName),
    )
  }

  private fun classifyReplaySubAgentActivity(
    payload: String,
    fallbackGroupKey: String,
  ): ToolActivity {
    val decoded = runCatching {
      replayJson.parseToJsonElement(payload).jsonObject
    }.getOrNull()
    val groupKey = if (decoded == null) {
      fallbackGroupKey
    } else {
      listOf(
        decoded["run_id"]?.jsonPrimitive?.content.orEmpty(),
        decoded["task_id"]?.jsonPrimitive?.content.orEmpty(),
        decoded["child_run_id"]?.jsonPrimitive?.content.orEmpty(),
        decoded["child_task_id"]?.jsonPrimitive?.content.orEmpty(),
      ).joinToString(separator = "|")
    }
    return ToolActivity(
      groupKey = groupKey,
      category = ToolSummaryCategory.DELEGATION,
    )
  }

  private fun toolCategoryForName(toolName: String?): ToolSummaryCategory = when (toolName?.trim()?.lowercase()) {
    "write",
    "edit",
    "multiedit",
    "workspace_write_file",
    "workspace_move_file",
    "workspace_delete_file",
    "generateimage",
    "imagegenerate",
    "synthesizespeech",
    "texttospeech",
    "tts",
    "skillsadd",
    "skills_add",
    "skillsaddbatch",
    "skills_add_batch",
    "skillsupdate",
    "skills_update",
    "skillsremove",
    "skills_remove",
    -> ToolSummaryCategory.MUTATION

    "read",
    "ls",
    "list",
    "grep",
    "glob",
    "websearch",
    "webfetch",
    "workspace_list_files",
    "workspace_read_file",
    "skills_list",
    "skillslist",
    "skillsfind",
    "skills_find",
    "skillsinspect",
    "skills_inspect",
    "skillscheck",
    "skills_check",
    "skill_read",
    "memory_search",
    "memory_get",
    "session_search",
    "session_get",
    "past_session_search",
    "past_session_get",
    "mcp_list_servers",
    -> ToolSummaryCategory.DISCOVERY

    "bash",
    "command_exec",
    "python_exec",
    "processstart",
    "processlist",
    "processread",
    "processwait",
    "processterminate",
    -> ToolSummaryCategory.EXECUTION

    "todowrite",
    -> ToolSummaryCategory.STATEFUL
    else -> ToolSummaryCategory.GENERIC
  }

  private fun preview(content: String): String {
    val collapsed = content.replace(Regex("\\s+"), " ").trim()
    return if (collapsed.length <= maxPreviewChars) {
      collapsed
    } else {
      collapsed.take(maxPreviewChars - 1).trimEnd() + "…"
    }
  }

  private fun boundSummary(lines: List<String>): String {
    val builder = StringBuilder()
    lines.forEach { line ->
      if (line.isBlank()) {
        return@forEach
      }
      val next = if (builder.isEmpty()) line else builder.toString() + "\n" + line
      if (next.length > maxSummaryChars) {
        return@forEach
      }
      if (builder.isNotEmpty()) {
        builder.append('\n')
      }
      builder.append(line)
    }
    val summary = builder.toString().trim()
    return if (summary.length <= maxSummaryChars) {
      summary
    } else {
      summary.take(maxSummaryChars - 1).trimEnd() + "…"
    }
  }

  private data class ToolActivity(
    val groupKey: String,
    val category: ToolSummaryCategory,
  )

  private enum class ToolSummaryCategory(val label: String) {
    MUTATION("mutation"),
    DISCOVERY("discovery"),
    EXECUTION("execution"),
    STATEFUL("stateful"),
    DELEGATION("delegation"),
    GENERIC("generic"),
  }

  private companion object {
    val replayJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
  }
}
