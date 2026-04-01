package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.memory.MemoryImageReferenceSupport

internal class AppMemoryImageReferenceService(
  private val memoryStore: MemoryStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  fun listImageReferences(memoryId: String): List<OpenCrayImageReference> {
    require(memoryId.isNotBlank()) { "memoryId must not be blank." }
    val record = findRecord(memoryId) ?: return emptyList()
    return MemoryImageReferenceSupport.decodeFromExtensions(record.extensions)
  }

  fun attachPromotedReference(
    memoryId: String,
    promotedReference: AppPromotedImageReference,
  ): MemoryRecord? = attachImageReferences(
    memoryId = memoryId,
    imageReferences = listOf(promotedReference.reference),
  )

  fun attachImageReferences(
    memoryId: String,
    imageReferences: List<OpenCrayImageReference>,
  ): MemoryRecord? {
    require(memoryId.isNotBlank()) { "memoryId must not be blank." }
    val existingRecord = findRecord(memoryId) ?: return null
    if (imageReferences.isEmpty()) {
      return existingRecord
    }
    val existingReferences = MemoryImageReferenceSupport.decodeFromExtensions(existingRecord.extensions)
    val mergedReferences = MemoryImageReferenceSupport.mergeImageReferences(
      existing = existingReferences,
      incoming = imageReferences,
    )
    if (mergedReferences == existingReferences) {
      return existingRecord
    }
    val updatedRecord = existingRecord.copy(
      recordVersion = existingRecord.recordVersion + 1L,
      updatedAtEpochMs = maxOf(existingRecord.createdAtEpochMs, clock()),
      extensions = MemoryImageReferenceSupport.encodeIntoExtensions(
        extensions = existingRecord.extensions,
        imageReferences = mergedReferences,
      ),
    )
    memoryStore.upsert(updatedRecord)
    return updatedRecord
  }

  private fun findRecord(memoryId: String): MemoryRecord? =
    memoryStore.list().firstOrNull { record -> record.id == memoryId }
}
