package com.opencray.runtime.memory

import java.util.Locale

class MemoryPromptLayer(
  private val config: MemoryPromptLayerConfig = MemoryPromptLayerConfig(),
) {
  fun render(
    result: MemoryRecallResult,
    detailMode: MemoryPromptDetailMode = MemoryPromptDetailMode.FULL,
  ): String {
    val memories = selectMemories(
      memories = result.memories,
      detailMode = detailMode,
    )
    if (memories.isEmpty()) {
      return ""
    }
    val renderConfig = renderConfig(detailMode)
    return buildString {
      appendLine("Use recalled durable context when it remains relevant and does not conflict with newer user instructions.")
      appendLine()
      memories.forEach { memory ->
        appendLine(
          "- kind=${memory.kind.renderToken()} scope=${memory.scope.renderToken()} content=${memory.content}",
        )
      }
      if (renderConfig.includeRecallBudgetNotice && result.omittedRecordCount > 0) {
        appendLine()
        append("Omitted ${result.omittedRecordCount} additional memory record(s) due to recall budget.")
      }
    }.trim()
  }

  private fun renderConfig(
    detailMode: MemoryPromptDetailMode,
  ): MemoryPromptRenderConfig = when (detailMode) {
    MemoryPromptDetailMode.FULL -> MemoryPromptRenderConfig(
      maxMemories = Int.MAX_VALUE,
      includeRecallBudgetNotice = true,
    )

    MemoryPromptDetailMode.COMPACT -> MemoryPromptRenderConfig(
      maxMemories = config.maxCompactMemories,
      includeRecallBudgetNotice = false,
    )

    MemoryPromptDetailMode.MINIMAL -> MemoryPromptRenderConfig(
      maxMemories = config.maxMinimalMemories,
      includeRecallBudgetNotice = false,
    )
  }

  private fun selectMemories(
    memories: List<RetrievedMemory>,
    detailMode: MemoryPromptDetailMode,
  ): List<RetrievedMemory> {
    if (memories.isEmpty()) {
      return emptyList()
    }
    val maxMemories = renderConfig(detailMode).maxMemories
    return memories.take(maxMemories)
  }

  private fun MemoryKind.renderToken(): String = name.lowercase(Locale.US)

  private fun MemoryScope.renderToken(): String = name.lowercase(Locale.US)
}

data class MemoryPromptLayerConfig(
  val maxCompactMemories: Int = 2,
  val maxMinimalMemories: Int = 1,
) {
  init {
    require(maxCompactMemories >= 1) {
      "MemoryPromptLayerConfig maxCompactMemories must be >= 1."
    }
    require(maxMinimalMemories >= 1) {
      "MemoryPromptLayerConfig maxMinimalMemories must be >= 1."
    }
    require(maxCompactMemories >= maxMinimalMemories) {
      "MemoryPromptLayerConfig maxCompactMemories must be >= maxMinimalMemories."
    }
  }
}

enum class MemoryPromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

private data class MemoryPromptRenderConfig(
  val maxMemories: Int,
  val includeRecallBudgetNotice: Boolean,
)
