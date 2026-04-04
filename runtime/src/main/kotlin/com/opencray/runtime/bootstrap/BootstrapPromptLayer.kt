package com.opencray.runtime.bootstrap

data class BootstrapPromptLayerConfig(
  val maxCompactChars: Int = 480,
  val maxMinimalChars: Int = 160,
) {
  init {
    require(maxCompactChars >= 128) { "BootstrapPromptLayerConfig maxCompactChars must be >= 128." }
    require(maxMinimalChars >= 64) { "BootstrapPromptLayerConfig maxMinimalChars must be >= 64." }
    require(maxMinimalChars <= maxCompactChars) {
      "BootstrapPromptLayerConfig maxMinimalChars must be <= maxCompactChars."
    }
  }
}

enum class BootstrapPromptDetailMode {
  FULL,
  COMPACT,
  MINIMAL,
}

data class RenderedBootstrapSnippet(
  val layerName: String,
  val text: String,
)

class BootstrapPromptLayer(
  private val config: BootstrapPromptLayerConfig = BootstrapPromptLayerConfig(),
) {
  fun render(
    snippet: BootstrapSnippet,
    detailMode: BootstrapPromptDetailMode = BootstrapPromptDetailMode.FULL,
  ): RenderedBootstrapSnippet {
    val boundedContent = when (detailMode) {
      BootstrapPromptDetailMode.FULL -> snippet.content
      BootstrapPromptDetailMode.COMPACT -> boundContent(snippet.content, config.maxCompactChars)
      BootstrapPromptDetailMode.MINIMAL -> boundContent(snippet.content, config.maxMinimalChars)
    }
    val promptTruncated = boundedContent != snippet.content
    return RenderedBootstrapSnippet(
      layerName = layerName(snippet),
      text = buildString {
        appendLine("source_file=${snippet.relativePath}")
        appendLine("truncated=${snippet.truncated}")
        if (snippet.sourceCharCount != snippet.content.length || promptTruncated) {
          appendLine("source_chars=${snippet.sourceCharCount}")
        }
        if (promptTruncated) {
          appendLine("prompt_truncated=true")
        }
        appendLine()
        append(boundedContent)
      }.trim(),
    )
  }

  fun layerName(snippet: BootstrapSnippet): String = "Bootstrap ${snippet.relativePath}"

  private fun boundContent(
    content: String,
    maxChars: Int,
  ): String {
    if (content.length <= maxChars) {
      return content
    }
    return content.take(maxChars - 1).trimEnd() + "…"
  }
}
