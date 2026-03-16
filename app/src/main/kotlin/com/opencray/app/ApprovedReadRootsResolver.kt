package com.opencray.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.opencray.app.facade.safety.SafetySettingsSnapshot
import com.opencray.policy.ExternalAccessMode
import java.nio.file.Files
import java.nio.file.Path

internal data class ApprovedReadRootsSnapshot(
  val roots: Set<Path>,
  val summary: String,
)

internal object ApprovedReadRootsResolver {
  fun hasGrantedPermission(
    context: Context,
    locationId: String,
  ): Boolean = permissionsToRequest(
    context = context,
    locationId = locationId,
  ).isEmpty()

  fun hasAccessibleLocation(
    context: Context,
    locationId: String,
  ): Boolean = readablePathsForLocation(
    context = context,
    locationId = locationId,
  ).isNotEmpty()

  fun resolve(
    context: Context?,
    workspaceRoot: Path,
    safetySettings: SafetySettingsState,
  ): ApprovedReadRootsSnapshot = resolve(
    context = context,
    workspaceRoot = workspaceRoot,
    externalAccessMode = safetySettings.externalAccessMode,
    enabledLocationIds = enabledLocationIds(safetySettings),
  )

  fun resolve(
    context: Context?,
    workspaceRoot: Path,
    safetySettings: SafetySettingsSnapshot,
  ): ApprovedReadRootsSnapshot = resolve(
    context = context,
    workspaceRoot = workspaceRoot,
    externalAccessMode = safetySettings.externalAccessMode,
    enabledLocationIds = safetySettings.locations
      .filter { location -> location.enabled }
      .map { location -> location.id }
      .toSet(),
  )

  private fun resolve(
    context: Context?,
    workspaceRoot: Path,
    externalAccessMode: ExternalAccessMode,
    enabledLocationIds: Set<String>,
  ): ApprovedReadRootsSnapshot {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val approvedRoots = linkedSetOf<Path>(normalizedWorkspaceRoot)
    val summaryEntries = mutableListOf<String>()
    if (externalAccessMode != ExternalAccessMode.SELECT_PATHS || context == null) {
      return ApprovedReadRootsSnapshot(
        roots = approvedRoots,
        summary = "workspace=${normalizedWorkspaceRoot.toString().replace('\\', '/')}",
      )
    }

    enabledLocationIds
      .sorted()
      .forEach { locationId ->
        val paths = readablePathsForLocation(context = context, locationId = locationId)
        if (paths.isEmpty()) {
          return@forEach
        }
        approvedRoots += paths
        summaryEntries += "$locationId=${paths.joinToString(separator = ",") { path -> path.toString().replace('\\', '/') }}"
      }

    val summary = buildString {
      append("workspace=")
      append(normalizedWorkspaceRoot.toString().replace('\\', '/'))
      if (summaryEntries.isNotEmpty()) {
        append(" | ")
        append(summaryEntries.joinToString(separator = " | "))
      }
    }
    return ApprovedReadRootsSnapshot(
      roots = approvedRoots,
      summary = summary,
    )
  }

  private fun readablePathsForLocation(
    context: Context,
    locationId: String,
  ): Set<Path> {
    if (!hasGrantedPermission(context = context, locationId = locationId)) {
      return emptySet()
    }
    return candidateDirectoriesForLocation(locationId)
      .map(Path::toAbsolutePath)
      .map(Path::normalize)
      .filter(::isReadableDirectory)
      .toCollection(linkedSetOf())
  }

  private fun candidateDirectoriesForLocation(locationId: String): List<Path> = when (locationId) {
    "photo_library" -> listOfNotNull(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)?.toPath(),
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)?.toPath(),
    )

    "downloads" -> listOfNotNull(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.toPath(),
    )

    "documents" -> listOfNotNull(
      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.toPath(),
    )

    "recordings" -> listOfNotNull(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)?.toPath()
      } else {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
          ?.toPath()
          ?.resolve("Recordings")
      },
    )

    else -> emptyList()
  }

  private fun enabledLocationIds(safetySettings: SafetySettingsState): Set<String> = buildSet {
    if (safetySettings.photoLibraryEnabled) {
      add("photo_library")
    }
    if (safetySettings.downloadsEnabled) {
      add("downloads")
    }
    if (safetySettings.documentsEnabled) {
      add("documents")
    }
    if (safetySettings.recordingsEnabled) {
      add("recordings")
    }
  }

  private fun isReadableDirectory(path: Path): Boolean {
    if (!Files.isDirectory(path)) {
      return false
    }
    return runCatching {
      Files.newDirectoryStream(path).use { true }
    }.getOrDefault(false)
  }

  fun permissionsToRequest(
    context: Context,
    locationId: String,
  ): List<String> = requiredPermissionsFor(locationId)
    .filter { permission ->
      ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

  private fun requiredPermissionsFor(locationId: String): List<String> {
    return when (locationId) {
      "photo_library" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
          Manifest.permission.READ_MEDIA_IMAGES,
          Manifest.permission.READ_MEDIA_VIDEO,
        )
      } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
      }

      "recordings" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.READ_MEDIA_AUDIO)
      } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
      }

      "downloads",
      "documents",
      -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        emptyList()
      } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
      }

      else -> emptyList()
    }
  }
}
