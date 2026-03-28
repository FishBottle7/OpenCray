package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRuntimeSessionContextFactoryTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun createBuildsConversationWithoutDefaultSystemPromptOrPendingPlaceholder() {
    val store = ChatSessionLocalStore(temporaryFolder.root, nowEpochMs = IncrementingClock(1_000L)::next)
    val workspaceRoot = temporaryFolder.newFolder("chat-runtime-session-context-workspace").toPath()
    val attachmentPath = workspaceRoot.resolve("chat-media").resolve("release-notes.md")
    java.nio.file.Files.createDirectories(attachmentPath.parent)
    attachmentPath.toFile().writeText("# Release notes")
    val state = store.loadState()
    val sessionId = state.activeSession.sessionId
    store.appendUserMessage(
      sessionId = sessionId,
      text = "Please prepare the release summary.",
      commandLabel = "Summarize",
      attachments = listOf(
        ChatAttachmentEntry(
          attachmentId = "attachment-1",
          kind = ChatAttachmentKind.FILE,
          displayName = "release-notes.md",
          localPath = "chat-media/release-notes.md",
        ),
      ),
    )
    store.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "I am collecting the diff now.",
    )
    store.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.SYSTEM,
      text = "Host-only note that must stay out of runtime context.",
    )
    val pendingMessageId = store.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Thinking...",
    ).messageId

    val context = ChatRuntimeSessionContextFactory(
      chatSessionStore = store,
      workspaceRootProvider = { workspaceRoot },
    ).create(
      sessionId = sessionId,
      excludedMessageIds = setOf(pendingMessageId),
      soulProfile = WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "Night Shift",
        customGuidance = "Stay direct.",
        extensions = mapOf(
          "voice" to "calm but direct",
          "toolUseBias" to "tool-forward",
          "preset" to "should-not-leak",
          "custom_guidance" to "should-not-duplicate",
        ),
      ),
    )

    assertEquals(ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_VALUE, context.sessionPolicyText)
    assertEquals("BUILDER", context.soulProfile?.presetName)
    assertEquals("Night Shift", context.soulProfile?.displayName)
    assertEquals("Stay direct.", context.soulProfile?.customGuidance)
    assertEquals("calm but direct", context.soulProfile?.extensions?.get("voice"))
    assertEquals("tool-forward", context.soulProfile?.extensions?.get("toolUseBias"))
    assertFalse(context.soulProfile?.extensions?.containsKey("preset") == true)
    assertFalse(context.soulProfile?.extensions?.containsKey("custom_guidance") == true)
    assertEquals(2, context.conversation.size)
    assertEquals(RuntimeConversationRole.USER, context.conversation[0].role)
    assertTrue(context.conversation[0].content.contains("Command: Summarize"))
    assertTrue(context.conversation[0].content.contains("Please prepare the release summary."))
    assertFalse(context.conversation[0].content.contains("Attachments:"))
    assertEquals(1, context.conversation[0].attachments.size)
    assertEquals("attachment-1", context.conversation[0].attachments.single().attachmentId)
    assertEquals("release-notes.md", context.conversation[0].attachments.single().displayName)
    assertNotNull(context.conversation[0].attachments.single().filePath)
    assertTrue(context.conversation[0].attachments.single().filePath?.endsWith("chat-media/release-notes.md") == true)
    assertEquals(RuntimeConversationRole.ASSISTANT, context.conversation[1].role)
    assertEquals("I am collecting the diff now.", context.conversation[1].content)
    assertTrue(context.conversation.none { message -> message.content.contains("Host-only note") })
  }

  @Test
  fun createRespectsVisibleThroughMessageBoundary() {
    val store = ChatSessionLocalStore(temporaryFolder.root, nowEpochMs = IncrementingClock(2_000L)::next)
    val state = store.loadState()
    val sessionId = state.activeSession.sessionId
    store.appendUserMessage(sessionId = sessionId, text = "First question")
    val firstPendingMessageId = store.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Thinking...",
    ).messageId
    store.appendUserMessage(sessionId = sessionId, text = "Future question")
    store.appendMessage(
      sessionId = sessionId,
      role = ChatTranscriptRole.ASSISTANT,
      text = "Future answer",
    )

    val context = ChatRuntimeSessionContextFactory(store).create(
      sessionId = sessionId,
      visibleThroughMessageId = firstPendingMessageId,
      excludedMessageIds = setOf(firstPendingMessageId),
    )

    assertEquals(1, context.conversation.size)
    assertEquals(RuntimeConversationRole.USER, context.conversation.first().role)
    assertTrue(context.conversation.first().content.contains("First question"))
    assertTrue(context.conversation.none { message -> message.content.contains("Future question") })
    assertTrue(context.conversation.none { message -> message.content.contains("Future answer") })
  }

  @Test
  fun createForwardsManagedSoulExtensionsGeneratedByWorkspaceSoulStore() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-extensions"))
    val workspaceRoot = temporaryFolder.newFolder("workspace-soul-extensions").toPath()
    val soulStore = WorkspaceSoulProfileStore()
    val state = chatStore.loadState()
    val sessionId = state.activeSession.sessionId
    soulStore.saveSoulProfile(
      workspaceRoot,
      WorkspaceSoulProfile(
        presetName = "BUILDER",
        customLabel = "Night Shift",
        customGuidance = "Stay direct.",
      ),
    )

    val context = ChatRuntimeSessionContextFactory(chatStore).create(
      sessionId = sessionId,
      soulProfile = soulStore.loadSoulProfile(workspaceRoot),
    )

    assertEquals("builder", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("terse", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
    assertEquals("low", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.PLASTICITY))
    assertEquals(
      "direct",
      context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.USER_RELATIONSHIP_STYLE),
    )
    assertEquals("balanced", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.RISK_TOLERANCE))
    assertEquals("tool_forward", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.TOOL_USE_BIAS))
  }

  private class IncrementingClock(
    start: Long,
  ) {
    private var value = start

    fun next(): Long = value++
  }
}
