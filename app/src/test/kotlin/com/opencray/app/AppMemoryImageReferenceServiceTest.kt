package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import com.opencray.runtime.memory.MemoryImageReferenceSupport
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppMemoryImageReferenceServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun attachPromotedReferencePersistsImageRefsWithoutDroppingOtherMetadata() {
    val store = InMemoryMemoryStore().apply {
      upsert(
        MemoryRecord(
          id = "memory-1",
          content = "Prototype uses a two-column landing-page layout.",
          tags = listOf("kind:project_fact", "scope:workspace"),
          createdAtEpochMs = 100L,
          updatedAtEpochMs = 120L,
          recordVersion = 2L,
          extensions = mapOf(
            MemoryRecordExtensionKeys.KIND to "project_fact",
            MemoryRecordExtensionKeys.SCOPE to "workspace",
            "note" to "keep",
          ),
        ),
      )
    }
    val service = AppMemoryImageReferenceService(
      memoryStore = store,
      clock = { 500L },
    )

    val updated = service.attachPromotedReference(
      memoryId = "memory-1",
      promotedReference = promotedReference(
        reference = imageReference(
          refId = "memory-img-1",
          relativePath = "memory-media/memory-1/first.png",
          sha256 = "a".repeat(64),
          caption = "Wireframe evidence",
        ),
      ),
    )

    requireNotNull(updated)
    assertEquals(3L, updated.recordVersion)
    assertEquals(500L, updated.updatedAtEpochMs)
    assertEquals("keep", updated.extensions["note"])
    val decoded = MemoryImageReferenceSupport.decodeFromExtensions(updated.extensions)
    assertEquals(1, decoded.size)
    assertEquals("memory-img-1", decoded.single().refId)
    assertEquals("Wireframe evidence", decoded.single().caption)
  }

  @Test
  fun attachPromotedReferenceIsIdempotentForDuplicateAsset() {
    val initialRecord = MemoryRecord(
      id = "memory-2",
      content = "Prototype references the same whiteboard image.",
      tags = listOf("kind:project_fact", "scope:workspace"),
      createdAtEpochMs = 200L,
      updatedAtEpochMs = 300L,
      recordVersion = 4L,
      extensions = MemoryImageReferenceSupport.encodeIntoExtensions(
        extensions = mapOf(
          MemoryRecordExtensionKeys.KIND to "project_fact",
          MemoryRecordExtensionKeys.SCOPE to "workspace",
        ),
        imageReferences = listOf(
          imageReference(
            refId = "memory-img-existing",
            relativePath = "memory-media/memory-2/original.png",
            sha256 = "b".repeat(64),
            caption = "Original evidence",
          ),
        ),
      ),
    )
    val store = InMemoryMemoryStore().apply {
      upsert(initialRecord)
    }
    val service = AppMemoryImageReferenceService(
      memoryStore = store,
      clock = { 900L },
    )

    val updated = service.attachPromotedReference(
      memoryId = "memory-2",
      promotedReference = promotedReference(
        reference = imageReference(
          refId = "memory-img-reimported",
          relativePath = "memory-media/memory-2/reimported.png",
          sha256 = "b".repeat(64),
          caption = "Reimported evidence",
        ),
      ),
    )

    requireNotNull(updated)
    assertEquals(4L, updated.recordVersion)
    val decoded = MemoryImageReferenceSupport.decodeFromExtensions(updated.extensions)
    assertEquals(1, decoded.size)
    assertEquals("memory-img-existing", decoded.single().refId)
    assertEquals("Original evidence", decoded.single().caption)

    val second = service.attachPromotedReference(
      memoryId = "memory-2",
      promotedReference = promotedReference(
        reference = imageReference(
          refId = "memory-img-reimported",
          relativePath = "memory-media/memory-2/reimported.png",
          sha256 = "b".repeat(64),
          caption = "Reimported evidence",
        ),
      ),
    )

    requireNotNull(second)
    assertEquals(updated.recordVersion, second.recordVersion)
    assertEquals(updated.updatedAtEpochMs, second.updatedAtEpochMs)
    assertEquals(1, MemoryImageReferenceSupport.decodeFromExtensions(second.extensions).size)
  }

  @Test
  fun attachImageReferencesReturnsNullWhenRecordDoesNotExist() {
    val service = AppMemoryImageReferenceService(
      memoryStore = InMemoryMemoryStore(),
    )

    val updated = service.attachImageReferences(
      memoryId = "missing-memory",
      imageReferences = listOf(
        imageReference(
          refId = "memory-img-missing",
          relativePath = "memory-media/missing/image.png",
          sha256 = "c".repeat(64),
        ),
      ),
    )

    assertNull(updated)
    assertTrue(service.listImageReferences("missing-memory").isEmpty())
  }

  private fun promotedReference(
    reference: OpenCrayImageReference,
  ): AppPromotedImageReference {
    val resolvedPath = Files.createTempFile(
      temporaryFolder.root.toPath(),
      "${reference.refId}-",
      ".png",
    )
    Files.write(resolvedPath, byteArrayOf(1, 2, 3, 4))
    return AppPromotedImageReference(
      reference = reference,
      mode = AppImageReferencePromotionMode.COPY_PROMOTE,
      resolvedAssetPath = resolvedPath,
      portraitSummary = null,
      reusedExistingAsset = false,
    )
  }

  private fun imageReference(
    refId: String,
    relativePath: String,
    sha256: String,
    caption: String? = "Evidence",
  ): OpenCrayImageReference = OpenCrayImageReference(
    refId = refId,
    role = OpenCrayImageReferenceRole.EVIDENCE,
    storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
    relativePath = relativePath,
    mimeType = "image/png",
    sha256 = sha256,
    widthPx = 1280,
    heightPx = 720,
    caption = caption,
    summary = "A persistent visual reference for the memory.",
    sourceLabel = "user_sent",
    sourceSessionId = "session-1",
    sourceMessageId = "message-1",
    createdAtEpochMs = 42L,
  )

  private class InMemoryMemoryStore : MemoryStore {
    private val records = linkedMapOf<String, MemoryRecord>()

    override fun list(): List<MemoryRecord> = records.values.toList()

    override fun upsert(record: MemoryRecord) {
      records[record.id] = record
    }

    override fun delete(id: String): Boolean = records.remove(id) != null

    override fun clear(): Boolean {
      val hadRecords = records.isNotEmpty()
      records.clear()
      return hadRecords
    }
  }
}
