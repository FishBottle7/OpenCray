package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSessionSupplementStoreFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun fileBackedStorePersistsAndConsumesSupplementsByRun() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements")
    val firstFactory = FileBackedAgentSessionSupplementStoreFactory(root)
    val firstStore = firstFactory.forChatSession("session-1")

    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "First supplement",
    )
    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "Second supplement",
    )
    firstStore.append(
      runId = "run-2",
      taskId = "task-2",
      text = "Other run supplement",
    )

    val restoredStore = FileBackedAgentSessionSupplementStoreFactory(root).forChatSession("session-1")
    val consumed = restoredStore.consumeForRun(runId = "run-1", taskId = "task-1")

    assertEquals(
      listOf("First supplement", "Second supplement"),
      consumed.map(MidLoopSupplementEntry::text),
    )
    assertEquals(
      listOf("Other run supplement"),
      restoredStore.snapshot().map(MidLoopSupplementEntry::text),
    )
  }

  @Test
  fun fileBackedStoreSeparatesSessionsAndCanConsumeAll() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements-isolated")
    val factory = FileBackedAgentSessionSupplementStoreFactory(root)

    factory.forChatSession("session-a").append(
      runId = "run-a",
      taskId = "task-a",
      text = "Session A",
    )
    factory.forChatSession("session-b").append(
      runId = "run-b",
      taskId = "task-b",
      text = "Session B",
    )

    val sessionAStore = factory.forChatSession("session-a")
    val consumedAll = sessionAStore.consumeAll()

    assertEquals(listOf("Session A"), consumedAll.map(MidLoopSupplementEntry::text))
    assertTrue(sessionAStore.snapshot().isEmpty())
    assertEquals(listOf("Session B"), factory.forChatSession("session-b").snapshot().map(MidLoopSupplementEntry::text))
  }

  @Test
  fun fileBackedStoreConsumesRunSupplementsOnlyOnceAcrossStoreInstances() {
    val root = temporaryFolder.newFolder("agent-runtime-supplements-consume-once")
    val factory = FileBackedAgentSessionSupplementStoreFactory(root)
    val firstStore = factory.forChatSession("session-1")
    val secondStore = factory.forChatSession("session-1")

    firstStore.append(
      runId = "run-1",
      taskId = "task-1",
      text = "First supplement",
    )

    val consumed = firstStore.consumeForRun(runId = "run-1", taskId = "task-1")

    assertEquals(listOf("First supplement"), consumed.map(MidLoopSupplementEntry::text))
    assertTrue(secondStore.consumeForRun(runId = "run-1", taskId = "task-1").isEmpty())
    assertTrue(factory.forChatSession("session-1").snapshot().isEmpty())
  }

  @Test
  fun fileBackedStoreAppendUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    var now = 1_000L
    var suffix = 0
    val store = fileBackedSessionSupplementStore(
      storage = storage,
      nowEpochMs = { now },
      entryIdSuffix = { "id-${++suffix}" },
    )
    store.append(
      runId = "run-1",
      taskId = "task-1",
      text = "First supplement",
    )
    val staleBeforeConcurrentAppend = storage.currentText
    now = 2_000L
    store.append(
      runId = "run-2",
      taskId = "task-2",
      text = "Second supplement",
    )
    val updateCallsBeforeAppend = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentAppend)
    now = 3_000L
    store.append(
      runId = "run-3",
      taskId = "task-3",
      text = "Third supplement",
    )

    assertEquals(updateCallsBeforeAppend + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(
      listOf("First supplement", "Second supplement", "Third supplement"),
      store.snapshot().map(MidLoopSupplementEntry::text),
    )
  }

  @Test
  fun fileBackedStoreConsumeForRunUsesSingleStorageUpdate() {
    val storage = StaleReadDurableTextStorage()
    var now = 1_000L
    var suffix = 0
    val store = fileBackedSessionSupplementStore(
      storage = storage,
      nowEpochMs = { now },
      entryIdSuffix = { "id-${++suffix}" },
    )
    store.append(
      runId = "run-remove",
      taskId = "task-remove",
      text = "Remove supplement",
    )
    now = 2_000L
    store.append(
      runId = "run-keep",
      taskId = "task-keep",
      text = "Keep supplement",
    )
    val staleBeforeConcurrentAppend = storage.currentText
    now = 3_000L
    store.append(
      runId = "run-concurrent",
      taskId = "task-concurrent",
      text = "Concurrent supplement",
    )
    val updateCallsBeforeConsume = storage.updateTextCallCount

    storage.returnStaleTextOnNextRead(staleBeforeConcurrentAppend)
    now = 4_000L
    val consumed = store.consumeForRun(runId = "run-remove", taskId = "task-remove")

    assertEquals(updateCallsBeforeConsume + 1, storage.updateTextCallCount)
    assertTrue(storage.hasPendingStaleRead)
    storage.clearPendingStaleRead()
    assertEquals(listOf("Remove supplement"), consumed.map(MidLoopSupplementEntry::text))
    assertEquals(
      listOf("Keep supplement", "Concurrent supplement"),
      store.snapshot().map(MidLoopSupplementEntry::text),
    )
  }

  private class StaleReadDurableTextStorage : DurableTextStorage {
    private var text: String? = null
    private var staleReadText: String? = null
    var hasPendingStaleRead: Boolean = false
      private set
    var updateTextCallCount: Int = 0
      private set

    val currentText: String?
      get() = text

    fun returnStaleTextOnNextRead(staleText: String?) {
      this.staleReadText = staleText
      hasPendingStaleRead = true
    }

    fun clearPendingStaleRead() {
      staleReadText = null
      hasPendingStaleRead = false
    }

    override fun readText(name: String): String? {
      if (!hasPendingStaleRead) {
        return text
      }
      hasPendingStaleRead = false
      return staleReadText
    }

    override fun writeText(name: String, text: String) {
      this.text = text
    }

    override fun delete(name: String): Boolean {
      val hadText = text != null
      text = null
      return hadText
    }

    override fun <T> updateText(
      name: String,
      update: (String?) -> DurableTextUpdate<T>,
    ): T {
      updateTextCallCount += 1
      val updated = update(text)
      if (updated.write) {
        text = updated.text
      }
      return updated.result
    }
  }
}
