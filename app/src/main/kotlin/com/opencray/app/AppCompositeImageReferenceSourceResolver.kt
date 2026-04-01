package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.OpenCrayAttachmentArtifact
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal data class AppResolvedImageAssetHandle(
  val path: Path,
  val displayName: String? = null,
  val mimeType: String? = null,
  val sourceSessionId: String? = null,
  val sourceMessageId: String? = null,
) {
  init {
    require(displayName == null || displayName.isNotBlank()) {
      "AppResolvedImageAssetHandle displayName must not be blank when provided."
    }
    require(mimeType == null || mimeType.isNotBlank()) {
      "AppResolvedImageAssetHandle mimeType must not be blank when provided."
    }
    require(sourceSessionId == null || sourceSessionId.isNotBlank()) {
      "AppResolvedImageAssetHandle sourceSessionId must not be blank when provided."
    }
    require(sourceMessageId == null || sourceMessageId.isNotBlank()) {
      "AppResolvedImageAssetHandle sourceMessageId must not be blank when provided."
    }
  }
}

internal class AppCompositeImageReferenceSourceResolver(
  workspaceRoot: Path?,
  privateRoot: Path,
  private val chatAttachmentLookup: ((OpenCrayImageReferenceSource) -> AppResolvedImageAssetHandle?)? = null,
  private val runArtifactLookup: ((OpenCrayImageReferenceSource) -> AppResolvedImageAssetHandle?)? = null,
  private val settingsAssetLookup: ((OpenCrayImageReferenceSource) -> AppResolvedImageAssetHandle?)? = null,
) : AppImageReferenceSourceResolver {
  private val fallbackResolver = DefaultAppImageReferenceSourceResolver(
    workspaceRoot = workspaceRoot,
    privateRoot = privateRoot,
  )

  override fun resolve(source: OpenCrayImageReferenceSource): ResolvedAppImageReferenceSource? = when (source.sourceKind) {
    OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT -> chatAttachmentLookup
      ?.invoke(source)
      ?.toResolvedSource(source)

    OpenCrayImageReferenceSourceKind.RUN_ARTIFACT -> runArtifactLookup
      ?.invoke(source)
      ?.toResolvedSource(source)

    OpenCrayImageReferenceSourceKind.SETTINGS_ASSET -> settingsAssetLookup
      ?.invoke(source)
      ?.toResolvedSource(source)

    OpenCrayImageReferenceSourceKind.WORKSPACE_PATH,
    OpenCrayImageReferenceSourceKind.DURABLE_ASSET,
    -> fallbackResolver.resolve(source)
  }

  private fun AppResolvedImageAssetHandle.toResolvedSource(
    source: OpenCrayImageReferenceSource,
  ): ResolvedAppImageReferenceSource = ResolvedAppImageReferenceSource(
    source = source,
    path = path.toAbsolutePath().normalize(),
    displayName = displayName?.trim()?.takeIf(String::isNotBlank) ?: source.displayName,
    mimeType = mimeType?.trim()?.takeIf(String::isNotBlank) ?: source.mimeType,
    sourceSessionId = sourceSessionId?.trim()?.takeIf(String::isNotBlank) ?: source.sourceSessionId,
    sourceMessageId = sourceMessageId?.trim()?.takeIf(String::isNotBlank) ?: source.sourceMessageId,
  )
}

internal fun ChatAttachmentEntry.toAppResolvedImageAssetHandle(
  workspaceRoot: Path,
  sourceSessionId: String? = null,
  sourceMessageId: String? = null,
): AppResolvedImageAssetHandle? {
  if (kind != ChatAttachmentKind.IMAGE) {
    return null
  }
  val resolvedPath = resolvePathInsideRoot(
    root = workspaceRoot,
    relativePath = localPath,
  ) ?: return null
  return AppResolvedImageAssetHandle(
    path = resolvedPath,
    displayName = displayName,
    mimeType = mimeType,
    sourceSessionId = sourceSessionId,
    sourceMessageId = sourceMessageId,
  )
}

internal fun OpenCrayAttachmentArtifact.toAppResolvedImageAssetHandle(
  workspaceRoot: Path,
): AppResolvedImageAssetHandle? {
  if (!isImageLike()) {
    return null
  }
  val resolvedPath = resolvePathInsideRoot(
    root = workspaceRoot,
    relativePath = relativePath,
  ) ?: return null
  return AppResolvedImageAssetHandle(
    path = resolvedPath,
    displayName = displayName?.trim()?.takeIf(String::isNotBlank),
    mimeType = mimeType?.trim()?.takeIf(String::isNotBlank),
  )
}

private fun OpenCrayAttachmentArtifact.isImageLike(): Boolean {
  val normalizedKindHint = kindHint?.trim()?.lowercase(Locale.US)
  if (normalizedKindHint == "image") {
    return true
  }
  val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.US)
  if (normalizedMimeType?.startsWith("image/") == true) {
    return true
  }
  val candidateName = displayName
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: relativePath.substringAfterLast('/').trim()
  val extension = candidateName.substringAfterLast('.', "").lowercase(Locale.US)
  return extension in IMAGE_EXTENSIONS
}

private fun resolvePathInsideRoot(
  root: Path,
  relativePath: String,
): Path? {
  val normalizedRoot = root.toAbsolutePath().normalize()
  val normalizedRelativePath = relativePath
    .trim()
    .replace('\\', '/')
    .removePrefix("/")
    .takeIf(String::isNotEmpty)
    ?: return null
  val resolved = normalizedRoot.resolve(normalizedRelativePath).normalize()
  if (!resolved.startsWith(normalizedRoot)) {
    return null
  }
  return resolved.takeIf { path ->
    Files.exists(path) && Files.isRegularFile(path)
  }
}

private val IMAGE_EXTENSIONS: Set<String> = setOf(
  "bmp",
  "gif",
  "heic",
  "heif",
  "jpeg",
  "jpg",
  "png",
  "webp",
)
