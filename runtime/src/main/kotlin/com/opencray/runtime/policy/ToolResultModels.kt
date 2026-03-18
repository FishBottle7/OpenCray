package com.opencray.runtime.policy

internal enum class ToolResultLimitKind(val wireValue: String) {
  READ_BYTE_BUDGET("read_byte_budget"),
  DIRECTORY_ENTRY_LIMIT("directory_entry_limit"),
  SEARCH_MATCH_LIMIT("search_match_limit"),
  WEB_FETCH_CHAR_LIMIT("web_fetch_char_limit"),
  WEB_SEARCH_RESULT_LIMIT("web_search_result_limit"),
  COMMAND_OUTPUT_BYTE_LIMIT("command_output_byte_limit"),
  PROCESS_OUTPUT_BYTE_LIMIT("process_output_byte_limit"),
}

internal data class ToolResultEnvelope(
  val limitApplied: Boolean = false,
  val truncated: Boolean = false,
  val limitKind: ToolResultLimitKind? = null,
) {
  fun metadata(): Map<String, String> {
    if (!limitApplied && !truncated && limitKind == null) {
      return emptyMap()
    }
    val resolvedLimitApplied = limitApplied || limitKind != null
    return buildMap {
      put("resultLimitApplied", resolvedLimitApplied.toString())
      put("resultTruncated", truncated.toString())
      limitKind?.let { put("resultLimitKind", it.wireValue) }
    }
  }
}
