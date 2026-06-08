package com.opencray.persistence.store.file

import com.opencray.persistence.store.DurableTextUpdate
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test
  fun readWriteAndDeleteUseSeparateLockSidecar() {
    val directory = temporaryFolder.newFolder("durable-text-storage-lock")
    val storage = DirectoryDurableTextStorage(directory)

    storage.writeText("runtime-runs.json", """{"runs":[]}""")
    File(directory, "runtime-runs.json.lock").writeText("held previously", Charsets.UTF_8)

    assertTrue(File(directory, "runtime-runs.json.lock").exists())
    assertEquals("""{"runs":[]}""", storage.readText("runtime-runs.json"))
    assertTrue(storage.delete("runtime-runs.json"))
    assertFalse(File(directory, "runtime-runs.json").exists())
    assertTrue(File(directory, "runtime-runs.json.lock").exists())
  }

  @Test
  fun readAndDeleteMissingDirectoryDoNotCreateLockSidecar() {
    val directory = File(temporaryFolder.root, "missing-durable-text-storage")
    val storage = DirectoryDurableTextStorage(directory)

    assertNull(storage.readText("runtime-runs.json"))
    assertFalse(storage.delete("runtime-runs.json"))
    assertFalse(directory.exists())
  }

  @Test
  fun updateTextReadsAndWritesUnderLockSidecar() {
    val directory = temporaryFolder.newFolder("durable-text-storage-update")
    val storage = DirectoryDurableTextStorage(directory)

    storage.writeText("runtime-runs.json", """{"runs":["one"]}""")
    val result = storage.updateText("runtime-runs.json") { current ->
      DurableTextUpdate(
        text = current?.replace("one", "two"),
        result = current,
      )
    }

    assertEquals("""{"runs":["one"]}""", result)
    assertEquals("""{"runs":["two"]}""", storage.readText("runtime-runs.json"))
    assertTrue(File(directory, "runtime-runs.json.lock").exists())
  }

  @Test
  fun updateTextNoOpDoesNotCreatePayloadFile() {
    val directory = temporaryFolder.newFolder("durable-text-storage-update-noop")
    val storage = DirectoryDurableTextStorage(directory)

    val result = storage.updateText("runtime-runs.json") { current ->
      DurableTextUpdate(
        text = current,
        result = false,
        write = false,
      )
    }

    assertFalse(result)
    assertFalse(File(directory, "runtime-runs.json").exists())
    assertTrue(File(directory, "runtime-runs.json.lock").exists())
  }
}
