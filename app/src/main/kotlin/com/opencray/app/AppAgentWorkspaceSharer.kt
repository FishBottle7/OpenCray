package com.opencray.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream
import org.opencray.app.R

internal object AppAgentWorkspaceSharer {
  fun shareEntries(
    appContext: Context,
    workspaceRoot: Path,
    relativePaths: List<String>,
  ) {
    val sourcePaths = relativePaths
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .map { relativePath ->
        resolvePath(
          workspaceRoot = workspaceRoot,
          relativePath = relativePath,
          allowRoot = false,
          outsideWorkspaceMessage = appContext.getString(
            R.string.files_share_error_outside_workspace,
          ),
        )
      }
    require(sourcePaths.isNotEmpty()) {
      appContext.getString(R.string.files_share_error_no_selection)
    }
    sourcePaths.forEach { source ->
      require(source.exists()) {
        appContext.getString(R.string.files_share_error_missing_entry)
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
            sourcePaths = sourcePaths,
            destinationDirectory = shareCacheDirectory,
          ),
        )
      } else {
        val source = sourcePaths.single()
        listOf(
          if (source.isDirectory()) {
            stageDirectoryArchive(source = source, destinationDirectory = shareCacheDirectory)
          } else {
            stageFileCopy(source = source, destinationDirectory = shareCacheDirectory)
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
    source: Path,
    destinationDirectory: Path,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = source.fileName.toString(),
    )
    Files.copy(
      source,
      destination,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.COPY_ATTRIBUTES,
    )
    return destination
  }

  private fun stageSelectionArchive(
    sourcePaths: List<Path>,
    destinationDirectory: Path,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = MULTI_SELECTION_ARCHIVE_NAME,
    )
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
      sourcePaths.forEach { source ->
        writeSourceToZip(output = output, source = source, rootName = source.name)
      }
    }
    return destination
  }

  private fun stageDirectoryArchive(
    source: Path,
    destinationDirectory: Path,
  ): Path {
    val destination = uniqueDestination(
      destinationDirectory = destinationDirectory,
      desiredName = "${source.name}.zip",
    )
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
      writeSourceToZip(output = output, source = source, rootName = source.name)
    }
    return destination
  }

  private fun writeSourceToZip(
    output: ZipOutputStream,
    source: Path,
    rootName: String,
  ) {
    Files.walk(source).use { stream ->
      stream.forEach { current ->
        val relative = source.relativize(current).toString().replace('\\', '/')
        val entryName = when {
          relative.isEmpty() && Files.isDirectory(current) -> "$rootName/"
          relative.isEmpty() -> rootName
          Files.isDirectory(current) -> "$rootName/$relative/"
          else -> "$rootName/$relative"
        }
        val entry = ZipEntry(entryName).apply {
          time = Files.getLastModifiedTime(current).toMillis()
        }
        output.putNextEntry(entry)
        if (!Files.isDirectory(current)) {
          current.inputStream().buffered().use { input ->
            input.copyTo(output)
          }
        }
        output.closeEntry()
      }
    }
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
      Files.walk(directory).use { stream ->
        stream
          .sorted(Comparator.reverseOrder())
          .forEach { path ->
            path.deleteIfExists()
          }
      }
    }
    Files.createDirectories(directory)
  }

  private fun resolvePath(
    workspaceRoot: Path,
    relativePath: String,
    allowRoot: Boolean,
    outsideWorkspaceMessage: String,
  ): Path {
    val normalizedRoot = workspaceRoot.toAbsolutePath().normalize()
    val trimmed = relativePath.trim().replace('\\', '/').removePrefix("/")
    if (trimmed.isEmpty()) {
      require(allowRoot) { "Workspace root cannot be targeted here." }
      return normalizedRoot
    }
    val resolved = normalizedRoot.resolve(trimmed).normalize()
    require(resolved.startsWith(normalizedRoot)) {
      outsideWorkspaceMessage
    }
    return resolved
  }

  private const val MULTI_SELECTION_ARCHIVE_NAME: String = "OpenCray Share.zip"
  private const val SHARE_CACHE_DIRECTORY_NAME: String = "workspace-share"
}
