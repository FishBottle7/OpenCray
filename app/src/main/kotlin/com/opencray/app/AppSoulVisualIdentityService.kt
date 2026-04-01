package com.opencray.app

import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCraySoulVisualIdentity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

internal class AppSoulVisualIdentityService(
  private val soulProfileStore: WorkspaceSoulProfileStore,
  private val promotionService: AppImageReferencePromotionService,
) {
  fun savePrimaryPortrait(
    workspaceRoot: Path,
    source: OpenCrayImageReferenceSource,
  ): OpenCraySoulVisualIdentity? {
    val existingVisualIdentity = soulProfileStore.loadSoulVisualIdentity(workspaceRoot)
    val promoted = promotionService.promoteForSoulPrimaryPortrait(source) ?: return null
    val updatedVisualIdentity = OpenCraySoulVisualIdentity(
      portraitSummary = promoted.portraitSummary ?: existingVisualIdentity?.portraitSummary ?: promoted.reference.summary,
      primaryPortrait = promoted.reference,
      referenceImages = existingVisualIdentity?.referenceImages.orEmpty(),
    )
    return saveVisualIdentity(
      workspaceRoot = workspaceRoot,
      visualIdentity = updatedVisualIdentity,
      promotedReference = promoted,
    )
  }

  fun saveReferenceImage(
    workspaceRoot: Path,
    refId: String,
    source: OpenCrayImageReferenceSource,
  ): OpenCraySoulVisualIdentity? {
    val existingVisualIdentity = soulProfileStore.loadSoulVisualIdentity(workspaceRoot)
      ?: OpenCraySoulVisualIdentity()
    val promoted = promotionService.promoteForSoulReference(
      refId = refId,
      source = source,
    ) ?: return null
    val updatedVisualIdentity = OpenCraySoulVisualIdentity(
      portraitSummary = existingVisualIdentity.portraitSummary,
      primaryPortrait = existingVisualIdentity.primaryPortrait,
      referenceImages = mergeReferenceImages(
        existing = existingVisualIdentity.referenceImages,
        incoming = listOf(promoted.reference),
      ),
    )
    return saveVisualIdentity(
      workspaceRoot = workspaceRoot,
      visualIdentity = updatedVisualIdentity,
      promotedReference = promoted,
    )
  }

  private fun saveVisualIdentity(
    workspaceRoot: Path,
    visualIdentity: OpenCraySoulVisualIdentity,
    promotedReference: AppPromotedImageReference,
  ): OpenCraySoulVisualIdentity? {
    return runCatching {
      soulProfileStore.saveSoulVisualIdentity(
        workspaceRoot = workspaceRoot,
        visualIdentity = visualIdentity,
      )
      visualIdentity
    }.getOrElse {
      cleanupPromotedAsset(promotedReference)
      null
    }
  }

  private fun cleanupPromotedAsset(
    promotedReference: AppPromotedImageReference,
  ) {
    if (promotedReference.mode != AppImageReferencePromotionMode.COPY_PROMOTE || promotedReference.reusedExistingAsset) {
      return
    }
    runCatching {
      promotedReference.resolvedAssetPath.deleteIfExists()
      cleanupEmptyParentDirectories(promotedReference.resolvedAssetPath.parent)
    }
  }

  private fun cleanupEmptyParentDirectories(directory: Path?) {
    var current = directory
    repeat(3) {
      val path = current ?: return
      if (!Files.exists(path)) {
        current = path.parent
        return@repeat
      }
      val isEmpty = Files.list(path).use { stream ->
        !stream.findAny().isPresent
      }
      if (!isEmpty) {
        return
      }
      path.deleteIfExists()
      current = path.parent
    }
  }

  private fun mergeReferenceImages(
    existing: List<OpenCrayImageReference>,
    incoming: List<OpenCrayImageReference>,
  ): List<OpenCrayImageReference> {
    if (incoming.isEmpty()) {
      return existing
    }
    val merged = existing.toMutableList()
    incoming.forEach { candidate ->
      val existingIndex = merged.indexOfFirst { persisted ->
        referencesMatch(persisted, candidate)
      }
      if (existingIndex < 0) {
        merged += candidate
      } else {
        merged[existingIndex] = mergeReference(
          existing = merged[existingIndex],
          incoming = candidate,
        )
      }
    }
    return merged.toList()
  }

  private fun referencesMatch(
    existing: OpenCrayImageReference,
    incoming: OpenCrayImageReference,
  ): Boolean = when {
    !existing.sha256.isNullOrBlank() && !incoming.sha256.isNullOrBlank() ->
      existing.sha256.equals(incoming.sha256, ignoreCase = true)

    existing.storageScope == incoming.storageScope && existing.relativePath == incoming.relativePath -> true
    existing.refId == incoming.refId -> true
    else -> false
  }

  private fun mergeReference(
    existing: OpenCrayImageReference,
    incoming: OpenCrayImageReference,
  ): OpenCrayImageReference = existing.copy(
    mimeType = existing.mimeType ?: incoming.mimeType,
    sha256 = existing.sha256 ?: incoming.sha256,
    widthPx = existing.widthPx ?: incoming.widthPx,
    heightPx = existing.heightPx ?: incoming.heightPx,
    caption = existing.caption ?: incoming.caption,
    sourceLabel = existing.sourceLabel ?: incoming.sourceLabel,
    sourceSessionId = existing.sourceSessionId ?: incoming.sourceSessionId,
    sourceMessageId = existing.sourceMessageId ?: incoming.sourceMessageId,
    createdAtEpochMs = minOf(existing.createdAtEpochMs, incoming.createdAtEpochMs),
  )
}
