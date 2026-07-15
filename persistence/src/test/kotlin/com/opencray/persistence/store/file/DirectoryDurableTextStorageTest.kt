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
    assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
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
  fun writeTextToleratesDirectoryCreatedByConcurrentOwner() {
    val directory = ConcurrentlyCreatedDirectory(
      File(temporaryFolder.root, "durable-text-storage-concurrent-create").path,
    )
    val storage = DirectoryDurableTextStorage(directory)

    storage.writeText("runtime-runs.json", """{"runs":[]}""")

    assertEquals("""{"runs":[]}""", storage.readText("runtime-runs.json"))
    assertEquals(1, directory.mkdirsCallCount)
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
  fun updateTextAllowsSameThreadReadThroughAnotherStorageInstance() {
    val directory = temporaryFolder.newFolder("durable-text-storage-reentrant-read")
    val outerStorage = DirectoryDurableTextStorage(directory)
    val nestedStorage = DirectoryDurableTextStorage(directory)
    outerStorage.writeText("chat-workspace.json", """{"sessions":[]}""")

    val nestedRead = outerStorage.updateText("chat-workspace.json") { current ->
      DurableTextUpdate(
        text = current,
        result = nestedStorage.readText("chat-workspace.json"),
        write = false,
      )
    }

    assertEquals("""{"sessions":[]}""", nestedRead)
    assertEquals("""{"sessions":[]}""", outerStorage.readText("chat-workspace.json"))
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

  private class ConcurrentlyCreatedDirectory(path: String) : File(path) {
    var mkdirsCallCount: Int = 0
      private set

    override fun exists(): Boolean =
      if (mkdirsCallCount == 0) {
        false
      } else {
        super.exists()
      }

    override fun mkdirs(): Boolean {
      mkdirsCallCount += 1
      super.mkdirs()
      return false
    }
  }
}
