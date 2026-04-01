package com.opencray.runtime.memory

import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImageReferenceSupportTest {
  @Test
  fun encodeAndDecodeRoundTripsImageReferencesThroughExtensions() {
    val encoded = MemoryImageReferenceSupport.encodeIntoExtensions(
      extensions = mapOf(MemoryRecordExtensionKeys.KIND to "project_fact"),
      imageReferences = listOf(
        OpenCrayImageReference(
          refId = "memory-img-1",
          role = OpenCrayImageReferenceRole.EVIDENCE,
          storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
          relativePath = "memory-media/fact-1/abc-wireframe.png",
          mimeType = "image/png",
          sha256 = "a".repeat(64),
          widthPx = 1280,
          heightPx = 720,
          caption = "Wireframe",
          summary = "A two-column landing-page wireframe.",
          sourceLabel = "chat_attachment",
          sourceSessionId = "session-1",
          sourceMessageId = "message-1",
          createdAtEpochMs = 42L,
        ),
      ),
    )

    val decoded = MemoryImageReferenceSupport.decodeFromExtensions(encoded)

    assertEquals(1, decoded.size)
    assertEquals("memory-img-1", decoded.single().refId)
    assertEquals("memory-media/fact-1/abc-wireframe.png", decoded.single().relativePath)
    assertEquals("project_fact", encoded[MemoryRecordExtensionKeys.KIND])
    assertTrue(encoded.containsKey(MemoryRecordExtensionKeys.IMAGE_REFS_JSON))
  }

  @Test
  fun encodeRemovesImageRefsKeyWhenListIsEmpty() {
    val encoded = MemoryImageReferenceSupport.encodeIntoExtensions(
      extensions = mapOf(
        MemoryRecordExtensionKeys.KIND to "project_fact",
        MemoryRecordExtensionKeys.IMAGE_REFS_JSON to """[{"refId":"old"}]""",
      ),
      imageReferences = emptyList(),
    )

    assertEquals("project_fact", encoded[MemoryRecordExtensionKeys.KIND])
    assertFalse(encoded.containsKey(MemoryRecordExtensionKeys.IMAGE_REFS_JSON))
  }

  @Test
  fun decodeReturnsEmptyListForInvalidPayload() {
    val decoded = MemoryImageReferenceSupport.decodeFromExtensions(
      mapOf(MemoryRecordExtensionKeys.IMAGE_REFS_JSON to "{not-json"),
    )

    assertTrue(decoded.isEmpty())
  }

  @Test
  fun mergeImageReferencesDeduplicatesBySha256AndKeepsStableIdentity() {
    val merged = MemoryImageReferenceSupport.mergeImageReferences(
      existing = listOf(
        imageReference(
          refId = "memory-img-existing",
          relativePath = "memory-media/fact-1/original.png",
          sha256 = "b".repeat(64),
          caption = null,
          sourceLabel = null,
          createdAtEpochMs = 120L,
        ),
      ),
      incoming = listOf(
        imageReference(
          refId = "memory-img-new",
          relativePath = "memory-media/fact-1/reimported.png",
          sha256 = "b".repeat(64),
          caption = "Reimported evidence",
          sourceLabel = "chat_attachment",
          createdAtEpochMs = 240L,
        ),
      ),
    )

    assertEquals(1, merged.size)
    assertEquals("memory-img-existing", merged.single().refId)
    assertEquals("memory-media/fact-1/original.png", merged.single().relativePath)
    assertEquals("Reimported evidence", merged.single().caption)
    assertEquals("chat_attachment", merged.single().sourceLabel)
    assertEquals(120L, merged.single().createdAtEpochMs)
  }

  @Test
  fun mergeImageReferencesAppendsDistinctReferencesInOrder() {
    val merged = MemoryImageReferenceSupport.mergeImageReferences(
      existing = listOf(
        imageReference(
          refId = "memory-img-1",
          relativePath = "memory-media/fact-1/first.png",
          sha256 = "c".repeat(64),
        ),
      ),
      incoming = listOf(
        imageReference(
          refId = "memory-img-2",
          relativePath = "memory-media/fact-1/second.png",
          sha256 = "d".repeat(64),
        ),
      ),
    )

    assertEquals(listOf("memory-img-1", "memory-img-2"), merged.map(OpenCrayImageReference::refId))
  }

  private fun imageReference(
    refId: String,
    relativePath: String,
    sha256: String,
    caption: String? = "Wireframe",
    sourceLabel: String? = "user_sent",
    createdAtEpochMs: Long = 42L,
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
    summary = "A two-column landing-page wireframe.",
    sourceLabel = sourceLabel,
    sourceSessionId = "session-1",
    sourceMessageId = "message-1",
    createdAtEpochMs = createdAtEpochMs,
  )
}
