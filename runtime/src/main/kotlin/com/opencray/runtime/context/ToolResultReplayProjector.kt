package com.opencray.runtime.context

import com.opencray.llm.LiteLlmGatewayToolResult
import com.opencray.runtime.OpenCraySerializableGatewayToolResult
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class FrozenToolResultReplayProjection(
  val projectionKey: String,
  val sourceDigest: String,
  val toolCallId: String? = null,
  val toolName: String,
  val reasons: List<String> = emptyList(),
  val originalContentChars: Int,
  val originalStdoutChars: Int,
  val originalStderrChars: Int,
  val originalStructuredChars: Int,
  val originalTotalChars: Int,
  val projectedToolResult: OpenCraySerializableGatewayToolResult,
) {
  init {
    require(projectionKey.isNotBlank()) {
      "FrozenToolResultReplayProjection projectionKey must not be blank."
    }
    require(sourceDigest.isNotBlank()) {
      "FrozenToolResultReplayProjection sourceDigest must not be blank."
    }
    require(toolName.isNotBlank()) {
      "FrozenToolResultReplayProjection toolName must not be blank."
    }
    require(originalContentChars >= 0) {
      "FrozenToolResultReplayProjection originalContentChars must be >= 0."
    }
    require(originalStdoutChars >= 0) {
      "FrozenToolResultReplayProjection originalStdoutChars must be >= 0."
    }
    require(originalStderrChars >= 0) {
      "FrozenToolResultReplayProjection originalStderrChars must be >= 0."
    }
    require(originalStructuredChars >= 0) {
      "FrozenToolResultReplayProjection originalStructuredChars must be >= 0."
    }
    require(originalTotalChars > 0) {
      "FrozenToolResultReplayProjection originalTotalChars must be > 0."
    }
  }

  fun restoredToolResult(): LiteLlmGatewayToolResult = projectedToolResult.toGatewayToolResult()
}

data class ToolResultReplayProjectorConfig(
  val minAttachmentProjectionChars: Int = 1_024,
  val minStdStreamProjectionChars: Int = 6_000,
  val minStructuredProjectionChars: Int = 6_000,
  val maxInlineSectionChars: Int = 320,
  val previewHeadChars: Int = 600,
  val previewTailChars: Int = 400,
  val maxProjectedContentChars: Int = 4_000,
  val attachmentLikeLineChars: Int = 192,
  val maxRetainedStdStreamChars: Int = 512,
  val maxRetainedStructuredChars: Int = 768,
) {
  init {
    require(minAttachmentProjectionChars >= 256) {
      "ToolResultReplayProjectorConfig minAttachmentProjectionChars must be >= 256."
    }
    require(minStdStreamProjectionChars >= 1_024) {
      "ToolResultReplayProjectorConfig minStdStreamProjectionChars must be >= 1024."
    }
    require(minStructuredProjectionChars >= 1_024) {
      "ToolResultReplayProjectorConfig minStructuredProjectionChars must be >= 1024."
    }
    require(maxInlineSectionChars >= 64) {
      "ToolResultReplayProjectorConfig maxInlineSectionChars must be >= 64."
    }
    require(previewHeadChars >= 128) {
      "ToolResultReplayProjectorConfig previewHeadChars must be >= 128."
    }
    require(previewTailChars >= 64) {
      "ToolResultReplayProjectorConfig previewTailChars must be >= 64."
    }
    require(maxProjectedContentChars >= 1_024) {
      "ToolResultReplayProjectorConfig maxProjectedContentChars must be >= 1024."
    }
    require(attachmentLikeLineChars >= 64) {
      "ToolResultReplayProjectorConfig attachmentLikeLineChars must be >= 64."
    }
    require(maxRetainedStdStreamChars >= 128) {
      "ToolResultReplayProjectorConfig maxRetainedStdStreamChars must be >= 128."
    }
    require(maxRetainedStructuredChars >= 128) {
      "ToolResultReplayProjectorConfig maxRetainedStructuredChars must be >= 128."
    }
  }
}

class ToolResultReplayProjector(
  private val config: ToolResultReplayProjectorConfig = ToolResultReplayProjectorConfig(),
) {
  fun projectionKey(
    entry: RuntimeConversationMessage,
    toolResult: LiteLlmGatewayToolResult,
  ): String {
    val digest = sourceDigest(
      entry = entry,
      toolResult = toolResult,
    )
    val keySeed = toolResult.toolCallId
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: entry.toolResult?.toolCallId
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: toolResult.toolName
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: entry.toolResult?.toolName
        ?.trim()
        ?.takeIf(String::isNotBlank)
      ?: "tool"
    return "tool-result-${normalizeKeySeed(keySeed)}-${digest.take(24)}"
  }

  fun maybeProject(
    entry: RuntimeConversationMessage,
    toolResult: LiteLlmGatewayToolResult,
  ): FrozenToolResultReplayProjection? {
    val content = toolResult.content
    val stdout = toolResult.stdout?.takeIf(String::isNotBlank)
    val stderr = toolResult.stderr?.takeIf(String::isNotBlank)
    val structured = toolResult.structuredContent?.toString()?.takeIf(String::isNotBlank)
    val attachmentLike = content.length >= config.minAttachmentProjectionChars &&
      isAttachmentLikePayload(content)
    if (toolResult.isError == true && !attachmentLike) {
      return null
    }
    val largeStdout = stdout != null && stdout.length >= config.minStdStreamProjectionChars
    val largeStderr = stderr != null && stderr.length >= config.minStdStreamProjectionChars
    val largeStructured = structured != null && structured.length >= config.minStructuredProjectionChars
    if (!attachmentLike && !largeStdout && !largeStderr && !largeStructured) {
      return null
    }

    val originalContentChars = content.length
    val originalStdoutChars = stdout?.length ?: 0
    val originalStderrChars = stderr?.length ?: 0
    val originalStructuredChars = structured?.length ?: 0
    val originalTotalChars = payloadChars(toolResult)
    val reasons = buildList {
      if (attachmentLike) {
        add("attachment_like_content")
      }
      if (largeStdout) {
        add("large_stdout")
      }
      if (largeStderr) {
        add("large_stderr")
      }
      if (largeStructured) {
        add("large_structured_content")
      }
    }
    val projected = LiteLlmGatewayToolResult(
      toolCallId = toolResult.toolCallId,
      toolName = toolResult.toolName,
      content = buildProjectedContent(
        toolName = toolResult.toolName ?: entry.toolResult?.toolName ?: "tool",
        reasons = reasons,
        content = content,
        stdout = stdout,
        stderr = stderr,
        structured = structured,
        originalContentChars = originalContentChars,
        originalStdoutChars = originalStdoutChars,
        originalStderrChars = originalStderrChars,
        originalStructuredChars = originalStructuredChars,
        originalTotalChars = originalTotalChars,
        attachmentLike = attachmentLike,
        largeStdout = largeStdout,
        largeStderr = largeStderr,
        largeStructured = largeStructured,
      ),
      structuredContent = toolResult.structuredContent
        ?.takeUnless { largeStructured && originalStructuredChars > config.maxRetainedStructuredChars },
      isError = toolResult.isError,
      exitCode = toolResult.exitCode,
      stdout = stdout?.takeUnless { largeStdout && originalStdoutChars > config.maxRetainedStdStreamChars },
      stderr = stderr?.takeUnless { largeStderr && originalStderrChars > config.maxRetainedStdStreamChars },
      errorCode = toolResult.errorCode,
      errorMessage = toolResult.errorMessage,
      metadata = toolResult.metadata,
    )
    if (payloadChars(projected) >= originalTotalChars) {
      return null
    }
    return FrozenToolResultReplayProjection(
      projectionKey = projectionKey(entry, toolResult),
      sourceDigest = sourceDigest(entry, toolResult),
      toolCallId = toolResult.toolCallId,
      toolName = toolResult.toolName ?: entry.toolResult?.toolName ?: "tool",
      reasons = reasons,
      originalContentChars = originalContentChars,
      originalStdoutChars = originalStdoutChars,
      originalStderrChars = originalStderrChars,
      originalStructuredChars = originalStructuredChars,
      originalTotalChars = originalTotalChars,
      projectedToolResult = OpenCraySerializableGatewayToolResult.from(projected),
    )
  }

  private fun buildProjectedContent(
    toolName: String,
    reasons: List<String>,
    content: String,
    stdout: String?,
    stderr: String?,
    structured: String?,
    originalContentChars: Int,
    originalStdoutChars: Int,
    originalStderrChars: Int,
    originalStructuredChars: Int,
    originalTotalChars: Int,
    attachmentLike: Boolean,
    largeStdout: Boolean,
    largeStderr: Boolean,
    largeStructured: Boolean,
  ): String {
    val rendered = buildString {
      appendLine("[frozen replay preview]")
      appendLine("version=v1")
      appendLine("tool_name=$toolName")
      appendLine("projection_reasons=${reasons.joinToString(separator = ",")}")
      appendLine("original_total_chars=$originalTotalChars")
      appendLine("original_content_chars=$originalContentChars")
      if (originalStdoutChars > 0) {
        appendLine("original_stdout_chars=$originalStdoutChars")
      }
      if (originalStderrChars > 0) {
        appendLine("original_stderr_chars=$originalStderrChars")
      }
      if (originalStructuredChars > 0) {
        appendLine("original_structured_chars=$originalStructuredChars")
      }
      appendLine("canonical transcript keeps the full raw tool result; replay stays on this stable preview.")
      appendLine()
      appendSection(
        builder = this,
        label = if (attachmentLike) "content (attachment-like excerpt)" else "content",
        value = content,
        preview = attachmentLike,
      )
      if (largeStdout) {
        appendLine()
        appendSection(
          builder = this,
          label = "stdout",
          value = stdout.orEmpty(),
          preview = true,
        )
      }
      if (largeStderr) {
        appendLine()
        appendSection(
          builder = this,
          label = "stderr",
          value = stderr.orEmpty(),
          preview = true,
        )
      }
      if (largeStructured) {
        appendLine()
        appendSection(
          builder = this,
          label = "structured_content",
          value = structured.orEmpty(),
          preview = true,
        )
      }
    }.trim()
    return if (rendered.length <= config.maxProjectedContentChars) {
      rendered
    } else {
      rendered
        .take(config.maxProjectedContentChars - PROJECTED_CONTENT_TRUNCATION_SUFFIX.length)
        .trimEnd() + PROJECTED_CONTENT_TRUNCATION_SUFFIX
    }
  }

  private fun appendSection(
    builder: StringBuilder,
    label: String,
    value: String,
    preview: Boolean,
  ) {
    val normalized = value.trim()
    if (normalized.isBlank()) {
      return
    }
    builder.append(label)
    builder.appendLine(":")
    if (!preview && normalized.length <= config.maxInlineSectionChars) {
      builder.append(normalized)
      return
    }
    if (normalized.length <= config.previewHeadChars + config.previewTailChars + SECTION_GAP_MARKER.length + 8) {
      builder.append(normalized)
      return
    }
    builder.append(normalized.take(config.previewHeadChars).trimEnd())
    builder.appendLine()
    builder.appendLine(SECTION_GAP_MARKER)
    builder.append(normalized.takeLast(config.previewTailChars).trimStart())
  }

  private fun payloadChars(toolResult: LiteLlmGatewayToolResult): Int =
    toolResult.content.length +
      (toolResult.stdout?.length ?: 0) +
      (toolResult.stderr?.length ?: 0) +
      (toolResult.structuredContent?.toString()?.length ?: 0)

  private fun sourceDigest(
    entry: RuntimeConversationMessage,
    toolResult: LiteLlmGatewayToolResult,
  ): String {
    val digestSource = buildString {
      append(entry.role.name)
      append('|')
      append(entry.kind.name)
      append('|')
      append(toolResult.toolCallId.orEmpty())
      append('|')
      append(toolResult.toolName.orEmpty())
      append('|')
      append(toolResult.isError?.toString().orEmpty())
      append('|')
      append(toolResult.exitCode?.toString().orEmpty())
      append('|')
      append(toolResult.errorCode.orEmpty())
      append('|')
      append(toolResult.errorMessage.orEmpty())
      append('|')
      append(toolResult.content)
      append('|')
      append(toolResult.stdout.orEmpty())
      append('|')
      append(toolResult.stderr.orEmpty())
      append('|')
      append(toolResult.structuredContent?.toString().orEmpty())
      append('|')
      toolResult.metadata.toSortedMap().forEach { (key, value) ->
        append(key)
        append('=')
        append(value)
        append('\n')
      }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(digestSource.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun normalizeKeySeed(raw: String): String {
    val normalized = raw.lowercase(Locale.US)
      .replace(Regex("[^a-z0-9]+"), "_")
      .trim('_')
    return normalized.take(32).ifBlank { "tool" }
  }

  private fun isAttachmentLikePayload(content: String): Boolean {
    val normalized = content.trim()
    if (normalized.startsWith("data:") || normalized.contains(";base64,")) {
      return true
    }
    return normalized.lineSequence().any { line ->
      val candidate = line.trim()
      candidate.length >= config.attachmentLikeLineChars &&
        candidate.all { character -> character.isLetterOrDigit() || character in ATTACHMENT_LIKE_CHARS }
    }
  }

  private companion object {
    const val ATTACHMENT_LIKE_CHARS: String = "+/=_-:,.;"
    const val SECTION_GAP_MARKER: String = "[...]"
    const val PROJECTED_CONTENT_TRUNCATION_SUFFIX: String = "\n[projection_truncated_for_replay_budget]"
  }
}
