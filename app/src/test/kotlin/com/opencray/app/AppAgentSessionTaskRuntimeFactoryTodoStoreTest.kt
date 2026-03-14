package com.opencray.app

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAgentSessionTaskRuntimeFactoryTodoStoreTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun todoStoreForSessionReusesStoreForSameSessionIdAndSeparatesDifferentSessions() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-root").toPath()
    val factory = AppAgentSessionTaskRuntimeFactory(
      llmSettingsProvider = { LlmSettingsState() },
      sessionContextFactory = ChatRuntimeSessionContextFactory(chatStore),
      soulProfileProvider = { null },
      workspaceRootsProvider = { setOf(workspaceRoot) },
      skillsRootsProvider = { emptyList() },
      mcpReportProvider = { null },
    )

    val first = factory.todoStoreForSession("session-1")
    val second = factory.todoStoreForSession("session-1")
    val third = factory.todoStoreForSession("session-2")

    assertSame(first, second)
    assertNotSame(first, third)
  }
}
