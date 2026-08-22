package com.opencray.runtime.session

import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.optionalInt
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import com.opencray.runtime.requiredString
import kotlinx.serialization.json.JsonObject

internal fun OpenCrayToolDispatcher.searchProjectedSessionHistory(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "session_search",
        status = AgentToolResultStatus.FAILED,
        content = "Projected prior-session history search is not configured for this runtime.",
        errorCode = "SESSION_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxSessionSearchResults).coerceIn(1, config.maxSessionSearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = sessionSearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = if (response.matches.isEmpty()) {
      "No matching projected prior-session snippets were found."
    } else {
      buildString {
        appendLine("Found ${response.matches.size} projected session match(es).")
        response.matches.forEachIndexed { index, match ->
          append(index + 1)
          append(". ")
          append(renderSessionSearchHeader(match))
          appendLine()
          appendLine(match.snippet)
          if (index != response.matches.lastIndex) {
            appendLine()
          }
        }
      }.trim()
    }
    return AgentToolResult(
      toolName = "session_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicySupport.commonMetadata(
        toolName = "session_search",
        metadataContext = policyMetadataContext(
          toolName = "session_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("surface", "session_history")
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "sessionIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match ->
              renderSessionLineRange(match.startLine, match.endLine)
            },
          )
        }
      },
    )
  }

internal fun OpenCrayToolDispatcher.getProjectedSessionHistory(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "session_get",
        status = AgentToolResultStatus.FAILED,
        content = "Projected prior-session history reads are not configured for this runtime.",
        errorCode = "SESSION_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxSessionGetLines)
      .coerceIn(1, config.maxSessionGetLines)
    val response = sessionSearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "session_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderSessionLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = toolPolicySupport.commonMetadata(
        toolName = "session_get",
        metadataContext = policyMetadataContext(
          toolName = "session_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "surface" to "session_history",
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.sessionIds.joinToString(separator = ","),
        "sessionIds" to response.sessionIds.joinToString(separator = ","),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.searchPastSessionArchive(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "past_session_search",
        status = AgentToolResultStatus.FAILED,
        content = "Past-session archive search is not configured for this runtime.",
        errorCode = "PAST_SESSION_SEARCH_UNAVAILABLE",
      )
    val query = arguments.requiredString("query")
    val maxResults = (arguments.optionalInt("max_results") ?: arguments.optionalInt("maxResults")
      ?: config.maxSessionSearchResults).coerceIn(1, config.maxSessionSearchResults)
    val minScore = (arguments.optionalInt("min_score") ?: arguments.optionalInt("minScore")
      ?: 1).coerceAtLeast(1)
    val response = sessionSearchService.search(
      context = context,
      query = query,
      maxResults = maxResults,
      minScore = minScore,
    )
    val content = renderPastSessionSearchContent(response.matches)
    return AgentToolResult(
      toolName = "past_session_search",
      status = AgentToolResultStatus.SUCCESS,
      content = content,
      metadata = toolPolicySupport.commonMetadata(
        toolName = "past_session_search",
        metadataContext = policyMetadataContext(
          toolName = "past_session_search",
          workspaceRelation = ToolWorkspaceRelation.NONE,
          targetSummary = inlinePreview(query, maxChars = 256),
        ),
      ) + buildMap {
        put("query", query)
        put("surface", "session_archive")
        put("queryTerms", response.queryTerms.joinToString(separator = ","))
        put("resultCount", response.matches.size.toString())
        put("corpusFileCount", response.corpusFileCount.toString())
        if (response.matches.isNotEmpty()) {
          put(
            "recordIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "sessionIds",
            response.matches.joinToString(separator = ",") { match -> match.sessionId },
          )
          put(
            "paths",
            response.matches.joinToString(separator = ",") { match -> match.path },
          )
          put(
            "lineRanges",
            response.matches.joinToString(separator = ",") { match ->
              renderSessionLineRange(match.startLine, match.endLine)
            },
          )
        }
      },
    )
  }

internal fun OpenCrayToolDispatcher.getPastSessionArchive(arguments: JsonObject): AgentToolResult {
    val context = config.sessionSearchToolContext
      ?: return AgentToolResult(
        toolName = "past_session_get",
        status = AgentToolResultStatus.FAILED,
        content = "Past-session archive reads are not configured for this runtime.",
        errorCode = "PAST_SESSION_GET_UNAVAILABLE",
      )
    val path = arguments.requiredString("path")
    val from = arguments.optionalInt("from")?.coerceAtLeast(1)
    val lines = (arguments.optionalInt("lines") ?: config.maxSessionGetLines)
      .coerceIn(1, config.maxSessionGetLines)
    val response = sessionSearchService.get(
      context = context,
      path = path,
      from = from,
      lines = lines,
    )
    return AgentToolResult(
      toolName = "past_session_get",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        append(response.path)
        append("#")
        append(renderSessionLineRange(response.startLine, response.endLine))
        appendLine()
        append(response.text)
      }.trim(),
      metadata = toolPolicySupport.commonMetadata(
        toolName = "past_session_get",
        metadataContext = policyMetadataContext(
          toolName = "past_session_get",
          targetKind = ToolTargetKind.FILE,
          workspaceRelation = ToolWorkspaceRelation.NONE,
          primaryTargetPath = response.path,
          targetSummary = response.path,
        ),
      ) + mapOf(
        "surface" to "session_archive",
        "path" to response.path,
        "from" to response.startLine.toString(),
        "returnedLineCount" to (response.endLine - response.startLine + 1).toString(),
        "totalLineCount" to response.totalLineCount.toString(),
        "recordIds" to response.sessionIds.joinToString(separator = ","),
        "sessionIds" to response.sessionIds.joinToString(separator = ","),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.renderSessionSearchHeader(match: SessionSearchMatch): String = buildString {
    append(match.path)
    append("#")
    append(renderSessionLineRange(match.startLine, match.endLine))
    append(" score=")
    append(match.score)
    append(" session_id=")
    append(match.sessionId)
    match.title
      ?.takeIf(String::isNotBlank)
      ?.let { title ->
        append(" title=")
        append(title)
      }
    if (match.matchedTerms.isNotEmpty()) {
      append(" matched_terms=")
      append(match.matchedTerms.joinToString(separator = "|"))
    }
  }

internal fun OpenCrayToolDispatcher.renderPastSessionSearchContent(matches: List<SessionSearchMatch>): String {
    if (matches.isEmpty()) {
      return "No matching past-session archive snippets were found."
    }
    return buildString {
      appendLine("Found ${matches.size} past-session archive match(es).")
      matches.forEachIndexed { index, match ->
        append(index + 1)
        append(". session_id=")
        append(match.sessionId)
        match.title
          ?.takeIf(String::isNotBlank)
          ?.let { title ->
            append(" title=")
            append(title)
          }
        append(" score=")
        append(match.score)
        appendLine()
        append("summary=")
        appendLine(match.snippet)
        append("reference=")
        append(match.path)
        append("#")
        append(renderSessionLineRange(match.startLine, match.endLine))
        if (match.matchedTerms.isNotEmpty()) {
          append(" matched_terms=")
          append(match.matchedTerms.joinToString(separator = "|"))
        }
        if (index != matches.lastIndex) {
          appendLine()
          appendLine()
        }
      }
    }.trim()
  }

internal fun OpenCrayToolDispatcher.renderSessionLineRange(
    startLine: Int,
    endLine: Int,
  ): String = if (startLine == endLine) {
    "L$startLine"
  } else {
    "L$startLine-L$endLine"
  }
