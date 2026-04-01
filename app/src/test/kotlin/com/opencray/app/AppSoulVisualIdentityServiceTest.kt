package com.opencray.app

import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSoulVisualIdentityServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun savePrimaryPortraitPersistsVisualIdentityIntoSoulDocument() {
    val workspaceRoot = temporaryFolder.newFolder("soul-visual-identity-workspace").toPath()
    val privateRoot = temporaryFolder.newFolder("soul-visual-identity-private").toPath()
    val sourceFile = writeFile(
      root = temporaryFolder.newFolder("soul-visual-identity-source").toPath(),
      relativePath = "settings/avatar.png",
    )
    val source = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
      settingsAssetId = "settings-avatar-1",
      displayName = "avatar.png",
      mimeType = "image/png",
    )
    val service = AppSoulVisualIdentityService(
      soulProfileStore = WorkspaceSoulProfileStore(),
      promotionService = AppImageReferencePromotionService(
        privateRoot = privateRoot,
        workspaceRoot = workspaceRoot,
        sourceResolver = SingleSourceResolver(source, sourceFile),
        dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 1024, heightPx = 1024) },
        summaryExtractor = AppImageSummaryExtractor {
          AppImageSummary(
            caption = "Portrait",
            summary = "A front-facing portrait with a calm expression.",
            portraitSummary = "Short dark hair, practical coat, steady gaze.",
          )
        },
        clock = { 321L },
      ),
    )

    val saved = service.savePrimaryPortrait(
      workspaceRoot = workspaceRoot,
      source = source,
    )

    assertNotNull(saved)
    requireNotNull(saved)
    assertEquals("Short dark hair, practical coat, steady gaze.", saved.portraitSummary)
    assertNotNull(saved.primaryPortrait)
    assertTrue(saved.primaryPortrait!!.relativePath.startsWith("soul-assets/portrait/"))

    val persisted = WorkspaceSoulProfileStore().loadSoulVisualIdentity(workspaceRoot)
    assertNotNull(persisted)
    requireNotNull(persisted)
    assertEquals(saved.portraitSummary, persisted.portraitSummary)
    assertEquals(saved.primaryPortrait, persisted.primaryPortrait)
  }

  @Test
  fun saveReferenceImagePreservesPortraitAndDedupesSameContent() {
    val workspaceRoot = temporaryFolder.newFolder("soul-reference-workspace").toPath()
    val privateRoot = temporaryFolder.newFolder("soul-reference-private").toPath()
    val portraitSource = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
      settingsAssetId = "settings-avatar-portrait",
      displayName = "portrait.png",
      mimeType = "image/png",
    )
    val referenceSource = OpenCrayImageReferenceSource(
      sourceKind = OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
      settingsAssetId = "settings-avatar-reference",
      displayName = "reference.png",
      mimeType = "image/png",
    )
    val portraitFile = writeFile(
      root = temporaryFolder.newFolder("soul-reference-portrait-source").toPath(),
      relativePath = "portrait.png",
    )
    val referenceFile = writeFile(
      root = temporaryFolder.newFolder("soul-reference-reference-source").toPath(),
      relativePath = "reference.png",
    )
    val service = AppSoulVisualIdentityService(
      soulProfileStore = WorkspaceSoulProfileStore(),
      promotionService = AppImageReferencePromotionService(
        privateRoot = privateRoot,
        workspaceRoot = workspaceRoot,
        sourceResolver = MultiSourceResolver(
          mapOf(
            portraitSource to portraitFile,
            referenceSource to referenceFile,
          ),
        ),
        dimensionsReader = AppImageDimensionsReader { AppImageDimensions(widthPx = 512, heightPx = 512) },
        summaryExtractor = AppImageSummaryExtractor { request ->
          if (request.source == portraitSource) {
            AppImageSummary(
              caption = "Portrait",
              summary = "Canonical portrait.",
              portraitSummary = "Reserved expression with dark hair.",
            )
          } else {
            AppImageSummary(
              caption = "Reference",
              summary = "A three-quarter reference image.",
            )
          }
        },
        clock = { 555L },
      ),
    )

    service.savePrimaryPortrait(
      workspaceRoot = workspaceRoot,
      source = portraitSource,
    )
    service.saveReferenceImage(
      workspaceRoot = workspaceRoot,
      refId = "look-1",
      source = referenceSource,
    )
    val twice = service.saveReferenceImage(
      workspaceRoot = workspaceRoot,
      refId = "look-1",
      source = referenceSource,
    )

    assertNotNull(twice)
    requireNotNull(twice)
    assertNotNull(twice.primaryPortrait)
    assertEquals(1, twice.referenceImages.size)
    assertEquals("look-1", twice.referenceImages.single().refId)
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

  private class SingleSourceResolver(
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
      )
    }
  }

  private class MultiSourceResolver(
    private val pathsBySource: Map<OpenCrayImageReferenceSource, Path>,
  ) : AppImageReferenceSourceResolver {
    override fun resolve(source: OpenCrayImageReferenceSource): ResolvedAppImageReferenceSource? {
      val resolvedPath = pathsBySource[source] ?: return null
      return ResolvedAppImageReferenceSource(
        source = source,
        path = resolvedPath,
        displayName = source.displayName,
        mimeType = source.mimeType,
      )
    }
  }
}
