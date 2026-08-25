package com.opencray.persistence.store.file

import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.persistence.model.ChatTranscriptSessionEntry
import com.opencray.persistence.model.ChatWorkspaceRecord
import com.opencray.persistence.store.ChatWorkspaceStoreUpdate
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileChatWorkspaceStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun updateReadsLatestPersistedWorkspaceBeforeWriting() {
    val directory = temporaryFolder.newFolder("chat-workspace-update")
    val store = JsonFileChatWorkspaceStore(directory)
    val base = workspaceRecord(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      extensions = mapOf("marker" to "base"),
    )
    store.save(base)
    val staleSnapshot = store.load()
    store.save(
      workspaceRecord(
        recordVersion = 2L,
        updatedAtEpochMs = 2_000L,
        extensions = mapOf("marker" to "concurrent"),
      ),
    )
    var observedMarker: String? = null

    val result = store.update { current ->
      val workspace = requireNotNull(current)
      observedMarker = workspace.extensions["marker"]
      ChatWorkspaceStoreUpdate(
        record = workspace.copy(
          recordVersion = workspace.recordVersion + 1L,
          updatedAtEpochMs = 3_000L,
          extensions = workspace.extensions + ("todos.session-1" to "[]"),
        ),
        result = workspace.extensions["marker"],
      )
    }

    assertEquals("base", staleSnapshot?.extensions?.get("marker"))
    assertEquals("concurrent", observedMarker)
    assertEquals("concurrent", result)
    val restored = requireNotNull(store.load())
    assertEquals("concurrent", restored.extensions["marker"])
    assertEquals("[]", restored.extensions["todos.session-1"])
  }

  @Test
  fun updateBacksUpUndecodableWorkspaceFileAndStartsFresh() {
    val directory = temporaryFolder.newFolder("chat-workspace-corrupt")
    val corruptContent = "{\"sessions\":\"not-a-list\",\"activeSessionId\":null}"
    File(directory, "chat-workspace.json").writeText(corruptContent, Charsets.UTF_8)
    val store = JsonFileChatWorkspaceStore(directory)
    val replacement = workspaceRecord(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      extensions = mapOf("marker" to "fresh"),
    )

    val result = store.update { current ->
      assertNull(current)
      ChatWorkspaceStoreUpdate(
        record = replacement,
        result = "recreated",
      )
    }

    assertEquals("recreated", result)
    assertEquals(replacement, store.load())
    val backups = corruptBackups(directory, "chat-workspace.json")
    assertEquals(1, backups.size)
    assertArrayEquals(
      corruptContent.toByteArray(Charsets.UTF_8),
      backups.single().readBytes(),
    )
  }

  @Test
  fun updateWithoutExistingWorkspaceFileCreatesNoCorruptBackup() {
    val directory = temporaryFolder.newFolder("chat-workspace-missing")
    val store = JsonFileChatWorkspaceStore(directory)
    val created = workspaceRecord(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      extensions = emptyMap(),
    )

    store.update { current ->
      assertNull(current)
      ChatWorkspaceStoreUpdate(
        record = created,
        result = Unit,
      )
    }

    assertEquals(created, store.load())
    assertTrue(corruptBackups(directory, "chat-workspace.json").isEmpty())
  }

  @Test
  fun updateTreatsBlankWorkspaceFileAsMissingWithoutBackup() {
    val directory = temporaryFolder.newFolder("chat-workspace-blank")
    File(directory, "chat-workspace.json").writeText("   ", Charsets.UTF_8)
    val store = JsonFileChatWorkspaceStore(directory)
    val created = workspaceRecord(
      recordVersion = 1L,
      updatedAtEpochMs = 1_000L,
      extensions = emptyMap(),
    )

    store.update { current ->
      assertNull(current)
      ChatWorkspaceStoreUpdate(
        record = created,
        result = Unit,
      )
    }

    assertEquals(created, store.load())
    assertTrue(corruptBackups(directory, "chat-workspace.json").isEmpty())
  }

  private fun corruptBackups(directory: File, baseName: String): List<File> =
    directory.listFiles { file -> file.name.startsWith("$baseName.corrupt-") }
      ?.sortedBy { it.name }
      .orEmpty()

  private fun workspaceRecord(
    recordVersion: Long,
    updatedAtEpochMs: Long,
    extensions: Map<String, String>,
  ): ChatWorkspaceRecord = ChatWorkspaceRecord(
    sessions = listOf(
      ChatTranscriptSessionEntry(
        sessionId = "session-1",
        title = "Session",
        createdAtEpochMs = 1_000L,
        updatedAtEpochMs = updatedAtEpochMs,
        messages = listOf(
          ChatTranscriptMessageEntry(
            messageId = "message-system",
            role = ChatTranscriptRole.SYSTEM,
            promptTemplateRefId = "system.default.v1",
            createdAtEpochMs = 1_000L,
          ),
        ),
      ),
    ),
    activeSessionId = "session-1",
    recordVersion = recordVersion,
    createdAtEpochMs = 1_000L,
    updatedAtEpochMs = updatedAtEpochMs,
    extensions = extensions,
  )
}
