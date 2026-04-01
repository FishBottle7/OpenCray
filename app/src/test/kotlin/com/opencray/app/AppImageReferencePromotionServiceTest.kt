package com.opencray.app

import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppImageReferencePromotionServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun promoteForMemoryCopiesChatAttachmentIntoPrivateStore() {
    val workspaceRoot = temporaryFolder.newFolder("promotion-workspace-memory").toPath()
    val privateRoot = temporaryFolder.newFolder("promotion-private-memory").toPath()
    val sourceFile = writeFile(
      root = temporaryFolder.newFolder("promotion-source-memory").toPath(),
      relativePath = "chat/camera-first.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
      chatAttachmentId = "chat-image-1",
      displayName = "camera-first.png",
      mimeType = "image/png",
      sourceSessionId = "session-1",
      sourceMessageId = "message-1",
    )
    val service = AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRoot,
      sourceResolver = FakeSourceResolver(source, sourceFile),
      dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 640, heightPx = 480) },
      summaryExtractor = AppImageSummaryExtractor {
        AppImageSummary(
          caption = "Camera photo",
          summary = "A whiteboard photo with a two-column plan sketch.",
        )
      },
      clock = { 123L },
    )

    val result = service.promoteForMemory(
      memoryId = "memory-1",
      source = source,
    )

    assertNotNull(result)
    requireNotNull(result)
    assertEquals(AppImageReferencePromotionMode.COPY_PROMOTE, result.mode)
    assertEquals(OpenCrayImageReferenceStorageScope.AGENT_PRIVATE, result.reference.storageScope)
    assertTrue(result.reference.relativePath.startsWith("memory-media/memory-1/"))
    assertTrue(result.resolvedAssetPath.startsWith(privateRoot))
    assertTrue(Files.exists(result.resolvedAssetPath))
    assertEquals("A whiteboard photo with a two-column plan sketch.", result.reference.summary)
    assertEquals("session-1", result.reference.sourceSessionId)
    assertEquals("message-1", result.reference.sourceMessageId)
  }

  @Test
  fun promoteForMemoryCanReferenceExistingWorkspaceImage() {
    val workspaceRoot = temporaryFolder.newFolder("promotion-workspace-reference").toPath()
    val privateRoot = temporaryFolder.newFolder("promotion-private-reference").toPath()
    val workspaceImage = writeFile(
      root = workspaceRoot,
      relativePath = "docs/mockups/agent-look.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.WORKSPACE_PATH,
      relativePath = "docs/mockups/agent-look.png",
      displayName = "agent-look.png",
      mimeType = "image/png",
    )
    val service = AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRoot,
      dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 1200, heightPx = 900) },
      summaryExtractor = AppImageSummaryExtractor {
        AppImageSummary(
          caption = "Workspace mockup",
          summary = "A public workspace mockup image.",
        )
      },
      clock = { 456L },
    )

    val result = service.promoteForMemory(
      memoryId = "memory-2",
      source = source,
      preferredMode = AppImageReferencePromotionMode.REFERENCE_PROMOTE,
    )

    assertNotNull(result)
    requireNotNull(result)
    assertEquals(AppImageReferencePromotionMode.REFERENCE_PROMOTE, result.mode)
    assertEquals(OpenCrayImageReferenceStorageScope.WORKSPACE, result.reference.storageScope)
    assertEquals("docs/mockups/agent-look.png", result.reference.relativePath)
    assertEquals(workspaceImage.toAbsolutePath().normalize(), result.resolvedAssetPath)
    assertFalse(Files.exists(privateRoot.resolve("memory-media")))
  }

  @Test
  fun promoteForSoulPrimaryPortraitCopiesSettingsAssetAndCarriesPortraitSummary() {
    val workspaceRoot = temporaryFolder.newFolder("promotion-workspace-settings").toPath()
    val privateRoot = temporaryFolder.newFolder("promotion-private-settings").toPath()
    val sourceFile = writeFile(
      root = temporaryFolder.newFolder("promotion-source-settings").toPath(),
      relativePath = "settings/avatar.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
      settingsAssetId = "settings-avatar-1",
      displayName = "avatar.png",
      mimeType = "image/png",
    )
    val service = AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRoot,
      sourceResolver = FakeSourceResolver(source, sourceFile),
      dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 1024, heightPx = 1024) },
      summaryExtractor = AppImageSummaryExtractor {
        AppImageSummary(
          caption = "Portrait",
          summary = "A front-facing portrait with a calm expression.",
          portraitSummary = "Short dark hair, practical coat, steady gaze.",
        )
      },
      clock = { 789L },
    )

    val result = service.promoteForSoulPrimaryPortrait(source)

    assertNotNull(result)
    requireNotNull(result)
    assertEquals(AppImageReferencePromotionMode.COPY_PROMOTE, result.mode)
    assertTrue(result.reference.relativePath.startsWith("soul-assets/portrait/"))
    assertEquals(OpenCrayImageReferenceStorageScope.AGENT_PRIVATE, result.reference.storageScope)
    assertEquals("Short dark hair, practical coat, steady gaze.", result.portraitSummary)
    assertTrue(Files.exists(result.resolvedAssetPath))
  }

  @Test
  fun promoteForSoulReferenceReusesExistingDurableAsset() {
    val privateRoot = temporaryFolder.newFolder("promotion-private-durable").toPath()
    val workspaceRoot = temporaryFolder.newFolder("promotion-workspace-durable").toPath()
    val durableFile = writeFile(
      root = privateRoot,
      relativePath = "soul-assets/references/look-1/seed.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.DURABLE_ASSET,
      relativePath = "soul-assets/references/look-1/seed.png",
      displayName = "seed.png",
      mimeType = "image/png",
    )
    val service = AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRoot,
      dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 512, heightPx = 512) },
      summaryExtractor = AppImageSummaryExtractor {
        AppImageSummary(
          caption = "Reference",
          summary = "A durable existing private reference image.",
        )
      },
      clock = { 999L },
    )

    val result = service.promoteForSoulReference(
      refId = "look-1",
      source = source,
    )

    assertNotNull(result)
    requireNotNull(result)
    assertEquals(AppImageReferencePromotionMode.REFERENCE_PROMOTE, result.mode)
    assertTrue(result.reusedExistingAsset)
    assertEquals(durableFile.toAbsolutePath().normalize(), result.resolvedAssetPath)
    assertEquals("soul-assets/references/look-1/seed.png", result.reference.relativePath)
  }

  @Test
  fun promoteDeletesFreshPrivateCopyWhenSummaryExtractionFails() {
    val workspaceRoot = temporaryFolder.newFolder("promotion-workspace-cleanup").toPath()
    val privateRoot = temporaryFolder.newFolder("promotion-private-cleanup").toPath()
    val sourceFile = writeFile(
      root = temporaryFolder.newFolder("promotion-source-cleanup").toPath(),
      relativePath = "chat/cleanup.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT,
      chatAttachmentId = "chat-image-cleanup",
      displayName = "cleanup.png",
      mimeType = "image/png",
    )
    val service = AppImageReferencePromotionService(
      privateRoot = privateRoot,
      workspaceRoot = workspaceRoot,
      sourceResolver = FakeSourceResolver(source, sourceFile),
      dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 256, heightPx = 256) },
      summaryExtractor = AppImageSummaryExtractor { null },
    )

    val result = service.promoteForMemory(
      memoryId = "memory-cleanup",
      source = source,
    )

    assertEquals(null, result)
    val memoryMediaRoot = privateRoot.resolve("memory-media").resolve("memory-cleanup")
    if (Files.exists(memoryMediaRoot)) {
      Files.list(memoryMediaRoot).use { stream ->
        assertFalse(stream.findAny().isPresent)
      }
    }
  }

  private fun writeFile(
    root: Path,
    relativePath: String,
  ): Path {
    val target = root.resolve(relativePath).normalize()
    Files.createDirectories(requireNotNull(target.parent))
    Files.write(target, byteArrayOf(1, 2, 3, 4))
    return target
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
}
