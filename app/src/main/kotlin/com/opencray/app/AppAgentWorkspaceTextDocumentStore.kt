package com.opencray.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal object AppAgentWorkspaceTextDocumentStore {
  fun loadDocument(
    workspaceRoot: Path,
    relativePath: String,
  ): Map<String, Any?> {
    val target = resolveSupportedTextFile(
      workspaceRoot = workspaceRoot,
      relativePath = relativePath,
    )
    val normalizedRelativePath = relativePath.trim().replace('\\', '/').removePrefix("/")
    return mapOf(
      "name" to target.name,
      "relativePath" to normalizedRelativePath,
      "content" to readText(target),
    )
  }

  fun createFile(
    workspaceRoot: Path,
    parentRelativePath: String,
    name: String,
  ) {
    val normalizedName = validateSupportedTextFileName(name)
    val parentDirectory = resolvePath(
      workspaceRoot = workspaceRoot,
      relativePath = parentRelativePath,
      allowRoot = true,
    )
    require(Files.isDirectory(parentDirectory)) {
      "Destination directory does not exist: ${parentDirectory.fileName ?: parentDirectory}"
    }
    val destination = parentDirectory.resolve(normalizedName).normalize()
    require(!destination.exists()) {
      "An item named '$normalizedName' already exists."
    }
    Files.write(
      destination,
      ByteArray(0),
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE,
    )
  }

  fun saveDocument(
    workspaceRoot: Path,
    targetRelativePath: String,
    content: String,
  ) {
    val target = resolveSupportedTextFile(
      workspaceRoot = workspaceRoot,
      relativePath = targetRelativePath,
    )
    Files.write(
      target,
      content.toByteArray(StandardCharsets.UTF_8),
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE,
    )
  }

  fun resolveSupportedTextFile(
    workspaceRoot: Path,
    relativePath: String,
  ): Path {
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
    require(isSupportedTextFileName(target.name)) {
      "Text editing is available for text files only."
    }
    return target
  }

  fun isSupportedTextFileName(name: String): Boolean {
    val normalizedName = name.trim().lowercase(Locale.US)
    if (normalizedName in supportedFileNames) {
      return true
    }
    val extension = normalizedName.substringAfterLast('.', "")
    return extension in supportedExtensions
  }

  fun validateSupportedTextFileName(rawName: String): String {
    val normalizedName = validateName(rawName)
    require(isSupportedTextFileName(normalizedName)) {
      "Only supported text files can be created here."
    }
    return normalizedName
  }

  fun resolvePath(
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

  fun validateName(rawName: String): String {
    val normalizedName = rawName.trim()
    require(normalizedName.isNotEmpty()) {
      "A name is required."
    }
    require(normalizedName != "." && normalizedName != "..") {
      "That name is not allowed."
    }
    require('/' !in normalizedName && '\\' !in normalizedName) {
      "Names cannot contain path separators."
    }
    return normalizedName
  }

  private fun readText(path: Path): String =
    try {
      val bytes = Files.readAllBytes(path)
      utf8Decoder().decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
      throw IllegalStateException("This file can't be edited as text.")
    }

  private fun utf8Decoder() =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)

  val supportedFileNames: Set<String> = setOf(
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

  val supportedExtensions: Set<String> = setOf(
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
