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
import kotlin.io.path.name
import org.opencray.app.R

internal object AppAgentWorkspaceOpener {
  fun openEntry(
    appContext: Context,
    workspaceRoot: Path,
    relativePath: String,
  ) {
    val guard = AppAgentWorkspaceExportGuard.create(workspaceRoot)
    val outsideWorkspaceMessage = appContext.getString(
      R.string.files_open_error_outside_workspace,
    )
    val source = guard.resolveEntry(
      relativePath = relativePath,
      outsideWorkspaceMessage = outsideWorkspaceMessage,
      symbolicLinkMessage = appContext.getString(
        R.string.files_open_error_symbolic_link,
      ),
    )
    require(Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
      appContext.getString(R.string.files_open_error_missing_entry)
    }
    require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      appContext.getString(R.string.files_open_error_not_file)
    }

    val stagedFile = runCatching {
      stageFileCopy(
        guard = guard,
        source = source,
        destinationDirectory = appContext.cacheDir.toPath()
          .resolve(OPEN_CACHE_DIRECTORY_NAME)
          .normalize(),
        outsideWorkspaceMessage = outsideWorkspaceMessage,
      )
    }.getOrElse { throwable ->
      throw IllegalStateException(
        appContext.getString(R.string.files_open_error_prepare_failed),
        throwable,
      )
    }

    val uri = FileProvider.getUriForFile(
      appContext,
      "${appContext.packageName}.fileprovider",
      stagedFile.toFile(),
    )
    val primaryIntent = buildViewIntent(
      appContext = appContext,
      uri = uri,
      displayName = stagedFile.fileName.toString(),
      mimeType = resolveMimeType(stagedFile.name),
    )
    val launchIntent = when {
      primaryIntent.resolveActivity(appContext.packageManager) != null -> primaryIntent
      else -> buildViewIntent(
        appContext = appContext,
        uri = uri,
        displayName = stagedFile.fileName.toString(),
        mimeType = "*/*",
      ).takeIf { intent ->
        intent.resolveActivity(appContext.packageManager) != null
      }
    }
    requireNotNull(launchIntent) {
      appContext.getString(R.string.files_open_error_no_target)
    }
    runCatching {
      appContext.startActivity(launchIntent)
    }.getOrElse { throwable ->
      throw IllegalStateException(
        appContext.getString(R.string.files_open_error_launch_failed),
        throwable,
      )
    }
  }

  private fun buildViewIntent(
    appContext: Context,
    uri: Uri,
    displayName: String,
    mimeType: String,
  ): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, mimeType)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    clipData = ClipData.newUri(appContext.contentResolver, displayName, uri)
  }

  private fun stageFileCopy(
    guard: AppAgentWorkspaceExportGuard,
    source: Path,
    destinationDirectory: Path,
    outsideWorkspaceMessage: String,
  ): Path {
    Files.createDirectories(destinationDirectory)
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
    while (Files.exists(candidate)) {
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

  private fun resolveMimeType(fileName: String): String {
    val guessed = URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (guessed != null) {
      return guessed
    }
    return when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
      "md" -> "text/markdown"
      "json" -> "application/json"
      "txt", "log" -> "text/plain"
      "m4a" -> "audio/mp4"
      "wav" -> "audio/wav"
      "mp3" -> "audio/mpeg"
      else -> "*/*"
    }
  }

  private const val OPEN_CACHE_DIRECTORY_NAME: String = "workspace-open"
}
