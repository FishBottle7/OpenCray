package com.opencray.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal object AppAgentWorkspaceTextPreviewer {
  fun loadPreview(
    workspaceRoot: Path,
    relativePath: String,
  ): Map<String, Any?> {
    val target = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
      allowRoot = false,
    )
    require(target.exists()) {
      "The selected file no longer exists."
    }
    require(!target.isDirectory()) {
      "Folders can't be previewed here."
    }
    require(isPreviewableName(target.name)) {
      "Text preview is available for text files only."
    }

    val normalizedRelativePath = relativePath.trim().replace('\\', '/').removePrefix("/")
    return try {
      InputStreamReader(Files.newInputStream(target), utf8Decoder()).buffered().use { reader ->
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

  private fun isPreviewableName(name: String): Boolean {
    val normalizedName = name.trim().lowercase(Locale.US)
    if (normalizedName in PREVIEWABLE_FILE_NAMES) {
      return true
    }
    val extension = normalizedName.substringAfterLast('.', "")
    return extension in PREVIEWABLE_EXTENSIONS
  }

  private fun resolvePath(
    workspaceRoot: Path,
    relativePath: String,
    allowRoot: Boolean,
  ): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    if (trimmed.isEmpty()) {
      require(allowRoot) { "Workspace root cannot be targeted here." }
      return normalizedRoot
    }
    val resolved = normalizedRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(normalizedRoot)) {
      "Path escapes the workspace root."
    }
    return resolved
  }

  private data class TextPreviewContent(
    val text: String,
    val isTruncated: Boolean,
  )

  private const val MAX_PREVIEW_CHARS: Int = 24_000
  private const val BUFFER_SIZE: Int = 2_048

  private val PREVIEWABLE_FILE_NAMES = setOf(
    ".env",
    ".gitignore",
    ".gitattributes",
    "makefile",
    "readme",
    "readme.md",
    "license",
    "gradlew",
    "gradlew.bat",
  )

  private val PREVIEWABLE_EXTENSIONS = setOf(
    "txt",
    "md",
    "markdown",
    "json",
    "yaml",
    "yml",
    "xml",
    "csv",
    "log",
    "ini",
    "conf",
    "config",
    "properties",
    "toml",
    "dart",
    "kt",
    "kts",
    "java",
    "js",
    "ts",
    "tsx",
    "jsx",
    "css",
    "scss",
    "html",
    "htm",
    "sh",
    "bash",
    "zsh",
    "py",
    "sql",
  )
}
