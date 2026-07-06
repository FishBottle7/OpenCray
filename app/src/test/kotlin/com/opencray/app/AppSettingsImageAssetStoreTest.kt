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
  fun importImageMergesCurrentDurableIndexWhenStoreCacheIsStale() {
    val directory = temporaryFolder.newFolder("settings-image-assets-merge")
    val sourceRoot = temporaryFolder.newFolder("settings-source-merge").toPath()
    val firstStore = AppSettingsImageAssetStore(
      directory = directory,
      nowEpochMs = { 100L },
    )
    val secondStore = AppSettingsImageAssetStore(
      directory = directory,
      nowEpochMs = { 200L },
    )
    val firstPath = writeFile(sourceRoot, "first.png", byteArrayOf(1))
    val secondPath = writeFile(sourceRoot, "second.png", byteArrayOf(2))
    val thirdPath = writeFile(sourceRoot, "third.png", byteArrayOf(3))

    assertNotNull(
      firstStore.importImage(
        sourcePath = firstPath,
        displayName = "first.png",
        mimeType = "image/png",
      ),
    )
    assertNotNull(
      secondStore.importImage(
        sourcePath = secondPath,
        displayName = "second.png",
        mimeType = "image/png",
      ),
    )
    assertNotNull(
      firstStore.importImage(
        sourcePath = thirdPath,
        displayName = "third.png",
        mimeType = "image/png",
      ),
    )

    val reloadedStore = AppSettingsImageAssetStore(directory = directory)
    assertEquals(
      setOf("first.png", "second.png", "third.png"),
      reloadedStore.list().map(AppSettingsImageAsset::displayName).toSet(),
    )
  }

  @Test
  fun listRefreshesDurableIndexAcrossStoreInstances() {
    val directory = temporaryFolder.newFolder("settings-image-assets-refresh")
    val sourceRoot = temporaryFolder.newFolder("settings-source-refresh").toPath()
    val firstStore = AppSettingsImageAssetStore(directory = directory)
    val secondStore = AppSettingsImageAssetStore(directory = directory)

    assertTrue(firstStore.list().isEmpty())
    assertNotNull(
      secondStore.importImage(
        sourcePath = writeFile(sourceRoot, "later.png", byteArrayOf(9)),
        displayName = "later.png",
        mimeType = "image/png",
      ),
    )

    assertEquals(
      listOf("later.png"),
      firstStore.list().map(AppSettingsImageAsset::displayName),
    )
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
    bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
  ): Path {
    val target = root.resolve(relativePath).normalize()
    Files.createDirectories(requireNotNull(target.parent))
    Files.write(target, bytes)
    return target
  }
}
