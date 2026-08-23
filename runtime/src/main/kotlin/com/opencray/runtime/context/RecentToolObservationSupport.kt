package com.opencray.runtime.context

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.workingstate.WorkingStateEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RecentToolObservationConfig(
  val maxEntries: Int = 4,
  val maxCompactEntries: Int = 2,
  val maxMinimalEntries: Int = 1,
  val maxReadChars: Int = 2_400,
  val maxReadLines: Int = 96,
  val maxListChars: Int = 1_600,
  val maxListLines: Int = 32,
  val maxCompactBodyChars: Int = 640,
  val maxCompactBodyLines: Int = 16,
  val maxRenderedChars: Int = 7_200,
  val duplicateLookbackMessages: Int = 24,
  val duplicateExcerptChars: Int = 1_200,
) {
  init {
    require(maxEntries >= 1) { "RecentToolObservationConfig maxEntries must be >= 1." }
    require(maxCompactEntries >= 1) { "RecentToolObservationConfig maxCompactEntries must be >= 1." }
    require(maxMinimalEntries >= 1) { "RecentToolObservationConfig maxMinimalEntries must be >= 1." }
    require(maxCompactEntries <= maxEntries) {
      "RecentToolObservationConfig maxCompactEntries must be <= maxEntries."
    }
    require(maxMinimalEntries <= maxCompactEntries) {
      "RecentToolObservationConfig maxMinimalEntries must be <= maxCompactEntries."
    }
    require(maxReadChars >= 256) { "RecentToolObservationConfig maxReadChars must be >= 256." }
    require(maxReadLines >= 8) { "RecentToolObservationConfig maxReadLines must be >= 8." }
    require(maxListChars >= 128) { "RecentToolObservationConfig maxListChars must be >= 128." }
    require(maxListLines >= 4) { "RecentToolObservationConfig maxListLines must be >= 4." }
    require(maxCompactBodyChars >= 128) {
      "RecentToolObservationConfig maxCompactBodyChars must be >= 128."
    }
    require(maxCompactBodyLines >= 4) {
      "RecentToolObservationConfig maxCompactBodyLines must be >= 4."
    }
    require(maxRenderedChars >= 512) { "RecentToolObservationConfig maxRenderedChars must be >= 512." }
    require(duplicateLookbackMessages >= 1) {
      "RecentToolObservationConfig duplicateLookbackMessages must be >= 1."
    }
    require(duplicateExcerptChars >= 128) {
      "RecentToolObservationConfig duplicateExcerptChars must be >= 128."
    }
  }
}

enum class RecentToolObservationDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

data class RecentToolObservationItem(
  val signature: String,
  val summaryLine: String,
  val body: String,
) {
  init {
    require(signature.isNotBlank()) { "RecentToolObservationItem signature must not be blank." }
    require(summaryLine.isNotBlank()) { "RecentToolObservationItem summaryLine must not be blank." }
    require(body.isNotBlank()) { "RecentToolObservationItem body must not be blank." }
  }
}

data class RecentToolObservationLayer(
  val text: String,
  val items: List<RecentToolObservationItem>,
  val omittedObservationCount: Int = 0,
) {
  init {
    require(text.isNotBlank()) { "RecentToolObservationLayer text must not be blank." }
    require(items.isNotEmpty()) {
      "RecentToolObservationLayer items must not be empty."
    }
    require(omittedObservationCount >= 0) {
      "RecentToolObservationLayer omittedObservationCount must be >= 0."
    }
  }

  val observationCount: Int
    get() = items.size
}

data class DuplicateDiscoveryToolHit(
  val toolName: String,
  val signature: String,
  val summaryLine: String,
  val excerpt: String,
  val metadata: Map<String, String> = emptyMap(),
)

class RecentToolObservationSupport(
  internal val config: RecentToolObservationConfig = RecentToolObservationConfig(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  fun workingStateEntries(
    messages: List<RuntimeConversationMessage>,
    maxEntries: Int = DEFAULT_WORKING_STATE_ENTRIES,
  ): List<WorkingStateEntry> {
    require(maxEntries >= 1) { "RecentToolObservationSupport maxEntries must be >= 1." }
    val actions = collectWorkingStateActions(activeTaskMessages(messages))
    return selectLatestUniqueWorkingStateEntries(actions, maxEntries)
  }

  fun decisionEntries(
    messages: List<RuntimeConversationMessage>,
    maxEntries: Int = DEFAULT_DECISION_ENTRIES,
  ): List<WorkingStateEntry> {
    require(maxEntries >= 1) { "RecentToolObservationSupport maxEntries must be >= 1." }
    val decisions = collectDecisionEntries(activeTaskMessages(messages))
    return selectLatestUniqueWorkingStateEntries(decisions, maxEntries)
  }

  fun blockerEntries(
    messages: List<RuntimeConversationMessage>,
    maxEntries: Int = DEFAULT_BLOCKER_ENTRIES,
  ): List<WorkingStateEntry> {
    require(maxEntries >= 1) { "RecentToolObservationSupport maxEntries must be >= 1." }
    val blockers = collectBlockerEntries(activeTaskMessages(messages))
    return selectLatestUniqueWorkingStateEntries(blockers, maxEntries)
  }

  private fun selectLatestUniqueWorkingStateEntries(
    entries: List<RenderedWorkingStateAction>,
    maxEntries: Int,
  ): List<WorkingStateEntry> {
    if (entries.isEmpty()) {
      return emptyList()
    }
    val selected = mutableListOf<RenderedWorkingStateAction>()
    val seenSignatures = linkedSetOf<String>()
    entries.asReversed().forEach { entry ->
      if (!seenSignatures.add(entry.signature)) {
        return@forEach
      }
      if (selected.size >= maxEntries) {
        return@forEach
      }
      selected += entry
    }
    selected.reverse()
    return selected.map { entry -> entry.entry }
  }

  fun summaryLines(messages: List<RuntimeConversationMessage>): List<String> =
    selectObservations(messages).map { observation ->
      observation.summaryLine.removePrefix("- ").trim()
    }

  fun buildLayer(
    messages: List<RuntimeConversationMessage>,
    detailMode: RecentToolObservationDetailMode = RecentToolObservationDetailMode.FULL,
  ): RecentToolObservationLayer? {
    val observations = collectObservations(activeTaskMessages(messages))
    val selected = selectObservations(messages)
    if (selected.isEmpty()) {
      return null
    }
    return renderLayer(
      items = selected.map { observation ->
        RecentToolObservationItem(
          signature = observation.signature,
          summaryLine = observation.summaryLine,
          body = observation.body,
        )
      },
      omittedObservationCount = (observations.map(RenderedObservation::signature).distinct().size - selected.size)
        .coerceAtLeast(0),
      detailMode = detailMode,
    )
  }

  fun renderLayer(
    layer: RecentToolObservationLayer,
    detailMode: RecentToolObservationDetailMode = RecentToolObservationDetailMode.FULL,
  ): RecentToolObservationLayer = renderLayer(
    items = layer.items,
    omittedObservationCount = layer.omittedObservationCount,
    detailMode = detailMode,
  )

  private fun selectObservations(messages: List<RuntimeConversationMessage>): List<RenderedObservation> {
    val observations = collectObservations(activeTaskMessages(messages))
    if (observations.isEmpty()) {
      return emptyList()
    }
    val selected = mutableListOf<RenderedObservation>()
    val seenSignatures = linkedSetOf<String>()
    observations.asReversed().forEach { observation ->
      if (!seenSignatures.add(observation.signature)) {
        return@forEach
      }
      if (selected.size >= config.maxEntries) {
        return@forEach
      }
      selected += observation
    }
    if (selected.isEmpty()) {
      return emptyList()
    }
    selected.reverse()
    return selected
  }

  fun findDuplicateDiscoveryCall(
    messages: List<RuntimeConversationMessage>,
    call: AgentToolCall,
  ): DuplicateDiscoveryToolHit? {
    val signature = signatureForCall(call) ?: return null
    var inspectedMessages = 0
    val activeMessages = activeTaskMessages(messages)
    for (index in activeMessages.lastIndex downTo 0) {
      if (inspectedMessages >= config.duplicateLookbackMessages) {
        break
      }
      val message = activeMessages[index]
      inspectedMessages += 1
      if (message.role == RuntimeConversationRole.USER) {
        break
      }
      val parsed = parseToolResult(message) ?: continue
      if (parsed.metadata["duplicateGuard"] == "true") {
        continue
      }
      val category = categoryForToolName(parsed.toolName) ?: continue
      if (category == ObservationCategory.BARRIER) {
        break
      }
      if (category != ObservationCategory.DISCOVERY) {
        continue
      }
      val observation = renderObservation(parsed) ?: continue
      if (observation.signature != signature) {
        continue
      }
      return DuplicateDiscoveryToolHit(
        toolName = parsed.toolName,
        signature = signature,
        summaryLine = observation.summaryLine.removePrefix("- ").trim(),
        excerpt = boundExcerpt(observation.body),
        metadata = parsed.metadata,
      )
    }
    return null
  }

  fun duplicateDiscoverySignature(call: AgentToolCall): String? = signatureForCall(call)

  private fun collectObservations(messages: List<RuntimeConversationMessage>): List<RenderedObservation> =
    messages.mapNotNull(::parseToolResult)
      .filter { parsed ->
        parsed.status.equals("success", ignoreCase = true) &&
          parsed.metadata["duplicateGuard"] != "true" &&
          frontContextObservationCategoryForToolName(parsed.toolName) != null
      }
      .mapNotNull(::renderObservation)

  private fun collectWorkingStateActions(
    messages: List<RuntimeConversationMessage>,
  ): List<RenderedWorkingStateAction> = messages.mapNotNull(::parseToolResult)
    .filter { parsed ->
      parsed.status.equals("success", ignoreCase = true) &&
        parsed.metadata["duplicateGuard"] != "true"
    }
    .mapNotNull { renderWorkingStateAction(it) }

  private fun collectDecisionEntries(
    messages: List<RuntimeConversationMessage>,
  ): List<RenderedWorkingStateAction> = messages.mapNotNull(::parseReplayControlEvent)
    .mapNotNull { renderDecisionEntry(it) }

  private fun collectBlockerEntries(
    messages: List<RuntimeConversationMessage>,
  ): List<RenderedWorkingStateAction> {
    val rendered = mutableListOf<RenderedWorkingStateAction>()
    messages.forEach { message ->
      parseToolResult(message)
        ?.let { renderToolResultBlocker(it) }
        ?.let(rendered::add)
      parseReplayControlEvent(message)
        ?.let { renderReplayBlocker(it) }
        ?.let(rendered::add)
    }
    return rendered
  }

  private fun activeTaskMessages(messages: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> {
    val lastUserIndex = messages.indexOfLast { message -> message.role == RuntimeConversationRole.USER }
    return if (lastUserIndex >= 0) {
      messages.drop(lastUserIndex + 1)
    } else {
      messages
    }
  }

  internal fun renderObservation(parsed: ParsedToolResult): RenderedObservation? = when (canonicalToolName(parsed.toolName)) {
    "Read" -> renderReadObservation(parsed)
    "LS" -> renderListObservation(parsed)
    "Grep" -> renderGrepObservation(parsed)
    "Glob" -> renderGlobObservation(parsed)
    "search_workspace_document" -> renderWorkspaceDocumentSearchObservation(parsed)
    "inspect_workspace_package" -> renderWorkspacePackageInspectObservation(parsed)
    "Task" -> renderTaskObservation(parsed)
    "ScheduledTaskCreate" -> renderScheduledTaskCreateObservation(parsed)
    "ScheduledTaskList" -> renderScheduledTaskListObservation(parsed)
    "ScheduledTaskGet" -> renderScheduledTaskGetObservation(parsed)
    "ScheduledTaskUpdate" -> renderScheduledTaskUpdateObservation(parsed)
    "ScheduledTaskDelete" -> renderScheduledTaskDeleteObservation(parsed)
    "SkillsFind" -> renderSkillsFindObservation(parsed)
    "SkillsList" -> renderSkillsListObservation(parsed)
    "SkillsInspect" -> renderSkillsInspectObservation(parsed)
    "SkillsCheck" -> renderSkillsCheckObservation(parsed)
    else -> null
  }

  private fun parseToolResult(message: RuntimeConversationMessage): ParsedToolResult? {
    if (message.role != RuntimeConversationRole.TOOL) {
      return null
    }
    if (message.commentaryJsonPayloadOrNull() != null) {
      return null
    }
    val decoded = message.toolResultJsonPayloadOrNull()
      ?.let(::replayJsonObjectOrNull)
      ?: return null
    val toolName = decoded.stringValue("tool_name")
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: message.toolResult?.toolName
      ?: return null
    val content = decoded.stringValue("content")
      ?: message.toolResultContentOrNull()
      ?: message.content
    val status = decoded.stringValue("status")
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: message.toolResult?.status
      ?: ""
    val metadata = decoded["metadata"]
      ?.let { element -> element as? JsonObject }
      ?.entries
      ?.mapNotNull { (key, value) ->
        val primitive = value as? JsonPrimitive ?: return@mapNotNull null
        key to primitive.content
      }
      ?.toMap()
      .orEmpty()
    return ParsedToolResult(
      toolName = canonicalToolName(toolName),
      status = status,
      content = content,
      metadata = metadata,
      errorCode = decoded.stringValue("error_code")
        ?.trim()
        ?.takeIf(String::isNotBlank),
      errorMessage = decoded.stringValue("error_message")
        ?.trim()
        ?.takeIf(String::isNotBlank),
    )
  }

  private fun parseReplayControlEvent(
    message: RuntimeConversationMessage,
  ): ParsedReplayControlEvent? {
    if (message.role != RuntimeConversationRole.TOOL || message.kind != RuntimeConversationMessageKind.PLAIN) {
      return null
    }
    val normalized = message.content.trim()
    if (normalized.isBlank()) {
      return null
    }
    val type = normalized.substringBefore(' ').trim()
    if (
      type != "approval_rejected" &&
      type != "approval_approved" &&
      type != "run_interrupted" &&
      type != "retry_abandoned"
    ) {
      return null
    }
    val fields = REPLAY_FIELD_REGEX
      .findAll(normalized)
      .associate { match ->
        match.groupValues[1] to match.groupValues[2]
      }
    return ParsedReplayControlEvent(
      type = type,
      fields = fields,
    )
  }

  private fun renderLayer(
    items: List<RecentToolObservationItem>,
    omittedObservationCount: Int,
    detailMode: RecentToolObservationDetailMode,
  ): RecentToolObservationLayer {
    val selectedItems = selectItemsForDetailMode(items, detailMode)
    val effectiveOmittedCount = (omittedObservationCount + (items.size - selectedItems.size)).coerceAtLeast(0)
    val lines = mutableListOf<String>()
    when (detailMode) {
      RecentToolObservationDetailMode.FULL -> {
        lines += "Recent replay-independent task observations are available below."
        lines += "Reuse them before repeating the same delegated investigation, scheduling inspection, or skills lookup."
        lines += "Workspace discovery tools such as Read or LS stay in conversation replay; use this layer only for control-plane context that replay does not keep front-loaded."
      }

      RecentToolObservationDetailMode.COMPACT -> {
        lines += "Recent replay-independent task observations are available below."
        lines += "Reuse them before repeating the same delegation, scheduling, or skills-discovery call."
      }

      RecentToolObservationDetailMode.MINIMAL -> {
        lines += "Latest replay-independent task observations:"
      }
    }
    selectedItems.forEach { item ->
      lines += ""
      lines += item.summaryLine
      when (detailMode) {
        RecentToolObservationDetailMode.FULL -> lines += item.body
        RecentToolObservationDetailMode.COMPACT -> lines += compactBody(item.body)
        RecentToolObservationDetailMode.MINIMAL -> Unit
      }
    }
    if (detailMode == RecentToolObservationDetailMode.FULL && effectiveOmittedCount > 0) {
      lines += ""
      lines += "Omitted $effectiveOmittedCount older unique task observation(s) from this working set."
    }
    return RecentToolObservationLayer(
      text = boundRenderedText(lines.joinToString(separator = "\n").trim()),
      items = selectedItems,
      omittedObservationCount = effectiveOmittedCount,
    )
  }

  private fun selectItemsForDetailMode(
    items: List<RecentToolObservationItem>,
    detailMode: RecentToolObservationDetailMode,
  ): List<RecentToolObservationItem> {
    if (items.isEmpty()) {
      return emptyList()
    }
    val maxItems = when (detailMode) {
      RecentToolObservationDetailMode.FULL -> items.size
      RecentToolObservationDetailMode.COMPACT -> minOf(items.size, config.maxCompactEntries)
      RecentToolObservationDetailMode.MINIMAL -> minOf(items.size, config.maxMinimalEntries)
    }
    return items.takeLast(maxItems)
  }

  internal fun canonicalToolName(toolName: String): String = when (toolName.trim().lowercase()) {
    "read" -> "Read"
    "ls", "list" -> "LS"
    "grep" -> "Grep"
    "glob" -> "Glob"
    "searchworkspacedocument" -> "search_workspace_document"
    "inspectworkspacepackage" -> "inspect_workspace_package"
    "extractworkspacepackage" -> "extract_workspace_package"
    "viewworkspacedocument" -> "view_workspace_document"
    "task" -> "Task"
    "scheduledtaskcreate", "scheduled_task_create" -> "ScheduledTaskCreate"
    "scheduledtasklist", "scheduled_task_list" -> "ScheduledTaskList"
    "scheduledtaskget", "scheduled_task_get" -> "ScheduledTaskGet"
    "scheduledtaskupdate", "scheduled_task_update" -> "ScheduledTaskUpdate"
    "scheduledtaskdelete", "scheduled_task_delete" -> "ScheduledTaskDelete"
    "skillsfind", "skills_find" -> "SkillsFind"
    "skillslist", "skills_list" -> "SkillsList"
    "skillsinspect", "skills_inspect" -> "SkillsInspect"
    "skillscheck", "skills_check" -> "SkillsCheck"
    "skillsadd", "skills_add" -> "SkillsAdd"
    "skillsaddbatch", "skills_add_batch" -> "SkillsAddBatch"
    "skillsupdate", "skills_update" -> "SkillsUpdate"
    "skillsremove", "skills_remove" -> "SkillsRemove"
    "generateimage", "imagegenerate" -> "GenerateImage"
    "synthesizespeech", "texttospeech", "tts" -> "SynthesizeSpeech"
    else -> toolName.trim()
  }

  private fun categoryForToolName(toolName: String): ObservationCategory? = when (canonicalToolName(toolName)) {
    "Read",
    "LS",
    "Grep",
    "Glob",
    "search_workspace_document",
    "inspect_workspace_package",
    "Task",
    "ScheduledTaskList",
    "ScheduledTaskGet",
    "SkillsFind",
    "SkillsList",
    "SkillsInspect",
    "SkillsCheck",
    -> if (canonicalToolName(toolName) == "Task") {
      ObservationCategory.DELEGATION
    } else {
      ObservationCategory.DISCOVERY
    }

    "Write",
    "Edit",
    "MultiEdit",
    "ImportFile",
    "import_chat_attachment",
    "extract_workspace_package",
    "view_workspace_document",
    "view_workspace_image",
    "view_workspace_pdf",
    "workspace_write_file",
    "workspace_move_file",
    "workspace_delete_file",
    "GenerateImage",
    "SynthesizeSpeech",
    "SkillsAdd",
    "SkillsAddBatch",
    "SkillsUpdate",
    "SkillsRemove",
    "Bash",
    "command_exec",
    "python_exec",
    "ProcessStart",
    "ProcessList",
    "ProcessRead",
    "ProcessWait",
    "ProcessTerminate",
    "TodoWrite",
    "ScheduledTaskCreate",
    "ScheduledTaskUpdate",
    "ScheduledTaskDelete",
    -> ObservationCategory.BARRIER

    else -> null
  }

  // Keep replay-clean discovery tools in conversation history, closer to Codex's ResponseItem model.
  // Codex-style assembly keeps ordinary tool traces in replay/history instead of duplicating them into
  // a separate front-loaded observation block. The front observation layer stays empty until a future
  // category is explicitly promoted as replay-independent runtime state.
  internal fun frontContextObservationCategoryForToolName(toolName: String): ObservationCategory? = null

  internal fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

  internal fun JsonObject.stringValueFrom(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> stringValue(key) }

  internal fun JsonObject.objectValue(key: String): JsonObject? =
    this[key] as? JsonObject

  internal fun JsonObject.scheduledTaskTriggerKind(): String? = when {
    stringValue("at") != null -> "at"
    stringValue("after") != null -> "after"
    stringValueFrom("start_at", "startAt") != null || stringValue("rrule") != null -> "rrule"
    else -> null
  }

  internal fun JsonObject.intValue(key: String): Int? =
    stringValue(key)?.toIntOrNull()

  internal fun JsonObject.intArrayCsvValue(key: String): String? {
    val array = this[key] as? JsonArray ?: return null
    val values = array.mapNotNull { entry ->
      (entry as? JsonPrimitive)?.content?.toIntOrNull()?.toString()
    }
    return values.takeIf(List<String>::isNotEmpty)?.joinToString(separator = ",")
  }

  internal fun JsonObject.stringArrayCsvValue(key: String): String? {
    val array = this[key] as? JsonArray ?: return null
    val values = array.mapNotNull { entry ->
      (entry as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotBlank)
    }
    return values
      .distinct()
      .sorted()
      .takeIf(List<String>::isNotEmpty)
      ?.joinToString(separator = ",")
  }

  internal enum class ObservationCategory {
    DISCOVERY,
    DELEGATION,
    BARRIER,
  }

  private companion object {
    const val DEFAULT_WORKING_STATE_ENTRIES: Int = 8
    const val DEFAULT_DECISION_ENTRIES: Int = 4
    const val DEFAULT_BLOCKER_ENTRIES: Int = 3
    val REPLAY_FIELD_REGEX: Regex = Regex("""([A-Za-z0-9_]+)=([^\s]+)""")
  }
}

internal data class ParsedToolResult(
  val toolName: String,
  val status: String,
  val content: String,
  val metadata: Map<String, String>,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  fun resultTruncated(): Boolean =
    metadata["resultTruncated"]?.toBooleanStrictOrNull()
      ?: metadata["truncated"]?.toBooleanStrictOrNull()
      ?: false

  fun resultLimitKind(): String? =
    metadata["resultLimitKind"]?.trim()?.takeIf(String::isNotBlank)
}
