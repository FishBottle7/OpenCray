package com.opencray.runtime.memory

import com.opencray.runtime.AgentToolCall
import com.opencray.runtime.AgentToolResult

internal enum class MemoryToolOperation {
  SEARCH,
  GET,
}

internal data class MemoryToolTrace(
  val operation: MemoryToolOperation,
  val toolName: String,
  val query: String? = null,
  val queryTerms: List<String> = emptyList(),
  val resultCount: Int? = null,
  val corpusFileCount: Int? = null,
  val recordIds: List<String> = emptyList(),
  val paths: List<String> = emptyList(),
  val lineRanges: List<String> = emptyList(),
  val path: String? = null,
  val fromLine: Int? = null,
  val returnedLineCount: Int? = null,
  val totalLineCount: Int? = null,
)

internal fun memoryToolTraceFrom(
  call: AgentToolCall,
  result: AgentToolResult,
): MemoryToolTrace? = when (call.toolName) {
  "memory_search" -> MemoryToolTrace(
    operation = MemoryToolOperation.SEARCH,
    toolName = call.toolName,
    query = result.metadata["query"]?.takeIf(String::isNotBlank),
    queryTerms = splitCsvMetadata(result.metadata["queryTerms"]),
    resultCount = result.metadata["resultCount"]?.toIntOrNull(),
    corpusFileCount = result.metadata["corpusFileCount"]?.toIntOrNull(),
    recordIds = splitCsvMetadata(result.metadata["recordIds"]),
    paths = splitCsvMetadata(result.metadata["paths"]),
    lineRanges = splitCsvMetadata(result.metadata["lineRanges"]),
  ).takeIf { trace ->
    trace.query != null ||
      trace.queryTerms.isNotEmpty() ||
      trace.resultCount != null ||
      trace.corpusFileCount != null ||
      trace.recordIds.isNotEmpty() ||
      trace.paths.isNotEmpty()
  }

  "memory_get" -> MemoryToolTrace(
    operation = MemoryToolOperation.GET,
    toolName = call.toolName,
    recordIds = splitCsvMetadata(result.metadata["recordIds"]),
    path = result.metadata["path"]?.takeIf(String::isNotBlank),
    fromLine = result.metadata["from"]?.toIntOrNull(),
    returnedLineCount = result.metadata["returnedLineCount"]?.toIntOrNull(),
    totalLineCount = result.metadata["totalLineCount"]?.toIntOrNull(),
  ).takeIf { trace ->
    trace.recordIds.isNotEmpty() ||
    trace.path != null ||
      trace.fromLine != null ||
      trace.returnedLineCount != null ||
      trace.totalLineCount != null
  }

  else -> null
}

private fun splitCsvMetadata(raw: String?): List<String> = raw
  .orEmpty()
  .split(',')
  .map(String::trim)
  .filter(String::isNotBlank)
