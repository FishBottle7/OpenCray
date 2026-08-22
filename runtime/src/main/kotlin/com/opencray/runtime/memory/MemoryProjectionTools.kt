package com.opencray.runtime.memory

import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.inlinePreview
import com.opencray.runtime.optionalInt
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import com.opencray.runtime.requiredString
import kotlinx.serialization.json.JsonObject

internal fun OpenCrayToolDispatcher.searchProjectedMemory(arguments: JsonObject): AgentToolResult {
    val context = config.memoryToolContext
      ?: return AgentToolResult(
        toolName = "memory_search",
        status = AgentToolResultStatus.FAILED,
        content = "Projected memory search is not configured for this runtime.",
        errorCode = "MEMORY_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxMemorySearchResults).coerceIn(1, config.maxMemorySearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = memorySearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = if (response.matches.isEmpty()) {
      "No matching projected memory snippets were found."
    } else {
      buildString {
        appendLine("Found ${response.matches.size} projected memory match(es).")
        response.matches.forEachIndexed { index, match ->
          append(index + 1)
          append(". ")
          append(renderMemorySearchHeader(match))
          appendLine()
          appendLine(match.snippet)
          if (index != response.matches.lastIndex) {
            appendLine()
          }
        }
      }.trim()
    }
    return AgentToolResult(
      toolName = "memory_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicySupport.commonMetadata(
        toolName = "memory_search",
        metadataContext = policyMetadataContext(
          toolName = "memory_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.recordId },
          )
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match -> renderMemoryLineRange(match.startLine, match.endLine) },
          )
        }
      },
    )
  }

internal fun OpenCrayToolDispatcher.getProjectedMemory(arguments: JsonObject): AgentToolResult {
    val context = config.memoryToolContext
      ?: return AgentToolResult(
        toolName = "memory_get",
        status = AgentToolResultStatus.FAILED,
        content = "Projected memory reads are not configured for this runtime.",
        errorCode = "MEMORY_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxMemoryGetLines)
      .coerceIn(1, config.maxMemoryGetLines)
    val response = memorySearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "memory_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderMemoryLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = toolPolicySupport.commonMetadata(
        toolName = "memory_get",
        metadataContext = policyMetadataContext(
          toolName = "memory_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.recordIds.joinToString(separator = ","),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.renderMemorySearchHeader(match: MemorySearchMatch): String = buildString {
    append(match.path)
    append("#")
    append(renderMemoryLineRange(match.startLine, match.endLine))
    append(" score=")
    append(match.score)
    append(" id=")
    append(match.recordId)
    append(" kind=")
    append(match.kind.name.lowercase())
    append(" scope=")
    append(match.scope.name.lowercase())
    append(" status=")
    append(match.status.name.lowercase())
    if (match.matchedTerms.isNotEmpty()) {
      append(" matched_terms=")
      append(match.matchedTerms.joinToString(separator = "|"))
    }
  }

internal fun OpenCrayToolDispatcher.renderMemoryLineRange(
    startLine: Int,
    endLine: Int,
  ): String = if (startLine == endLine) {
    "L$startLine"
  } else {
    "L$startLine-L$endLine"
  }
