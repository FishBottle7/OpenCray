package com.opencray.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePackageSupportTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val provider = DefaultWorkspacePackageProvider()

  @Test
  fun workspacePackageKindForDetectsDocxPackage() {
    val packagePath = createZipArchive(
      relativePath = "docs/report.docx",
      entries = mapOf(
        "[Content_Types].xml" to "<Types/>".toByteArray(StandardCharsets.UTF_8),
        "_rels/.rels" to "<Relationships/>".toByteArray(StandardCharsets.UTF_8),
        "word/document.xml" to "<document/>".toByteArray(StandardCharsets.UTF_8),
      ),
    )

    assertEquals(WorkspacePackageKind.DOCX, workspacePackageKindFor(packagePath))
  }

  @Test
  fun inspectFiltersEntriesAndPreviewsRequestedXmlParts() {
    val packagePath = createZipArchive(
      relativePath = "docs/report.docx",
      entries = mapOf(
        "[Content_Types].xml" to "<Types/>".toByteArray(StandardCharsets.UTF_8),
        "_rels/.rels" to "<Relationships/>".toByteArray(StandardCharsets.UTF_8),
        "word/document.xml" to "<document><p>Hello package</p></document>".toByteArray(StandardCharsets.UTF_8),
        "word/styles.xml" to "<styles/>".toByteArray(StandardCharsets.UTF_8),
        "word/media/image1.png" to byteArrayOf(1, 2, 3, 4),
      ),
    )

    val result = provider.inspect(
      path = packagePath,
      request = WorkspacePackageInspectionRequest(
        glob = "word/**/*.xml",
        maxEntries = 10,
        previewEntries = listOf("word/document.xml"),
        previewChars = 80,
      ),
    )

    assertEquals(WorkspacePackageKind.DOCX, result.packageKind)
    assertEquals(5, result.entryCount)
    assertEquals(2, result.matchedEntryCount)
    assertEquals(listOf("word/document.xml", "word/styles.xml"), result.entries.map(WorkspacePackageEntry::path))
    assertEquals(1, result.previews.size)
    assertTrue(result.previews.single().content.contains("Hello package"))
    assertEquals(listOf("word/document.xml"), result.mainPartHints)
    assertEquals(listOf("_rels/.rels", "word/_rels/document.xml.rels"), result.relationshipPartHints)
    assertEquals(1, result.mediaEntryCount)
  }

  @Test
  fun extractWritesSelectedEntriesIntoDestinationDirectory() {
    val packagePath = createZipArchive(
      relativePath = "docs/report.docx",
      entries = mapOf(
        "[Content_Types].xml" to "<Types/>".toByteArray(StandardCharsets.UTF_8),
        "word/document.xml" to "<document><p>Hello package</p></document>".toByteArray(StandardCharsets.UTF_8),
        "word/media/image1.png" to byteArrayOf(7, 8, 9, 10),
      ),
    )
    val destinationRoot = temporaryFolder.newFolder("workspace-package-extract").toPath()

    val result = provider.extract(
      path = packagePath,
      request = WorkspacePackageExtractionRequest(
        destinationRoot = destinationRoot,
        glob = "word/**/*.xml",
      ),
    )

    val extractedDocument = destinationRoot.resolve("word").resolve("document.xml")
    assertEquals(WorkspacePackageKind.DOCX, result.packageKind)
    assertEquals(1, result.matchedEntryCount)
    assertTrue(Files.exists(extractedDocument))
    assertEquals(
      "<document><p>Hello package</p></document>",
      Files.readAllBytes(extractedDocument).toString(StandardCharsets.UTF_8),
    )
  }

  @Test
  fun extractRejectsZipSlipPaths() {
    val packagePath = createZipArchive(
      relativePath = "archives/malicious.zip",
      entries = mapOf(
        "../evil.txt" to "nope".toByteArray(StandardCharsets.UTF_8),
      ),
    )
    val destinationRoot = temporaryFolder.newFolder("workspace-package-zip-slip").toPath()

    try {
      provider.extract(
        path = packagePath,
        request = WorkspacePackageExtractionRequest(
          destinationRoot = destinationRoot,
          entries = listOf("../evil.txt"),
        ),
      )
      fail("Expected zip slip extraction to be rejected.")
    } catch (error: IllegalArgumentException) {
      assertTrue(error.message.orEmpty().contains("escape the requested destination directory"))
    }
  }

  private fun createZipArchive(
    relativePath: String,
    entries: Map<String, ByteArray>,
  ): Path {
    val outputPath = temporaryFolder.root.toPath().resolve(relativePath)
    Files.createDirectories(outputPath.parent)
    ZipOutputStream(Files.newOutputStream(outputPath)).use { zip ->
      entries.forEach { (entryPath, bytes) ->
        zip.putNextEntry(ZipEntry(entryPath))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return outputPath
  }
}
