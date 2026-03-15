package com.opencray.runtime.memory

import java.util.Locale

class MemoryPromptLayer {
  fun render(result: MemoryRecallResult): String {
    if (result.memories.isEmpty()) {
      return ""
    }
    return buildString {
      appendLine("Use recalled durable context when it remains relevant and does not conflict with newer user instructions.")
      appendLine()
      result.memories.forEach { memory ->
        appendLine(
          "- kind=${memory.kind.renderToken()} scope=${memory.scope.renderToken()} content=${memory.content}",
        )
      }
      if (result.omittedRecordCount > 0) {
        appendLine()
        append("Omitted ${result.omittedRecordCount} additional memory record(s) due to recall budget.")
      }
    }.trim()
  }

  private fun MemoryKind.renderToken(): String = name.lowercase(Locale.US)

  private fun MemoryScope.renderToken(): String = name.lowercase(Locale.US)
}
