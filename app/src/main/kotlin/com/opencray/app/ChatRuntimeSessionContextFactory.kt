package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole

internal class ChatRuntimeSessionContextFactory(
  private val chatSessionStore: ChatSessionLocalStore,
) {
  fun create(
    sessionId: String,
    visibleThroughMessageId: String? = null,
    excludedMessageIds: Set<String> = emptySet(),
    soulProfile: WorkspaceSoulProfile? = null,
  ): AgentRuntimeSessionContext {
    val session = chatSessionStore.loadSession(sessionId)
    val sessionPolicyText = resolveSessionPolicyText(session)
    val conversation = visibleMessages(
      session = session,
      visibleThroughMessageId = visibleThroughMessageId,
    )
      ?.asSequence()
      ?.filterNot { message -> message.messageId in excludedMessageIds }
      ?.mapNotNull(::toRuntimeConversationMessage)
      ?.toList()
      ?: emptyList()

    return AgentRuntimeSessionContext(
      sessionPolicyText = sessionPolicyText,
      soulProfile = soulProfile?.toRuntimeSoulProfile(),
      conversation = conversation,
    )
  }

  private fun visibleMessages(
    session: com.opencray.persistence.model.ChatTranscriptSessionEntry?,
    visibleThroughMessageId: String?,
  ): List<ChatTranscriptMessageEntry>? {
    val messages = session?.messages ?: return null
    val cutoffId = visibleThroughMessageId?.trim().orEmpty()
    if (cutoffId.isBlank()) {
      return messages
    }
    val cutoffIndex = messages.indexOfFirst { message -> message.messageId == cutoffId }
    return if (cutoffIndex >= 0) {
      messages.take(cutoffIndex + 1)
    } else {
      messages
    }
  }

  private fun resolveSessionPolicyText(
    session: com.opencray.persistence.model.ChatTranscriptSessionEntry?,
  ): String? {
    val policyTemplateId = session?.messages
      ?.firstOrNull { message ->
        message.role == ChatTranscriptRole.SYSTEM && !message.promptTemplateRefId.isNullOrBlank()
      }
      ?.promptTemplateRefId
      ?: ChatSessionLocalStore.DEFAULT_SYSTEM_TEMPLATE_ID
    return chatSessionStore.promptTemplateBody(policyTemplateId)
  }

  private fun toRuntimeConversationMessage(
    message: ChatTranscriptMessageEntry,
  ): RuntimeConversationMessage? {
    if (message.role == ChatTranscriptRole.SYSTEM) {
      return null
    }

    val resolvedBody = message.text ?: chatSessionStore.promptTemplateBody(message.promptTemplateRefId).orEmpty()
    val content = ChatRuntimeTextFormatter.formatMessage(message, resolvedBody)
    if (content.isBlank()) {
      return null
    }

    return RuntimeConversationMessage(
      role = when (message.role) {
        ChatTranscriptRole.USER -> RuntimeConversationRole.USER
        ChatTranscriptRole.ASSISTANT -> RuntimeConversationRole.ASSISTANT
        ChatTranscriptRole.TOOL -> RuntimeConversationRole.TOOL
        ChatTranscriptRole.SYSTEM -> return null
      },
      content = content,
    )
  }
}
