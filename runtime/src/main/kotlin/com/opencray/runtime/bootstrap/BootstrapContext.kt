package com.opencray.runtime.bootstrap

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

enum class BootstrapMode(val wireValue: String) {
  FULL("full"),
  LIGHTWEIGHT("lightweight"),
  NONE("none");

  companion object {
    fun fromWireValue(raw: String?): BootstrapMode? = entries.firstOrNull { mode ->
      mode.wireValue.equals(raw?.trim(), ignoreCase = true)
    }
  }
}

enum class BootstrapFileKind(
  val fileName: String,
) {
  AGENTS("AGENTS.md"),
  SOUL("SOUL.md"),
  TOOLS("TOOLS.md"),
  PROJECT("PROJECT.md"),
  ;

  fun isEnabledFor(mode: BootstrapMode): Boolean = when (mode) {
    BootstrapMode.FULL -> true
    // Lightweight mode keeps workspace guidance while avoiding overlap with soul/tool layers.
    BootstrapMode.LIGHTWEIGHT -> this == AGENTS || this == PROJECT
    BootstrapMode.NONE -> false
  }
}

data class BootstrapSnippet(
  val name: String,
  val relativePath: String,
  val content: String,
  val sourceCharCount: Int,
  val truncated: Boolean,
) {
  init {
    require(name.isNotBlank()) { "BootstrapSnippet name must not be blank." }
    require(relativePath.isNotBlank()) { "BootstrapSnippet relativePath must not be blank." }
    require(content.isNotBlank()) { "BootstrapSnippet content must not be blank." }
    require(sourceCharCount >= content.length) {
      "BootstrapSnippet sourceCharCount must be >= injected content length."
    }
  }
}

data class BootstrapFileTrace(
  val name: String,
  val relativePath: String,
  val sourceCharCount: Int,
  val injectedCharCount: Int,
  val truncated: Boolean,
) {
  init {
    require(name.isNotBlank()) { "BootstrapFileTrace name must not be blank." }
    require(relativePath.isNotBlank()) { "BootstrapFileTrace relativePath must not be blank." }
    require(sourceCharCount >= 0) { "BootstrapFileTrace sourceCharCount must be >= 0." }
    require(injectedCharCount >= 0) { "BootstrapFileTrace injectedCharCount must be >= 0." }
  }
}

data class BootstrapTrace(
  val mode: String = BootstrapMode.NONE.wireValue,
  val visibleFileCount: Int = 0,
  val injectedFileCount: Int = 0,
  val omittedFileCount: Int = 0,
  val truncatedFileCount: Int = 0,
  val files: List<BootstrapFileTrace> = emptyList(),
) {
  init {
    require(visibleFileCount >= 0) { "BootstrapTrace visibleFileCount must be >= 0." }
    require(injectedFileCount >= 0) { "BootstrapTrace injectedFileCount must be >= 0." }
    require(omittedFileCount >= 0) { "BootstrapTrace omittedFileCount must be >= 0." }
    require(truncatedFileCount >= 0) { "BootstrapTrace truncatedFileCount must be >= 0." }
  }

  val isEmpty: Boolean
    get() = visibleFileCount == 0 &&
      injectedFileCount == 0 &&
      omittedFileCount == 0 &&
      truncatedFileCount == 0 &&
      files.isEmpty() &&
      mode == BootstrapMode.NONE.wireValue
}

data class BootstrapContext(
  val mode: BootstrapMode = BootstrapMode.NONE,
  val files: List<BootstrapSnippet> = emptyList(),
  val trace: BootstrapTrace = BootstrapTrace(),
) {
  val injectedFileCount: Int
    get() = files.size

  val isEmpty: Boolean
    get() = mode == BootstrapMode.NONE || (files.isEmpty() && trace.visibleFileCount == 0)
}

data class BootstrapContextResolverConfig(
  val maxCharsPerFile: Int = 1_600,
  val maxTotalChars: Int = 3_200,
  val minRemainingCharsToInject: Int = 128,
) {
  init {
    require(maxCharsPerFile >= 128) { "BootstrapContextResolverConfig maxCharsPerFile must be >= 128." }
    require(maxTotalChars >= maxCharsPerFile) {
      "BootstrapContextResolverConfig maxTotalChars must be >= maxCharsPerFile."
    }
    require(minRemainingCharsToInject >= 64) {
      "BootstrapContextResolverConfig minRemainingCharsToInject must be >= 64."
    }
  }
}

class BootstrapContextResolver(
  private val config: BootstrapContextResolverConfig = BootstrapContextResolverConfig(),
) {
  fun resolve(
    workspaceRoots: Set<Path>,
    mode: BootstrapMode,
  ): BootstrapContext {
    if (mode == BootstrapMode.NONE || workspaceRoots.isEmpty()) {
      return BootstrapContext(mode = BootstrapMode.NONE)
    }
    val normalizedRoots = workspaceRoots
      .map(Path::toAbsolutePath)
      .map(Path::normalize)
      .sortedBy(Path::toString)
    val candidates = normalizedRoots.flatMap { root ->
      BootstrapFileKind.entries.mapNotNull { kind ->
        if (!kind.isEnabledFor(mode)) {
          return@mapNotNull null
        }
        val file = root.resolve(kind.fileName)
        if (!Files.isRegularFile(file)) {
          return@mapNotNull null
        }
        val normalizedContent = String(
          Files.readAllBytes(file),
          StandardCharsets.UTF_8,
        )
          .trim()
          .takeIf(String::isNotBlank)
          ?: return@mapNotNull null
        CandidateBootstrapFile(
          name = kind.fileName,
          relativePath = displayPath(file = file, root = root, includeRootName = normalizedRoots.size > 1),
          content = normalizedContent,
        )
      }
    }
    if (candidates.isEmpty()) {
      return BootstrapContext(
        mode = mode,
        trace = BootstrapTrace(mode = mode.wireValue),
      )
    }
    var remainingChars = config.maxTotalChars
    var truncatedFileCount = 0
    val injected = mutableListOf<BootstrapSnippet>()
    val traceFiles = mutableListOf<BootstrapFileTrace>()
    candidates.forEach { candidate ->
      if (remainingChars < config.minRemainingCharsToInject) {
        return@forEach
      }
      val fileBudget = minOf(config.maxCharsPerFile, remainingChars)
      val boundedContent = boundContent(
        content = candidate.content,
        maxChars = fileBudget,
      )
      val truncated = boundedContent != candidate.content
      if (truncated) {
        truncatedFileCount += 1
      }
      injected += BootstrapSnippet(
        name = candidate.name,
        relativePath = candidate.relativePath,
        content = boundedContent,
        sourceCharCount = candidate.content.length,
        truncated = truncated,
      )
      traceFiles += BootstrapFileTrace(
        name = candidate.name,
        relativePath = candidate.relativePath,
        sourceCharCount = candidate.content.length,
        injectedCharCount = boundedContent.length,
        truncated = truncated,
      )
      remainingChars = (remainingChars - boundedContent.length).coerceAtLeast(0)
    }
    return BootstrapContext(
      mode = mode,
      files = injected,
      trace = BootstrapTrace(
        mode = mode.wireValue,
        visibleFileCount = candidates.size,
        injectedFileCount = injected.size,
        omittedFileCount = (candidates.size - injected.size).coerceAtLeast(0),
        truncatedFileCount = truncatedFileCount,
        files = traceFiles,
      ),
    )
  }

  private fun boundContent(
    content: String,
    maxChars: Int,
  ): String {
    if (content.length <= maxChars) {
      return content
    }
    return content.take(maxChars - 1).trimEnd() + "…"
  }

  private fun displayPath(
    file: Path,
    root: Path,
    includeRootName: Boolean,
  ): String {
    val relative = root.relativize(file.toAbsolutePath().normalize())
      .toString()
      .ifBlank { file.fileName.toString() }
      .replace(File.separatorChar, '/')
    if (!includeRootName) {
      return relative
    }
    val rootName = root.fileName?.toString()?.trim()?.takeIf(String::isNotBlank) ?: "root"
    return "$rootName/$relative"
  }

  private data class CandidateBootstrapFile(
    val name: String,
    val relativePath: String,
    val content: String,
  )
}
