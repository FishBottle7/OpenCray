package com.opencray.runtime.context

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.subagent.SubAgentResultMetadataKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class RecentToolObservationConfig(
  val maxEntries: Int = 4,
  val maxReadChars: Int = 2_400,
  val maxReadLines: Int = 96,
  val maxListChars: Int = 1_600,
  val maxListLines: Int = 32,
  val maxRenderedChars: Int = 7_200,
  val duplicateLookbackMessages: Int = 24,
  val duplicateExcerptChars: Int = 1_200,
) {
  init {
    require(maxEntries >= 1) { "RecentToolObservationConfig maxEntries must be >= 1." }
    require(maxReadChars >= 256) { "RecentToolObservationConfig maxReadChars must be >= 256." }
    require(maxReadLines >= 8) { "RecentToolObservationConfig maxReadLines must be >= 8." }
    require(maxListChars >= 128) { "RecentToolObservationConfig maxListChars must be >= 128." }
    require(maxListLines >= 4) { "RecentToolObservationConfig maxListLines must be >= 4." }
    require(maxRenderedChars >= 512) { "RecentToolObservationConfig maxRenderedChars must be >= 512." }
    require(duplicateLookbackMessages >= 1) {
      "RecentToolObservationConfig duplicateLookbackMessages must be >= 1."
    }
    require(duplicateExcerptChars >= 128) {
      "RecentToolObservationConfig duplicateExcerptChars must be >= 128."
    }
  }
}

data class RecentToolObservationLayer(
  val text: String,
  val observationCount: Int,
  val omittedObservationCount: Int = 0,
) {
  init {
    require(text.isNotBlank()) { "RecentToolObservationLayer text must not be blank." }
    require(observationCount >= 1) {
      "RecentToolObservationLayer observationCount must be >= 1."
    }
    require(omittedObservationCount >= 0) {
      "RecentToolObservationLayer omittedObservationCount must be >= 0."
    }
  }
}

data class DuplicateDiscoveryToolHit(
  val toolName: String,
  val signature: String,
  val summaryLine: String,
  val excerpt: String,
  val metadata: Map<String, String> = emptyMap(),
)

class RecentToolObservationSupport(
  private val config: RecentToolObservationConfig = RecentToolObservationConfig(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  fun summaryLines(messages: List<RuntimeConversationMessage>): List<String> =
    selectObservations(messages).map { observation ->
      observation.summaryLine.removePrefix("- ").trim()
    }

  fun buildLayer(messages: List<RuntimeConversationMessage>): RecentToolObservationLayer? {
    val observations = collectObservations(activeTaskMessages(messages))
    val selected = selectObservations(messages)
    if (selected.isEmpty()) {
      return null
    }

    val lines = mutableListOf<String>()
    lines += "Recent successful workspace and delegation observations from the current task are available below."
    lines += "Reuse them before repeating identical workspace discovery, skills lookup, or delegated investigation calls."
    lines += "If you still need more detail, narrow the next discovery call with offset, limit, path, pattern, glob, query, or source."
    selected.forEach { observation ->
      lines += ""
      lines += observation.summaryLine
      lines += observation.body
    }
    val totalUniqueCount = observations.map(RenderedObservation::signature).distinct().size
    val omittedObservationCount = (totalUniqueCount - selected.size).coerceAtLeast(0)
    if (omittedObservationCount > 0) {
      lines += ""
      lines += "Omitted $omittedObservationCount older unique workspace observation(s) from this working set."
    }

    return RecentToolObservationLayer(
      text = boundRenderedText(lines.joinToString(separator = "\n").trim()),
      observationCount = selected.size,
      omittedObservationCount = omittedObservationCount,
    )
  }

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
          categoryForToolName(parsed.toolName).let { category ->
            category == ObservationCategory.DISCOVERY ||
              category == ObservationCategory.DELEGATION
          }
      }
      .mapNotNull(::renderObservation)

  private fun activeTaskMessages(messages: List<RuntimeConversationMessage>): List<RuntimeConversationMessage> {
    val lastUserIndex = messages.indexOfLast { message -> message.role == RuntimeConversationRole.USER }
    return if (lastUserIndex >= 0) {
      messages.drop(lastUserIndex + 1)
    } else {
      messages
    }
  }

  private fun renderObservation(parsed: ParsedToolResult): RenderedObservation? = when (canonicalToolName(parsed.toolName)) {
    "Read" -> renderReadObservation(parsed)
    "LS" -> renderListObservation(parsed)
    "Grep" -> renderGrepObservation(parsed)
    "Glob" -> renderGlobObservation(parsed)
    "search_workspace_document" -> renderWorkspaceDocumentSearchObservation(parsed)
    "Task" -> renderTaskObservation(parsed)
    "SkillsFind" -> renderSkillsFindObservation(parsed)
    "SkillsList" -> renderSkillsListObservation(parsed)
    "SkillsInspect" -> renderSkillsInspectObservation(parsed)
    "SkillsCheck" -> renderSkillsCheckObservation(parsed)
    else -> null
  }

  private fun renderReadObservation(parsed: ParsedToolResult): RenderedObservation? {
    val filePath = parsed.metadata["filePath"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val offset = parsed.metadata["offset"]?.toIntOrNull() ?: 1
    val limit = parsed.metadata["limit"]?.toIntOrNull()
    val returnedLineCount = parsed.metadata["returnedLineCount"]?.toIntOrNull()
    val totalLineCount = parsed.metadata["totalLineCount"]?.toIntOrNull()
    val truncated = parsed.resultTruncated()
    val limitKind = parsed.resultLimitKind()
    val lineSummary = buildReadLineSummary(
      offset = offset,
      returnedLineCount = returnedLineCount,
      totalLineCount = totalLineCount,
    )
    val detailParts = mutableListOf(lineSummary)
    detailParts += "limit=${limit?.toString() ?: "all"}"
    detailParts += "truncated=$truncated"
    limitKind?.let { detailParts += "limit_kind=$it" }
    val body = boundMultiline(
      text = parsed.content,
      maxChars = config.maxReadChars,
      maxLines = config.maxReadLines,
    )
    return RenderedObservation(
      signature = buildReadSignature(
        filePath = filePath,
        offset = offset,
        limit = limit,
      ),
      summaryLine = "- Read file_path=$filePath ${detailParts.joinToString(separator = " ")}",
      body = body,
    )
  }

  private fun renderListObservation(parsed: ParsedToolResult): RenderedObservation? {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val entryCount = parsed.metadata["entryCount"]?.toIntOrNull()
    val detailParts = mutableListOf("path=$path", "entries=${entryCount?.toString() ?: "unknown"}")
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildListSignature(path = path),
      summaryLine = "- LS ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderGrepObservation(parsed: ParsedToolResult): RenderedObservation? {
    val pattern = parsed.metadata["pattern"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val glob = parsed.metadata["glob"]?.trim().takeUnless { it.isNullOrBlank() }
    val matchCount = parsed.metadata["matchCount"]?.toIntOrNull()
    val detailParts = mutableListOf<String>()
    detailParts += "pattern=$pattern"
    detailParts += "path=$path"
    glob?.let { detailParts += "glob=$it" }
    detailParts += "matches=${matchCount?.toString() ?: "unknown"}"
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildGrepSignature(
        pattern = pattern,
        path = path,
        glob = glob,
      ),
      summaryLine = "- Grep ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderGlobObservation(parsed: ParsedToolResult): RenderedObservation? {
    val pattern = parsed.metadata["pattern"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: "."
    val matchCount = parsed.metadata["matchCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "pattern=$pattern",
      "path=$path",
      "matches=${matchCount?.toString() ?: "unknown"}",
    )
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildGlobSignature(
        pattern = pattern,
        path = path,
      ),
      summaryLine = "- Glob ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderWorkspaceDocumentSearchObservation(parsed: ParsedToolResult): RenderedObservation? {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val query = parsed.metadata["query"]?.trim().takeUnless { it.isNullOrBlank() }
    val hitCount = parsed.metadata["hitCount"]?.toIntOrNull()
    val pageCount = parsed.metadata["pageCount"]?.toIntOrNull()
    val requestedPages = parsed.metadata["requestedPages"]?.trim().takeUnless { it.isNullOrBlank() }
    val pageFrom = parsed.metadata["pageFrom"]?.trim().takeUnless { it.isNullOrBlank() }
    val pageTo = parsed.metadata["pageTo"]?.trim().takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf(
      "path=$path",
      "query=${query ?: "<preview>"}",
      "hits=${hitCount?.toString() ?: "unknown"}",
    )
    pageCount?.let { detailParts += "page_count=$it" }
    requestedPages?.let { detailParts += "requested_pages=$it" }
    if (pageFrom != null || pageTo != null) {
      detailParts += "requested_range=${pageFrom ?: 1}-${pageTo ?: "end"}"
    }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildWorkspaceDocumentSearchSignature(
        path = path,
        query = query,
        requestedPages = requestedPages,
        pageFrom = pageFrom,
        pageTo = pageTo,
      ),
      summaryLine = "- search_workspace_document ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderTaskObservation(parsed: ParsedToolResult): RenderedObservation {
    val description = parsed.metadata["delegationDescription"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: "Task"
    val subagentType = parsed.metadata["delegationSubagentType"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["subagentType"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: "general-purpose"
    val contextMode = parsed.metadata["delegationContextMode"]?.trim().takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["subagentContextMode"]?.trim().takeUnless { it.isNullOrBlank() }
    val executionState = parsed.metadata[SubAgentResultMetadataKeys.EXECUTION_STATE]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.metadata["childExecutionStatus"]?.trim()?.lowercase()
      ?: "completed"
    val childTurnCount = parsed.metadata["childTurnCount"]?.trim().takeUnless { it.isNullOrBlank() }
    val childToolCallCount = parsed.metadata["childToolCallCount"]?.trim().takeUnless { it.isNullOrBlank() }
    val headline = parsed.metadata[SubAgentResultMetadataKeys.SUMMARY_HEADLINE]
      ?.trim()
      .takeUnless { it.isNullOrBlank() }
      ?: parsed.content
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?: "Delegated child result."
    val details = parsed.metadata[SubAgentResultMetadataKeys.SUMMARY_DETAILS]
      ?.lines()
      ?.map(String::trim)
      ?.filter(String::isNotBlank)
      .orEmpty()
    val detailParts = mutableListOf(
      "description=$description",
      "subagent=$subagentType",
      "state=$executionState",
    )
    contextMode?.let { detailParts += "context=$it" }
    childTurnCount?.let { detailParts += "turns=$it" }
    childToolCallCount?.let { detailParts += "tool_calls=$it" }
    val bodyLines = buildList {
      add("Summary: $headline")
      details.forEach { line ->
        add("Detail: $line")
      }
    }
    return RenderedObservation(
      signature = buildTaskSignature(
        description = description,
        subagentType = subagentType,
        headline = headline,
      ),
      summaryLine = "- Task ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = bodyLines.joinToString(separator = "\n"),
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderSkillsFindObservation(parsed: ParsedToolResult): RenderedObservation {
    val query = parsed.metadata["query"]?.trim().orEmpty()
    val resultCount = parsed.metadata["resultCount"]?.toIntOrNull()
    val remoteResultCount = parsed.metadata["remoteResultCount"]?.toIntOrNull()
    val localResultCount = parsed.metadata["localResultCount"]?.toIntOrNull()
    val providerName = parsed.metadata["providerName"]?.trim().takeUnless { it.isNullOrBlank() }
    val detailParts = mutableListOf(
      "query=${query.ifBlank { "<catalog>" }}",
      "results=${resultCount?.toString() ?: "unknown"}",
    )
    providerName?.let { detailParts += "provider=$it" }
    remoteResultCount?.let { detailParts += "remote=$it" }
    localResultCount?.let { detailParts += "local=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsFindSignature(query = query),
      summaryLine = "- SkillsFind ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderSkillsListObservation(parsed: ParsedToolResult): RenderedObservation {
    val path = parsed.metadata["path"]?.trim().takeUnless { it.isNullOrBlank() }
    val skillCount = parsed.metadata["skillCount"]?.toIntOrNull()
    val detailParts = mutableListOf("skills=${skillCount?.toString() ?: "unknown"}")
    path?.let { detailParts += "path=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsListSignature(),
      summaryLine = "- SkillsList ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderSkillsInspectObservation(parsed: ParsedToolResult): RenderedObservation? {
    val sourceRef = parsed.metadata["sourceRef"]?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val sourceType = parsed.metadata["sourceType"]?.trim().takeUnless { it.isNullOrBlank() }
    val candidateCount = parsed.metadata["candidateCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "source_ref=$sourceRef",
      "candidates=${candidateCount?.toString() ?: "unknown"}",
    )
    sourceType?.let { detailParts += "source_type=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsInspectSignature(sourceRef = sourceRef),
      summaryLine = "- SkillsInspect ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
  }

  private fun renderSkillsCheckObservation(parsed: ParsedToolResult): RenderedObservation {
    val skillId = parsed.metadata["skillId"]?.trim().takeUnless { it.isNullOrBlank() }
    val checkedCount = parsed.metadata["checkedCount"]?.toIntOrNull()
    val updateAvailableCount = parsed.metadata["updateAvailableCount"]?.toIntOrNull()
    val upToDateCount = parsed.metadata["upToDateCount"]?.toIntOrNull()
    val sourceUnavailableCount = parsed.metadata["sourceUnavailableCount"]?.toIntOrNull()
    val unsupportedCount = parsed.metadata["unsupportedCount"]?.toIntOrNull()
    val detailParts = mutableListOf(
      "skill=${skillId ?: "<all>"}",
      "checked=${checkedCount?.toString() ?: "unknown"}",
    )
    updateAvailableCount?.let { detailParts += "updates=$it" }
    upToDateCount?.let { detailParts += "up_to_date=$it" }
    sourceUnavailableCount?.let { detailParts += "source_unavailable=$it" }
    unsupportedCount?.let { detailParts += "unsupported=$it" }
    if (parsed.resultTruncated()) {
      detailParts += "truncated=true"
    }
    parsed.resultLimitKind()?.let { detailParts += "limit_kind=$it" }
    return RenderedObservation(
      signature = buildSkillsCheckSignature(skillId = skillId),
      summaryLine = "- SkillsCheck ${detailParts.joinToString(separator = " ")}",
      body = boundMultiline(
        text = parsed.content,
        maxChars = config.maxListChars,
        maxLines = config.maxListLines,
      ),
    )
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
    )
  }

  private fun signatureForCall(call: AgentToolCall): String? {
    val arguments = call.arguments
    return when (canonicalToolName(call.toolName)) {
      "Read" -> buildReadSignature(
        filePath = arguments.stringValueFrom("file_path", "path") ?: return null,
        offset = arguments.intValue("offset") ?: 1,
        limit = arguments.intValue("limit"),
      )

      "LS" -> buildListSignature(
        path = arguments.stringValue("path") ?: ".",
      )

      "Grep" -> buildGrepSignature(
        pattern = arguments.stringValue("pattern") ?: return null,
        path = arguments.stringValue("path") ?: ".",
        glob = arguments.stringValue("glob"),
      )

      "Glob" -> buildGlobSignature(
        pattern = arguments.stringValue("pattern") ?: return null,
        path = arguments.stringValue("path") ?: ".",
      )

      "search_workspace_document" -> buildWorkspaceDocumentSearchSignature(
        path = arguments.stringValue("path") ?: return null,
        query = arguments.stringValue("query"),
        requestedPages = arguments.intArrayCsvValue("pages"),
        pageFrom = arguments.stringValue("page_from"),
        pageTo = arguments.stringValue("page_to"),
      )

      "SkillsFind" -> buildSkillsFindSignature(
        query = arguments.stringValue("query").orEmpty(),
      )

      "SkillsList" -> buildSkillsListSignature()

      "SkillsInspect" -> buildSkillsInspectSignature(
        sourceRef = arguments.stringValueFrom("source_ref", "sourceRef", "source", "path", "url") ?: return null,
      )

      "SkillsCheck" -> buildSkillsCheckSignature(
        skillId = arguments.stringValueFrom("skill_id", "skillId", "name"),
      )

      else -> null
    }
  }

  private fun buildReadLineSummary(
    offset: Int,
    returnedLineCount: Int?,
    totalLineCount: Int?,
  ): String {
    val lineRange = when {
      returnedLineCount == null -> "lines=$offset+"
      returnedLineCount <= 0 -> "lines=empty@$offset"
      else -> "lines=$offset-${offset + returnedLineCount - 1}"
    }
    return if (totalLineCount != null) {
      "$lineRange/$totalLineCount"
    } else {
      lineRange
    }
  }

  private fun buildReadSignature(
    filePath: String,
    offset: Int,
    limit: Int?,
  ): String = listOf(
    "Read",
    normalizePathValue(filePath),
    offset.toString(),
    limit?.toString().orEmpty(),
  ).joinToString(separator = "|")

  private fun buildListSignature(path: String): String = listOf(
    "LS",
    normalizePathValue(path),
  ).joinToString(separator = "|")

  private fun buildGrepSignature(
    pattern: String,
    path: String,
    glob: String?,
  ): String = listOf(
    "Grep",
    pattern.trim(),
    normalizePathValue(path),
    glob?.trim().orEmpty(),
  ).joinToString(separator = "|")

  private fun buildGlobSignature(
    pattern: String,
    path: String,
  ): String = listOf(
    "Glob",
    pattern.trim(),
    normalizePathValue(path),
  ).joinToString(separator = "|")

  private fun buildWorkspaceDocumentSearchSignature(
    path: String,
    query: String?,
    requestedPages: String?,
    pageFrom: String?,
    pageTo: String?,
  ): String = listOf(
    "search_workspace_document",
    normalizePathValue(path),
    query?.trim().orEmpty(),
    requestedPages?.trim().orEmpty(),
    pageFrom?.trim().orEmpty(),
    pageTo?.trim().orEmpty(),
  ).joinToString(separator = "|")

  private fun buildSkillsFindSignature(query: String): String = listOf(
    "SkillsFind",
    query.trim(),
  ).joinToString(separator = "|")

  private fun buildSkillsListSignature(): String = "SkillsList"

  private fun buildSkillsInspectSignature(sourceRef: String): String = listOf(
    "SkillsInspect",
    sourceRef.trim(),
  ).joinToString(separator = "|")

  private fun buildSkillsCheckSignature(skillId: String?): String = listOf(
    "SkillsCheck",
    skillId?.trim().orEmpty(),
  ).joinToString(separator = "|")

  private fun buildTaskSignature(
    description: String,
    subagentType: String,
    headline: String,
  ): String = listOf(
    "Task",
    description.trim(),
    subagentType.trim(),
    headline.trim(),
  ).joinToString(separator = "|")

  private fun normalizePathValue(path: String): String = path.trim().ifBlank { "." }.replace('\\', '/')

  private fun boundMultiline(
    text: String,
    maxChars: Int,
    maxLines: Int,
  ): String {
    val normalized = text.trim().ifBlank { "<empty>" }
    val lines = normalized.lineSequence().toList()
    val keptLines = if (lines.size <= maxLines) lines else lines.take(maxLines)
    val joined = keptLines.joinToString(separator = "\n")
    val boundedChars = if (joined.length <= maxChars) joined else joined.take(maxChars).trimEnd() + "…"
    val truncated = keptLines.size < lines.size || boundedChars.length < normalized.length
    return if (truncated) {
      "$boundedChars\n[truncated for recent observation working set]"
    } else {
      boundedChars
    }
  }

  private fun boundRenderedText(text: String): String =
    if (text.length <= config.maxRenderedChars) {
      text
    } else {
      text.take(config.maxRenderedChars).trimEnd() + "\n[recent observation layer truncated]"
    }

  private fun boundExcerpt(text: String): String =
    if (text.length <= config.duplicateExcerptChars) {
      text
    } else {
      text.take(config.duplicateExcerptChars).trimEnd() + "\n[excerpt truncated]"
    }

  private fun canonicalToolName(toolName: String): String = when (toolName.trim().lowercase()) {
    "read" -> "Read"
    "ls", "list" -> "LS"
    "grep" -> "Grep"
    "glob" -> "Glob"
    "searchworkspacedocument" -> "search_workspace_document"
    "viewworkspacedocument" -> "view_workspace_document"
    "task" -> "Task"
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
    "Task",
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
    -> ObservationCategory.BARRIER

    else -> null
  }

  private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

  private fun JsonObject.stringValueFrom(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> stringValue(key) }

  private fun JsonObject.intValue(key: String): Int? =
    stringValue(key)?.toIntOrNull()

  private fun JsonObject.intArrayCsvValue(key: String): String? {
    val array = this[key] as? JsonArray ?: return null
    val values = array.mapNotNull { entry ->
      (entry as? JsonPrimitive)?.content?.toIntOrNull()?.toString()
    }
    return values.takeIf(List<String>::isNotEmpty)?.joinToString(separator = ",")
  }

  private data class ParsedToolResult(
    val toolName: String,
    val status: String,
    val content: String,
    val metadata: Map<String, String>,
  ) {
    fun resultTruncated(): Boolean =
      metadata["resultTruncated"]?.toBooleanStrictOrNull()
        ?: metadata["truncated"]?.toBooleanStrictOrNull()
        ?: false

    fun resultLimitKind(): String? =
      metadata["resultLimitKind"]?.trim()?.takeIf(String::isNotBlank)
  }

  private data class RenderedObservation(
    val signature: String,
    val summaryLine: String,
    val body: String,
  )

  private enum class ObservationCategory {
    DISCOVERY,
    DELEGATION,
    BARRIER,
  }
}
