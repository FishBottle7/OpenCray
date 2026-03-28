package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.persistence.model.ChatTranscriptMessageEntry

internal object ChatRuntimeTextFormatter {
  fun formatTextOnly(
    text: String,
    commandLabel: String?,
  ): String = buildString {
    if (!commandLabel.isNullOrBlank()) {
      append("Command: ")
      append(commandLabel.trim())
      append('\n')
    }
    val normalizedText = text.trim()
    if (normalizedText.isNotBlank()) {
      if (isNotEmpty()) {
        append('\n')
      }
      append(normalizedText)
    }
  }.trim()

  fun format(
    text: String,
    commandLabel: String?,
    attachments: List<ChatAttachmentEntry>,
  ): String = buildString {
    if (!commandLabel.isNullOrBlank()) {
      append("Command: ")
      append(commandLabel.trim())
      append('\n')
    }
    if (attachments.isNotEmpty()) {
      if (isNotEmpty()) {
        append('\n')
      }
      append("Attachments:\n")
      attachments.forEach { attachment ->
        append("- ")
        append(attachment.displayName)
        append(" [kind=")
        append(attachment.kind.toWireKind())
        append(", chat_attachment_id=")
        append(attachment.attachmentId)
        append(", inline_markdown=attachment:")
        append(attachment.attachmentId)
        append(']')
        append('\n')
      }
    }
    val normalizedText = text.trim()
    if (normalizedText.isNotBlank()) {
      if (isNotEmpty()) {
        append('\n')
      }
      append(normalizedText)
    }
  }.trim()

  fun formatMessage(
    message: ChatTranscriptMessageEntry,
    resolvedBody: String,
  ): String = format(
    text = resolvedBody,
    commandLabel = message.commandLabel,
    attachments = message.attachments,
  )

  private fun ChatAttachmentKind.toWireKind(): String = when (this) {
    ChatAttachmentKind.IMAGE -> "image"
    ChatAttachmentKind.VOICE,
    ChatAttachmentKind.AUDIO,
    -> "voice"
    ChatAttachmentKind.FILE -> "file"
  }
}
