package com.opencray.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TwinImportSourceProbeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun detectsChatLabJsonAsChatHistory() {
    val file = temporaryFolder.newFile("chatlab.json").toPath()
    file.writeText(
      """
      {
        "chatlab": {"version": "1"},
        "meta": {"name": "Lin x User", "groupId": "chatlab_lin_user", "type": "private"},
        "members": [
          {"platformId": "actor_lin", "accountName": "Lin"},
          {"platformId": "actor_user", "accountName": "User"}
        ],
        "messages": [
          {"sender": "actor_user", "accountName": "User", "timestamp": 1735910400, "type": 0, "content": "我会补上。"}
        ]
      }
      """.trimIndent(),
    )

    val snapshot = TwinImportSourceProbe.inspect(file)

    assertEquals("chat_history", snapshot.sourceMode)
    assertEquals("chatlab_json", snapshot.formatKey)
    assertTrue(snapshot.usesExistingImporter)
    assertFalse(snapshot.needsManualSelection)
  }

  @Test
  fun detectsChatLabJsonlAsChatHistory() {
    val file = temporaryFolder.newFile("chatlab.jsonl").toPath()
    file.writeText(
      listOf(
        "{\"_type\":\"header\",\"chatlab\":{\"version\":\"1\"},\"meta\":{\"name\":\"Lin x User\",\"groupId\":\"chatlab_lin_user\",\"type\":\"private\"}}",
        "{\"_type\":\"member\",\"platformId\":\"actor_lin\",\"accountName\":\"Lin\"}",
        "{\"_type\":\"message\",\"sender\":\"actor_user\",\"accountName\":\"User\",\"timestamp\":1735910400,\"type\":0,\"content\":\"我会补上。\"}",
      ).joinToString(separator = "\n", postfix = "\n"),
    )

    val snapshot = TwinImportSourceProbe.inspect(file)

    assertEquals("chat_history", snapshot.sourceMode)
    assertEquals("chatlab_jsonl", snapshot.formatKey)
    assertTrue(snapshot.usesExistingImporter)
    assertFalse(snapshot.needsManualSelection)
  }

  @Test
  fun detectsNormalizedFictionWorkJson() {
    val file = temporaryFolder.newFile("work.json").toPath()
    file.writeText(
      """
      {
        "source_id": "novel_demo",
        "work_id": "novel_demo",
        "characters": [
          {"entity_id": "char_lin", "display_name": "Lin", "role": "anchor"},
          {"entity_id": "char_user", "display_name": "User", "role": "lead"}
        ],
        "scenes": [
          {"scene_id": "scene_01", "text": "Lin looked at User and finally spoke.", "timestamp": "2025-01-03T21:12:14+08:00"}
        ]
      }
      """.trimIndent(),
    )

    val snapshot = TwinImportSourceProbe.inspect(file)

    assertEquals("fiction_work", snapshot.sourceMode)
    assertEquals("normalized_fiction_work", snapshot.formatKey)
    assertTrue(snapshot.usesExistingImporter)
    assertFalse(snapshot.needsManualSelection)
  }
}
