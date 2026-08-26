package com.opencray.app

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class AppAgentWorkspaceExportGuardTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private val outsideWorkspaceMessage = "Only items inside the current workspace."
  private val symbolicLinkMessage = "Symbolic links cannot be exported."

  private fun newWorkspace(): Path = temporaryFolder.newFolder("workspace").toPath()

  private fun newCacheRoot(): Path = temporaryFolder.newFolder().toPath()

  private fun writeString(path: Path, content: String): Path {
    Files.createDirectories(path.parent)
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
    return path
  }

  private fun readString(path: Path): String =
    String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  @Test
  fun `resolveEntry rejects parent traversal escape`() {
    val guard = AppAgentWorkspaceExportGuard.create(newWorkspace())
    val exception = assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("../secret.txt", outsideWorkspaceMessage, symbolicLinkMessage)
    }
    assertEquals(outsideWorkspaceMessage, exception.message)
  }

  @Test
  fun `resolveEntry rejects nested traversal escape even when intermediate stays inside`() {
    val workspace = newWorkspace()
    writeString(workspace.resolve("docs/deep/notes.md"), "legal")
    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val exception = assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("docs/deep/../../escape.txt", outsideWorkspaceMessage, symbolicLinkMessage)
    }
    assertEquals(outsideWorkspaceMessage, exception.message)
  }

  @Test
  fun `resolveEntry rejects absolute style escape after prefix strip`() {
    val guard = AppAgentWorkspaceExportGuard.create(newWorkspace())
    assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("/../../../../etc/passwd", outsideWorkspaceMessage, symbolicLinkMessage)
    }
  }

  @Test
  fun `legal deep path resolves and stages into dedicated open and share directories`() {
    val workspace = newWorkspace()
    val cacheRoot = newCacheRoot()
    val content = "deep legal content"
    writeString(workspace.resolve("docs/deep/notes.md"), content)
    val guard = AppAgentWorkspaceExportGuard.create(workspace)

    val source = guard.resolveEntry(
      relativePath = "docs/deep/notes.md",
      outsideWorkspaceMessage = outsideWorkspaceMessage,
      symbolicLinkMessage = symbolicLinkMessage,
    )
    assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
    assertEquals(
      content,
      guard.copyFileIntoStagingAndRead(source),
    )

    listOf("workspace-open", "workspace-share").forEach { stagingName ->
      val stagingDirectory = cacheRoot.resolve(stagingName).normalize()
      Files.createDirectories(stagingDirectory)
      val destination = stagingDirectory.resolve("notes.md")
      assertFalse(Files.exists(destination))
      guard.copyFileIntoStaging(source, destination, outsideWorkspaceMessage)
      assertTrue(destination.startsWith(stagingDirectory))
      assertEquals(content, readString(destination))
    }
  }

  @Test
  fun `directory escape link pointing at app private files is rejected`() {
    val workspace = newWorkspace()
    val privateAppDir = temporaryFolder.newFolder("app-private").toPath()
    val secret = writeString(privateAppDir.resolve("secret.txt"), "app private")
    val linkKind = createDirectoryLink(workspace.resolve("leak"), privateAppDir)
    assumeTrue("No symbolic link or junction support on this machine.", linkKind != LinkKind.NONE)

    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val entryException = assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("leak/secret.txt", outsideWorkspaceMessage, symbolicLinkMessage)
    }
    if (linkKind == LinkKind.SYMLINK) {
      assertEquals(symbolicLinkMessage, entryException.message)
    }
    assertEquals("app private", readString(secret))
    assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("leak", outsideWorkspaceMessage, symbolicLinkMessage)
    }
  }

  @Test
  fun `direct file symbolic link to app private file is rejected`() {
    val workspace = newWorkspace()
    val privateAppDir = temporaryFolder.newFolder("app-private-file").toPath()
    writeString(privateAppDir.resolve("secret.txt"), "app private file")
    val link = workspace.resolve("secret-link.txt")
    val created = runCatching {
      Files.createSymbolicLink(link, privateAppDir.resolve("secret.txt"))
    }.isSuccess
    assumeTrue("File symbolic links unavailable on this machine.", created)

    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val exception = assertThrows(IllegalArgumentException::class.java) {
      guard.resolveEntry("secret-link.txt", outsideWorkspaceMessage, symbolicLinkMessage)
    }
    assertEquals(symbolicLinkMessage, exception.message)
  }

  @Test
  fun `copy into staging fails closed when leaf is swapped to a symbolic link`() {
    val workspace = newWorkspace()
    val privateAppDir = temporaryFolder.newFolder("app-private-race").toPath()
    writeString(privateAppDir.resolve("secret.txt"), "should not leak")
    val link = workspace.resolve("swap-link.txt")
    val created = runCatching {
      Files.createSymbolicLink(link, privateAppDir.resolve("secret.txt"))
    }.isSuccess
    assumeTrue("File symbolic links unavailable on this machine.", created)

    val cacheRoot = newCacheRoot()
    val stagingDirectory = cacheRoot.resolve("workspace-open").normalize()
    Files.createDirectories(stagingDirectory)
    val destination = stagingDirectory.resolve("swap-link.txt")

    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val failed = runCatching {
      guard.copyFileIntoStaging(link, destination, outsideWorkspaceMessage)
    }.isFailure
    assertTrue(failed)
    assertFalse(Files.exists(destination))
  }

  @Test
  fun `tree containing symbolic link is rejected before archiving`() {
    val workspace = newWorkspace()
    val privateAppDir = temporaryFolder.newFolder("app-private-tree").toPath()
    writeString(privateAppDir.resolve("secret.txt"), "tree leak")
    writeString(workspace.resolve("bundle/a/b.txt"), "normal file")
    val created = runCatching {
      Files.createSymbolicLink(
        workspace.resolve("bundle/a/link.txt"),
        privateAppDir.resolve("secret.txt"),
      )
    }.isSuccess
    assumeTrue("File symbolic links unavailable on this machine.", created)

    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val bundle = workspace.resolve("bundle")
    val exception = assertThrows(IllegalArgumentException::class.java) {
      guard.ensureTreeHasNoSymbolicLinks(bundle, symbolicLinkMessage)
    }
    assertEquals(symbolicLinkMessage, exception.message)
    assertThrows(IllegalArgumentException::class.java) {
      archiveToBytes(guard, bundle, "bundle")
    }
  }

  @Test
  fun `archive of legal tree writes expected entries`() {
    val workspace = newWorkspace()
    writeString(workspace.resolve("bundle/a/b.txt"), "zip me")
    val guard = AppAgentWorkspaceExportGuard.create(workspace)
    val bundle = workspace.resolve("bundle")

    val archiveBytes = archiveToBytes(guard, bundle, "bundle")
    val archiveFile = Files.createTempFile("opencray-guard-test", ".zip")
    try {
      Files.write(archiveFile, archiveBytes)
      ZipFile(archiveFile.toFile()).use { zip ->
        val entries = Collections.list(zip.entries()).associate { entry: ZipEntry ->
          entry.name to zip.getInputStream(entry).readBytes()
        }
        assertEquals(
          setOf("bundle/", "bundle/a/", "bundle/a/b.txt"),
          entries.keys,
        )
        assertEquals("zip me", String(entries.getValue("bundle/a/b.txt"), StandardCharsets.UTF_8))
      }
    } finally {
      Files.deleteIfExists(archiveFile)
    }
  }

  @Test
  fun `file provider paths are narrowed to dedicated staging directories`() {
    val factory = DocumentBuilderFactory.newInstance().apply {
      isNamespaceAware = true
    }
    val document = factory.newDocumentBuilder()
      .parse(locateResource("src/main/res/xml/opencray_file_provider_paths.xml").toFile())
    val androidNs = "http://schemas.android.com/apk/res/android"
    val nodes = document.getElementsByTagName("cache-path")
    val entries = buildMap {
      for (index in 0 until nodes.length) {
        val element = nodes.item(index) as Element
        put(element.getAttributeNS(androidNs, "name"), element.getAttributeNS(androidNs, "path"))
      }
    }
    assertEquals("workspace-open/", entries.getValue("workspace_open"))
    assertEquals("workspace-share/", entries.getValue("workspace_share"))
    assertFalse(entries.containsKey("opencray_cache"))
    assertFalse(entries.containsValue("."))
  }

  private fun archiveToBytes(
    guard: AppAgentWorkspaceExportGuard,
    source: Path,
    rootName: String,
  ): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    ZipOutputStream(buffer).use { output ->
      guard.writeSourceToZip(output, source, rootName, outsideWorkspaceMessage)
    }
    return buffer.toByteArray()
  }

  private fun locateResource(relative: String): Path {
    var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    repeat(6) {
      val moduleCandidate = current.resolve(relative)
      val appCandidate = current.resolve("app").resolve(relative)
      when {
        Files.exists(moduleCandidate) -> return moduleCandidate
        Files.exists(appCandidate) -> return appCandidate
      }
      current = current.parent ?: return@repeat
    }
    throw AssertionError("Unable to locate $relative from ${System.getProperty("user.dir")}")
  }

  private enum class LinkKind {
    SYMLINK,
    JUNCTION,
    NONE,
  }

  private fun createDirectoryLink(
    link: Path,
    target: Path,
  ): LinkKind {
    try {
      Files.createSymbolicLink(link, target)
      return LinkKind.SYMLINK
    } catch (_: UnsupportedOperationException) {
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
    val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    if (isWindows) {
      val process = ProcessBuilder(
        "cmd",
        "/c",
        "mklink",
        "/J",
        link.toString(),
        target.toString(),
      ).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      if (exitCode == 0 && Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS)) {
        return LinkKind.JUNCTION
      }
      println("mklink /J failed ($exitCode): ${output.trim()}")
    }
    return LinkKind.NONE
  }

  private fun AppAgentWorkspaceExportGuard.copyFileIntoStagingAndRead(source: Path): String {
    val stagingDirectory = newCacheRoot().resolve("workspace-open-readback").normalize()
    Files.createDirectories(stagingDirectory)
    val destination = stagingDirectory.resolve("readback.md")
    copyFileIntoStaging(source, destination, outsideWorkspaceMessage)
    return readString(destination)
  }
}
