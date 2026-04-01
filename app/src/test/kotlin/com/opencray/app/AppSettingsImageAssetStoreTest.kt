package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsImageAssetStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun importImageCopiesIntoHostOwnedStoreAndResolvesByStableId() {
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-image-assets"),
      nowEpochMs = { 123L },
    )
    val sourcePath = writeFile(
      root = temporaryFolder.newFolder("settings-source").toPath(),
      relativePath = "avatar.png",
    )

    val imported = store.importImage(
      sourcePath = sourcePath,
      displayName = "avatar.png",
      mimeType = "image/png",
    )

    assertNotNull(imported)
    requireNotNull(imported)
    assertEquals("settings-${imported.sha256.take(12)}", imported.assetId)
    assertTrue(imported.relativePath.startsWith("assets/${imported.assetId}/"))
    assertEquals(123L, imported.createdAtEpochMs)

    val resolved = store.resolveImageHandle(imported.assetId)
    assertNotNull(resolved)
    requireNotNull(resolved)
    assertEquals("avatar.png", resolved.displayName)
    assertEquals("image/png", resolved.mimeType)
    assertTrue(Files.exists(resolved.path))
  }

  @Test
  fun importImageDedupesByContentAcrossReimports() {
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-image-assets-dedupe"),
    )
    val sourceRoot = temporaryFolder.newFolder("settings-source-dedupe").toPath()
    val firstPath = writeFile(sourceRoot, "first/avatar.png")
    val secondPath = writeFile(sourceRoot, "second/avatar-copy.png")

    val first = store.importImage(
      sourcePath = firstPath,
      displayName = "avatar.png",
      mimeType = "image/png",
    )
    val second = store.importImage(
      sourcePath = secondPath,
      displayName = "avatar-copy.png",
      mimeType = "image/png",
    )

    assertNotNull(first)
    assertNotNull(second)
    requireNotNull(first)
    requireNotNull(second)
    assertEquals(first.assetId, second.assetId)
    assertEquals(first.relativePath, second.relativePath)
    assertEquals(1, store.list().size)
  }

  @Test
  fun importImageRejectsUnsupportedNonImageFiles() {
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-image-assets-non-image"),
    )
    val sourcePath = writeFile(
      root = temporaryFolder.newFolder("settings-source-non-image").toPath(),
      relativePath = "notes.txt",
    )

    val imported = store.importImage(
      sourcePath = sourcePath,
      displayName = "notes.txt",
      mimeType = "text/plain",
    )

    assertNull(imported)
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
}
