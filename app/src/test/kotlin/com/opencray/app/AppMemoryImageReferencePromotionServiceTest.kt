package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.persistence.store.MemoryStore
import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import com.opencray.runtime.memory.MemoryImageReferenceSupport
import com.opencray.runtime.memory.MemoryRecordExtensionKeys
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppMemoryImageReferencePromotionServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun attachSourcePromotesAndPersistsMemoryImageReference() {
    val workspaceRoot = temporaryFolder.newFolder("memory-image-promotion-workspace").toPath()
    val privateRoot = temporaryFolder.newFolder("memory-image-promotion-private").toPath()
    val sourcePath = writeFile(
      root = temporaryFolder.newFolder("memory-image-promotion-source").toPath(),
      relativePath = "chat/evidence.png",
    )
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(memoryRecord(id = "memory-1"))
    }
    val service = AppMemoryImageReferencePromotionService(
      promotionService = AppImageReferencePromotionService(
        privateRoot = privateRoot,
        workspaceRoot = workspaceRoot,
        sourceResolver = FakeSourceResolver(
          expectedSource = imageSource("chat-image-1"),
          resolvedPath = sourcePath,
        ),
        dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 640, heightPx = 480) },
        summaryExtractor = AppImageSummaryExtractor {
          AppImageSummary(
            caption = "Whiteboard photo",
            summary = "A whiteboard photo with a two-column plan sketch.",
          )
        },
        clock = { 321L },
      ),
      memoryImageReferenceService = AppMemoryImageReferenceService(
        memoryStore = memoryStore,
        clock = { 500L },
      ),
    )

    val updated = service.attachSource(
      memoryId = "memory-1",
      source = imageSource("chat-image-1"),
    )

    assertNotNull(updated)
    requireNotNull(updated)
    assertEquals(1, MemoryImageReferenceSupport.decodeFromExtensions(updated.extensions).size)
    assertEquals(2L, updated.recordVersion)
  }

  @Test
  fun attachSourceDeletesFreshCopyWhenMemoryRecordDoesNotExist() {
    val workspaceRoot = temporaryFolder.newFolder("memory-image-missing-workspace").toPath()
    val privateRoot = temporaryFolder.newFolder("memory-image-missing-private").toPath()
    val sourcePath = writeFile(
      root = temporaryFolder.newFolder("memory-image-missing-source").toPath(),
      relativePath = "chat/evidence.png",
    )
    val service = AppMemoryImageReferencePromotionService(
      promotionService = AppImageReferencePromotionService(
        privateRoot = privateRoot,
        workspaceRoot = workspaceRoot,
        sourceResolver = FakeSourceResolver(
          expectedSource = imageSource("chat-image-missing"),
          resolvedPath = sourcePath,
        ),
        dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 640, heightPx = 480) },
        summaryExtractor = AppImageSummaryExtractor {
          AppImageSummary(
            caption = "Whiteboard photo",
            summary = "A whiteboard photo with a two-column plan sketch.",
          )
        },
      ),
      memoryImageReferenceService = AppMemoryImageReferenceService(
        memoryStore = InMemoryMemoryStore(),
      ),
    )

    val updated = service.attachSource(
      memoryId = "missing-memory",
      source = imageSource("chat-image-missing"),
    )

    assertNull(updated)
    val memoryMediaRoot = privateRoot.resolve("memory-media").resolve("missing-memory")
    if (Files.exists(memoryMediaRoot)) {
      Files.list(memoryMediaRoot).use { stream ->
        assertFalse(stream.findAny().isPresent)
      }
    }
  }

  @Test
  fun attachSourceDeletesDuplicateFreshCopyWhenExistingRecordAlreadyKeepsSameImage() {
    val workspaceRoot = temporaryFolder.newFolder("memory-image-duplicate-workspace").toPath()
    val privateRoot = temporaryFolder.newFolder("memory-image-duplicate-private").toPath()
    val sourcePath = writeFile(
      root = temporaryFolder.newFolder("memory-image-duplicate-source").toPath(),
      relativePath = "chat/evidence.png",
    )
    val duplicateSha = sha256Hex(sourcePath)
    val memoryStore = InMemoryMemoryStore().apply {
      upsert(
        memoryRecord(
          id = "memory-duplicate",
          extensions = MemoryImageReferenceSupport.encodeIntoExtensions(
            extensions = baseExtensions(),
            imageReferences = listOf(
              imageReference(
                refId = "memory-img-existing",
                relativePath = "memory-media/memory-duplicate/original.png",
                sha256 = duplicateSha,
              ),
            ),
          ),
          recordVersion = 4L,
          updatedAtEpochMs = 400L,
        ),
      )
    }
    val service = AppMemoryImageReferencePromotionService(
      promotionService = AppImageReferencePromotionService(
        privateRoot = privateRoot,
        workspaceRoot = workspaceRoot,
        sourceResolver = FakeSourceResolver(
          expectedSource = imageSource("chat-image-duplicate", displayName = "duplicate-name.png"),
          resolvedPath = sourcePath,
        ),
        dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 640, heightPx = 480) },
        summaryExtractor = AppImageSummaryExtractor {
          AppImageSummary(
            caption = "Whiteboard photo",
            summary = "A whiteboard photo with a two-column plan sketch.",
          )
        },
      ),
      memoryImageReferenceService = AppMemoryImageReferenceService(
        memoryStore = memoryStore,
        clock = { 900L },
      ),
    )

    val updated = service.attachSource(
      memoryId = "memory-duplicate",
      source = imageSource("chat-image-duplicate", displayName = "duplicate-name.png"),
    )

    requireNotNull(updated)
    assertEquals(4L, updated.recordVersion)
    assertEquals(1, MemoryImageReferenceSupport.decodeFromExtensions(updated.extensions).size)
    val copiedAsset = privateRoot
      .resolve("memory-media")
      .resolve("memory-duplicate")
      .resolve("${duplicateSha.take(12)}-duplicate-name.png")
    assertFalse(Files.exists(copiedAsset))
  }

  private fun memoryRecord(
    id: String,
    extensions: Map<String, String> = baseExtensions(),
    recordVersion: Long = 1L,
    updatedAtEpochMs: Long = 100L,
  ): MemoryRecord = MemoryRecord(
    id = id,
    content = "Prototype planning evidence.",
    tags = listOf("kind:project_fact", "scope:workspace"),
    createdAtEpochMs = 50L,
    updatedAtEpochMs = updatedAtEpochMs,
    recordVersion = recordVersion,
    extensions = extensions,
  )

  private fun baseExtensions(): Map<String, String> = mapOf(
    MemoryRecordExtensionKeys.KIND to "project_fact",
    MemoryRecordExtensionKeys.SCOPE to "workspace",
  )

  private fun imageSource(
    chatAttachmentId: String,
    displayName: String = "evidence.png",
  ): OpenCrayImageReferenceSource = OpenCrayImageReferenceSource(
    sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
    chatAttachmentId = chatAttachmentId,
    displayName = displayName,
    mimeType = "image/png",
    sourceSessionId = "session-1",
    sourceMessageId = "message-1",
  )

  private fun imageReference(
    refId: String,
    relativePath: String,
    sha256: String,
  ): OpenCrayImageReference = OpenCrayImageReference(
    refId = refId,
    role = OpenCrayImageReferenceRole.EVIDENCE,
    storageScope = OpenCrayImageReferenceStorageScope.AGENT_PRIVATE,
    relativePath = relativePath,
    mimeType = "image/png",
    sha256 = sha256,
    widthPx = 640,
    heightPx = 480,
    caption = "Existing evidence",
    summary = "A durable existing image reference.",
    sourceLabel = "user_sent",
    sourceSessionId = "session-1",
    sourceMessageId = "message-1",
    createdAtEpochMs = 42L,
  )

  private fun writeFile(
    root: Path,
    relativePath: String,
  ): Path {
    val target = root.resolve(relativePath).normalize()
    Files.createDirectories(requireNotNull(target.parent))
    Files.write(target, byteArrayOf(1, 2, 3, 4))
    return target
  }

  private fun sha256Hex(path: Path): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          digest.update(buffer, 0, read)
        }
      }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private class FakeSourceResolver(
    private val expectedSource: OpenCrayImageReferenceSource,
    private val resolvedPath: Path,
  ) : AppImageReferenceSourceResolver {
    override fun resolve(source: OpenCrayImageReferenceSource): ResolvedAppImageReferenceSource? {
      if (source != expectedSource) {
        return null
      }
      return ResolvedAppImageReferenceSource(
        source = source,
        path = resolvedPath,
        displayName = source.displayName,
        mimeType = source.mimeType,
        sourceSessionId = source.sourceSessionId,
        sourceMessageId = source.sourceMessageId,
      )
    }
  }

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
