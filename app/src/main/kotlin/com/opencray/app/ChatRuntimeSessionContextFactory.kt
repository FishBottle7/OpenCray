package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.persistence.model.ChatTranscriptRole
import com.opencray.runtime.context.AgentRuntimeSessionContext
import com.opencray.runtime.context.RuntimeConversationAttachment
import com.opencray.runtime.context.RuntimeConversationAttachmentKind
import com.opencray.runtime.context.RuntimeConversationMessage
import com.opencray.runtime.context.RuntimeConversationRole
import java.nio.file.Files
import java.nio.file.Path

internal class ChatRuntimeSessionContextFactory(
  private val chatSessionStore: ChatSessionLocalStore,
  private val workspaceRootProvider: (() -> Path)? = null,
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
    val attachments = message.attachments.map(::toRuntimeConversationAttachment)
    val content = ChatRuntimeTextFormatter.formatTextOnly(
      text = resolvedBody,
      commandLabel = message.commandLabel,
    )
    if (content.isBlank() && attachments.isEmpty()) {
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
      attachments = attachments,
    )
  }

  internal fun resolveChatAttachmentEntry(
    sessionId: String,
    attachmentId: String,
  ): com.opencray.persistence.model.ChatAttachmentEntry? {
    val normalizedAttachmentId = attachmentId.trim()
    if (normalizedAttachmentId.isEmpty()) {
      return null
    }
    return chatSessionStore.loadSession(sessionId)
      ?.messages
      ?.asReversed()
      ?.firstNotNullOfOrNull { message ->
        message.attachments
          .asReversed()
          .firstOrNull { attachment -> attachment.attachmentId.trim() == normalizedAttachmentId }
      }
  }

  internal fun resolveChatAttachmentFilePath(
    attachment: com.opencray.persistence.model.ChatAttachmentEntry,
  ): Path? = resolveAttachmentFilePath(
    localPath = attachment.localPath,
  )

  private fun toRuntimeConversationAttachment(
    attachment: com.opencray.persistence.model.ChatAttachmentEntry,
  ): RuntimeConversationAttachment = RuntimeConversationAttachment(
    attachmentId = attachment.attachmentId,
    kind = attachment.kind.toRuntimeKind(),
    displayName = attachment.displayName,
    filePath = resolveAttachmentFilePath(localPath = attachment.localPath)
      ?.toString()
      ?.replace('\\', '/'),
    mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
    transcriptText = attachment.transcriptText?.trim()?.takeIf(String::isNotBlank),
  )

  private fun resolveAttachmentFilePath(
    localPath: String,
  ): Path? {
    val normalizedLocalPath = localPath.trim().takeIf(String::isNotBlank) ?: return null
    val candidate = runCatching { Path.of(normalizedLocalPath) }.getOrNull() ?: return null
    val resolved = if (candidate.isAbsolute) {
      candidate
    } else {
      val workspaceRoot = workspaceRootProvider?.invoke() ?: return null
      workspaceRoot.resolve(candidate)
    }.toAbsolutePath().normalize()
    return resolved.takeIf { path -> Files.exists(path) && Files.isRegularFile(path) }
  }

  private fun com.opencray.persistence.model.ChatAttachmentKind.toRuntimeKind():
    RuntimeConversationAttachmentKind = when (this) {
    com.opencray.persistence.model.ChatAttachmentKind.IMAGE -> RuntimeConversationAttachmentKind.IMAGE
    com.opencray.persistence.model.ChatAttachmentKind.VOICE -> RuntimeConversationAttachmentKind.VOICE
    com.opencray.persistence.model.ChatAttachmentKind.AUDIO -> RuntimeConversationAttachmentKind.AUDIO
    com.opencray.persistence.model.ChatAttachmentKind.FILE -> RuntimeConversationAttachmentKind.FILE
  }
}
