package com.opencray.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.name

internal object AppAgentWorkspaceTextPreviewer {
  fun loadPreview(
    workspaceRoot: Path,
    relativePath: String,
  ): Map<String, Any?> {
    val target = AppAgentWorkspaceTextDocumentStore.resolveSupportedTextFile(
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
    )

    val normalizedRelativePath = relativePath.trim().replace('\\', '/').removePrefix("/")
    return try {
      InputStreamReader(java.nio.file.Files.newInputStream(target), utf8Decoder()).buffered().use { reader ->
        val preview = readPreviewText(reader)
        mapOf(
          "name" to target.name,
          "relativePath" to normalizedRelativePath,
          "content" to preview.text,
          "isTruncated" to preview.isTruncated,
        )
      }
    } catch (_: CharacterCodingException) {
      throw IllegalStateException("This file can't be previewed as text.")
    }
  }

  private fun readPreviewText(reader: BufferedReader): TextPreviewContent {
    val builder = StringBuilder()
    val buffer = CharArray(BUFFER_SIZE)
    while (builder.length < MAX_PREVIEW_CHARS) {
      val remaining = MAX_PREVIEW_CHARS - builder.length
      val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
      if (read < 0) {
        return TextPreviewContent(text = builder.toString(), isTruncated = false)
      }
      builder.append(buffer, 0, read)
    }
    val isTruncated = reader.read() != -1
    return TextPreviewContent(text = builder.toString(), isTruncated = isTruncated)
  }

  private fun utf8Decoder() =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)

  private data class TextPreviewContent(
    val text: String,
    val isTruncated: Boolean,
  )

  private const val MAX_PREVIEW_CHARS: Int = 24_000
  private const val BUFFER_SIZE: Int = 2_048
}
