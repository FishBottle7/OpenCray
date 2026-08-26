package com.opencray.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream
import org.opencray.app.R

internal object AppAgentWorkspaceSharer {
  fun shareEntries(
    appContext: Context,
    workspaceRoot: Path,
    relativePaths: List<String>,
  ) {
    val guard = AppAgentWorkspaceExportGuard.create(workspaceRoot)
    val outsideWorkspaceMessage = appContext.getString(
      R.string.files_share_error_outside_workspace,
    )
    val symbolicLinkMessage = appContext.getString(
      R.string.files_share_error_symbolic_link,
    )
    val sourcePaths = relativePaths
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .map { relativePath ->
        guard.resolveEntry(
          relativePath = relativePath,
          outsideWorkspaceMessage = outsideWorkspaceMessage,
          symbolicLinkMessage = symbolicLinkMessage,
        )
      }
    require(sourcePaths.isNotEmpty()) {
      appContext.getString(R.string.files_share_error_no_selection)
    }
    sourcePaths.forEach { source ->
      require(Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        appContext.getString(R.string.files_share_error_missing_entry)
      }
    }
    sourcePaths.forEach { source ->
      if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        guard.ensureTreeHasNoSymbolicLinks(
          directory = source,
          symbolicLinkMessage = symbolicLinkMessage,
        )
      }
    }

    val shareCacheDirectory = appContext.cacheDir.toPath()
      .resolve(SHARE_CACHE_DIRECTORY_NAME)
      .normalize()
    resetDirectory(shareCacheDirectory)

    val stagedFiles = runCatching {
      if (sourcePaths.size > 1) {
        listOf(
          stageSelectionArchive(
            guard = guard,
            sourcePaths = sourcePaths,
            destinationDirectory = shareCacheDirectory,
            outsideWorkspaceMessage = outsideWorkspaceMessage,
          ),
        )
      } else {
        val source = sourcePaths.single()
        listOf(
          if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            stageDirectoryArchive(
              guard = guard,
              source = source,
              destinationDirectory = shareCacheDirectory,
              outsideWorkspaceMessage = outsideWorkspaceMessage,
            )
          } else {
            stageFileCopy(
              guard = guard,
              source = source,
              destinationDirectory = shareCacheDirectory,
              outsideWorkspaceMessage = outsideWorkspaceMessage,
            )
          },
        )
      }
    }.getOrElse { throwable ->
      throw IllegalStateException(
        appContext.getString(R.string.files_share_error_prepare_failed),
        throwable,
      )
    }
    val uris = stagedFiles.map { staged ->
      FileProvider.getUriForFile(
        appContext,
        "${appContext.packageName}.fileprovider",
        staged.toFile(),
      )
    }
    val shareIntent = buildShareIntent(
      appContext = appContext,
      stagedFiles = stagedFiles,
      uris = uris,
    )
    val chooserIntent = Intent.createChooser(shareIntent, null).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    require(chooserIntent.resolveActivity(appContext.packageManager) != null) {
      appContext.getString(R.string.files_share_error_no_target)
    }
    runCatching {
      appContext.startActivity(chooserIntent)
    }.getOrElse { throwable ->
      throw IllegalStateException(
        appContext.getString(R.string.files_share_error_launch_failed),
        throwable,
      )
    }
  }

  private fun buildShareIntent(
    appContext: Context,
    stagedFiles: List<Path>,
    uris: List<Uri>,
  ): Intent {
    val isSingle = uris.size == 1
    val intent = Intent(
      if (isSingle) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE,
    ).apply {
      type = resolveMimeType(stagedFiles)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      clipData = buildClipData(appContext, stagedFiles, uris)
      if (isSingle) {
        putExtra(Intent.EXTRA_STREAM, uris.single())
      } else {
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
      }
    }
    return intent
  }

  private fun buildClipData(
    appContext: Context,
    stagedFiles: List<Path>,
    uris: List<Uri>,
  ): ClipData {
    val firstUri = uris.first()
    val clipData = ClipData.newUri(
      appContext.contentResolver,
      stagedFiles.first().fileName.toString(),
      firstUri,
    )
    uris.drop(1).forEachIndexed { index, uri ->
      clipData.addItem(
        ClipData.Item(uri),
      )
    }
    return clipData
  }

  private fun resolveMimeType(stagedFiles: List<Path>): String {
    if (stagedFiles.size != 1) {
      return "*/*"
    }
    val name = stagedFiles.single().fileName.toString()
    if (name.lowercase(Locale.US).endsWith(".zip")) {
      return "application/zip"
    }
    return URLConnection.guessContentTypeFromName(name) ?: "*/*"
  }

  private fun stageFileCopy(
    guard: AppAgentWorkspaceExportGuard,
    source: Path,
    destinationDirectory: Path,
    outsideWorkspaceMessage: String,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = source.fileName.toString(),
    )
    guard.copyFileIntoStaging(
      source = source,
      destination = destination,
      outsideWorkspaceMessage = outsideWorkspaceMessage,
    )
    return destination
  }

  private fun stageSelectionArchive(
    guard: AppAgentWorkspaceExportGuard,
    sourcePaths: List<Path>,
    destinationDirectory: Path,
    outsideWorkspaceMessage: String,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = MULTI_SELECTION_ARCHIVE_NAME,
    )
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
      sourcePaths.forEach { source ->
        guard.writeSourceToZip(
          output = output,
          source = source,
          rootName = source.name,
          outsideWorkspaceMessage = outsideWorkspaceMessage,
        )
      }
    }
    return destination
  }

  private fun stageDirectoryArchive(
    guard: AppAgentWorkspaceExportGuard,
    source: Path,
    destinationDirectory: Path,
    outsideWorkspaceMessage: String,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = "${source.name}.zip",
    )
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
      guard.writeSourceToZip(
        output = output,
        source = source,
        rootName = source.name,
        outsideWorkspaceMessage = outsideWorkspaceMessage,
      )
    }
    return destination
  }

  private fun uniqueDestination(
    destinationDirectory: Path,
    desiredName: String,
  ): Path {
    val extension = desiredName.substringAfterLast('.', "")
    val baseName = if (extension.isEmpty() || !desiredName.contains('.')) {
      desiredName
    } else {
      desiredName.substring(0, desiredName.length - extension.length - 1)
    }
    var candidate = destinationDirectory.resolve(desiredName)
    var suffix = 2
    while (candidate.exists()) {
      val suffixName = if (extension.isEmpty()) {
        "$baseName ($suffix)"
      } else {
        "$baseName ($suffix).$extension"
      }
      candidate = destinationDirectory.resolve(suffixName)
      suffix += 1
    }
    return candidate
  }

  private fun resetDirectory(directory: Path) {
    if (directory.exists()) {
      val deletionOrder = mutableListOf<Path>()
      Files.walkFileTree(
        directory,
        emptySet(),
        Int.MAX_VALUE,
        object : java.nio.file.SimpleFileVisitor<Path>() {
          override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            deletionOrder.add(file)
            return java.nio.file.FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): java.nio.file.FileVisitResult {
            deletionOrder.add(dir)
            return java.nio.file.FileVisitResult.CONTINUE
          }
        },
      )
      deletionOrder.forEach { path ->
        path.deleteIfExists()
      }
    }
    Files.createDirectories(directory)
  }

  private const val MULTI_SELECTION_ARCHIVE_NAME: String = "OpenCray Share.zip"
  private const val SHARE_CACHE_DIRECTORY_NAME: String = "workspace-share"
}
