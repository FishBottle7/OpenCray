package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.memory.MemoryImageReferenceSupport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

internal class AppMemoryImageReferencePromotionService(
  private val promotionService: AppImageReferencePromotionService,
  private val memoryImageReferenceService: AppMemoryImageReferenceService,
) {
  fun attachSource(
    memoryId: String,
    source: OpenCrayImageReferenceSource,
    preferredMode: AppImageReferencePromotionMode? = null,
  ): MemoryRecord? {
    val promoted = promotionService.promoteForMemory(
      memoryId = memoryId,
      source = source,
      preferredMode = preferredMode,
    ) ?: return null
    val updatedRecord = runCatching {
      memoryImageReferenceService.attachPromotedReference(
        memoryId = memoryId,
        promotedReference = promoted,
      )
    }.getOrNull()
    if (updatedRecord == null) {
      cleanupPromotedAsset(promoted)
      return null
    }
    if (!recordReferencesPromotedAsset(updatedRecord, promoted)) {
      cleanupPromotedAsset(promoted)
    }
    return updatedRecord
  }

  private fun recordReferencesPromotedAsset(
    record: MemoryRecord,
    promoted: AppPromotedImageReference,
  ): Boolean = MemoryImageReferenceSupport.decodeFromExtensions(record.extensions).any { reference ->
    reference.storageScope == promoted.reference.storageScope &&
      reference.relativePath == promoted.reference.relativePath
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
}
