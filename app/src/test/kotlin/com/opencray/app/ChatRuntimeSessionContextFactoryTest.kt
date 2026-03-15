package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.soul.SoulProfileExtensionKeys
import com.opencray.runtime.context.RuntimeConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
          localPath = "D:/tmp/release-notes.md",
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

    val context = ChatRuntimeSessionContextFactory(store).create(
      sessionId = sessionId,
      excludedMessageIds = setOf(pendingMessageId),
      soulProfile = PersonalizationLocalStore.SoulProfile(
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
    assertTrue(context.conversation[0].content.contains("Attachments:"))
    assertTrue(context.conversation[0].content.contains("release-notes.md"))
    assertTrue(context.conversation[0].content.contains("Please prepare the release summary."))
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
  fun createForwardsManagedSoulExtensionsGeneratedByPersonalizationStore() {
    val chatStore = ChatSessionLocalStore(temporaryFolder.newFolder("chat-store-soul-extensions"))
    val personalizationStore = PersonalizationLocalStore(
      temporaryFolder.newFolder("personalization-store"),
      nowEpochMs = IncrementingClock(3_000L)::next,
    )
    val state = chatStore.loadState()
    val sessionId = state.activeSession.sessionId
    personalizationStore.saveSoulProfile(
      PersonalizationLocalStore.SoulProfile(
        presetName = "BUILDER",
        customLabel = "Night Shift",
        customGuidance = "Stay direct.",
      ),
    )

    val context = ChatRuntimeSessionContextFactory(chatStore).create(
      sessionId = sessionId,
      soulProfile = personalizationStore.loadSoulProfile(),
    )

    assertEquals("builder", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.TONE))
    assertEquals("terse", context.soulProfile?.extensions?.get(SoulProfileExtensionKeys.VERBOSITY))
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
