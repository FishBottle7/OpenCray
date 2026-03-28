package com.opencray.persistence.store.file

import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryDurableTextStorageTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun writeTextOnWindowsDoesNotReuseFixedSiblingTmpFileName() {
    assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
    val directory = temporaryFolder.newFolder("durable-text-storage")
    val storage = DirectoryDurableTextStorage(directory)
    val legacyTmpFile = File(directory, "chat-workspace.json.tmp")

    FileOutputStream(legacyTmpFile).use { stream ->
      storage.writeText("chat-workspace.json", """{"ok":true}""")
      stream.write(byteArrayOf(1))
      stream.flush()
    }

    assertEquals(
      """{"ok":true}""",
      File(directory, "chat-workspace.json").readText(Charsets.UTF_8),
    )
  }
}
