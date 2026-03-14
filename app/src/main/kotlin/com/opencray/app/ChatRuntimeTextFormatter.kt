package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatTranscriptMessageEntry

internal object ChatRuntimeTextFormatter {
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
      append("Attachments:\n")
      attachments.forEach { attachment ->
        append("- ")
        append(attachment.displayName)
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
}
