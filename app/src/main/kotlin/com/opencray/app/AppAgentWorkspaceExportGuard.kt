package com.opencray.app

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class AppAgentWorkspaceExportGuard private constructor(
  private val realRoot: Path,
) {

  fun resolveEntry(
    relativePath: String,
    outsideWorkspaceMessage: String,
    symbolicLinkMessage: String,
  ): Path {
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    require(trimmed.isNotEmpty()) { outsideWorkspaceMessage }
    require(trimmed.split('/').none { it == ".." }) { outsideWorkspaceMessage }
    val resolved = realRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(realRoot)) { outsideWorkspaceMessage }
    if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      return resolved
    }
    requirePathHasNoSymbolicLinks(resolved, symbolicLinkMessage)
    val realTarget = runCatching { resolved.toRealPath() }
      .getOrElse { throw IllegalArgumentException(outsideWorkspaceMessage) }
    require(realTarget.startsWith(realRoot)) { outsideWorkspaceMessage }
    return realTarget
  }

  fun ensureTreeHasNoSymbolicLinks(
    directory: Path,
    symbolicLinkMessage: String,
  ) {
    Files.walkFileTree(
      directory,
      emptySet(),
      Int.MAX_VALUE,
      object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
          if (Files.isSymbolicLink(dir)) {
            throw IllegalArgumentException(symbolicLinkMessage)
          }
          return java.nio.file.FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
          if (Files.isSymbolicLink(file)) {
            throw IllegalArgumentException(symbolicLinkMessage)
          }
          return java.nio.file.FileVisitResult.CONTINUE
        }
      },
    )
  }

  fun copyFileIntoStaging(
    source: Path,
    destination: Path,
    outsideWorkspaceMessage: String,
  ) {
    openFileWithinBoundary(source, outsideWorkspaceMessage).use { input ->
      Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  fun writeSourceToZip(
    output: ZipOutputStream,
    source: Path,
    rootName: String,
    outsideWorkspaceMessage: String,
  ) {
    val stagedEntries = mutableListOf<Pair<Path, String>>()
    Files.walkFileTree(
      source,
      emptySet(),
      Int.MAX_VALUE,
      object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
          require(isWithinBoundary(dir)) { outsideWorkspaceMessage }
          stagedEntries.add(dir to source.relativize(dir).toString().replace('\\', '/'))
          return java.nio.file.FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
          require(isWithinBoundary(file)) { outsideWorkspaceMessage }
          stagedEntries.add(file to source.relativize(file).toString().replace('\\', '/'))
          return java.nio.file.FileVisitResult.CONTINUE
        }
      },
    )
    stagedEntries.forEach { (current, relative) ->
      val entryName = when {
        relative.isEmpty() && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) -> "$rootName/"
        relative.isEmpty() -> rootName
        Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) -> "$rootName/$relative/"
        else -> "$rootName/$relative"
      }
      val entry = ZipEntry(entryName).apply {
        time = Files.getLastModifiedTime(current, LinkOption.NOFOLLOW_LINKS).toMillis()
      }
      output.putNextEntry(entry)
      if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        openFileWithinBoundary(current, outsideWorkspaceMessage)
          .buffered()
          .use { input -> input.copyTo(output) }
      }
      output.closeEntry()
    }
  }

  private fun openFileWithinBoundary(
    file: Path,
    outsideWorkspaceMessage: String,
  ): InputStream {
    val input = Files.newInputStream(
      file,
      StandardOpenOption.READ,
      LinkOption.NOFOLLOW_LINKS,
    )
    if (!isWithinBoundary(file)) {
      runCatching { input.close() }
      throw IllegalArgumentException(outsideWorkspaceMessage)
    }
    return input
  }

  private fun isWithinBoundary(file: Path): Boolean {
    if (Files.isSymbolicLink(file)) {
      return false
    }
    val realTarget = runCatching { file.toRealPath() }.getOrNull() ?: return false
    return realTarget.startsWith(realRoot)
  }

  private fun requirePathHasNoSymbolicLinks(
    target: Path,
    symbolicLinkMessage: String,
  ) {
    var current: Path = realRoot
    for (segment in realRoot.relativize(target)) {
      current = current.resolve(segment.toString())
      if (Files.isSymbolicLink(current)) {
        throw IllegalArgumentException(symbolicLinkMessage)
      }
    }
  }

  companion object {
    fun create(workspaceRoot: Path): AppAgentWorkspaceExportGuard {
      val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
      val canonicalRoot = runCatching { normalizedRoot.toRealPath() }
        .getOrDefault(normalizedRoot)
      return AppAgentWorkspaceExportGuard(canonicalRoot)
    }
  }
}
