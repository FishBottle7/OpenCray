package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsImageAssetImportServiceTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun importPersistsAssetsAndCleansTemporaryCandidates() {
    lateinit var firstCandidate: Path
    lateinit var secondCandidate: Path
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-import-service-assets"),
      nowEpochMs = { 123L },
    )
    val service = AppSettingsImageAssetImportService(
      candidateImporter = AppSettingsImageCandidateImporter {
        firstCandidate = writeCandidate("first.png", byteArrayOf(1, 2, 3, 4))
        secondCandidate = writeCandidate("second.png", byteArrayOf(1, 2, 3, 4))
        listOf(
          AppImportedSettingsImageCandidate(
            path = firstCandidate,
            displayName = "first.png",
            mimeType = "image/png",
          ),
          AppImportedSettingsImageCandidate(
            path = secondCandidate,
            displayName = "second.png",
            mimeType = "image/png",
          ),
        )
      },
      assetStore = store,
    )

    val imported = service.import(listOf("uri-1", "uri-2"))

    assertEquals(1, imported.size)
    assertEquals(imported.single().assetId, store.list().single().assetId)
    assertFalse(Files.exists(firstCandidate))
    assertFalse(Files.exists(secondCandidate))
  }

  @Test
  fun importSkipsUnsupportedCandidatesButStillCleansThemUp() {
    lateinit var textCandidate: Path
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-import-service-unsupported"),
      nowEpochMs = { 123L },
    )
    val service = AppSettingsImageAssetImportService(
      candidateImporter = AppSettingsImageCandidateImporter {
        textCandidate = writeCandidate("notes.txt", "not-an-image".toByteArray())
        listOf(
          AppImportedSettingsImageCandidate(
            path = textCandidate,
            displayName = "notes.txt",
            mimeType = "text/plain",
          ),
        )
      },
      assetStore = store,
    )

    val imported = service.import(listOf("uri-1"))

    assertEquals(emptyList<AppSettingsImageAsset>(), imported)
    assertEquals(emptyList<AppSettingsImageAsset>(), store.list())
    assertFalse(Files.exists(textCandidate))
  }

  @Test
  fun importReturnsPersistedAssetMetadata() {
    val store = AppSettingsImageAssetStore(
      directory = temporaryFolder.newFolder("settings-import-service-metadata"),
      nowEpochMs = { 456L },
    )
    val service = AppSettingsImageAssetImportService(
      candidateImporter = AppSettingsImageCandidateImporter {
        listOf(
          AppImportedSettingsImageCandidate(
            path = writeCandidate("portrait.png", byteArrayOf(9, 8, 7, 6)),
            displayName = "portrait.png",
            mimeType = "image/png",
          ),
        )
      },
      assetStore = store,
    )

    val imported = service.import(listOf("uri-1"))

    assertEquals(1, imported.size)
    assertNotNull(store.resolveImageHandle(imported.single().assetId))
    assertEquals(456L, imported.single().createdAtEpochMs)
  }

  private fun writeCandidate(
    fileName: String,
    bytes: ByteArray,
  ): Path = temporaryFolder.newFolder("settings-import-candidate-${System.nanoTime()}")
    .toPath()
    .resolve(fileName)
    .also { path ->
      Files.write(path, bytes)
    }
}
