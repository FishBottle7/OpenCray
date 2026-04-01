package com.opencray.app

import com.opencray.persistence.model.ChatTranscriptMessageEntry
import com.opencray.runtime.OpenCrayImageReferenceSource
import java.nio.file.Path

internal class AppImageReferenceSourceResolverFactory(
  private val workspaceRootProvider: () -> Path?,
  private val privateRootProvider: () -> Path,
  private val chatSessionStore: ChatSessionLocalStore,
  private val runArtifactCatalog: AppRunArtifactCatalog,
  private val settingsImageAssetStore: AppSettingsImageAssetStore,
) {
  fun create(): AppImageReferenceSourceResolver = AppCompositeImageReferenceSourceResolver(
    workspaceRoot = workspaceRootProvider(),
    privateRoot = privateRootProvider(),
    chatAttachmentLookup = ::resolveChatAttachment,
    runArtifactLookup = ::resolveRunArtifact,
    settingsAssetLookup = ::resolveSettingsAsset,
  )

  private fun resolveChatAttachment(
    source: OpenCrayImageReferenceSource,
  ): AppResolvedImageAssetHandle? {
    val sessionId = source.sourceSessionId?.trim()?.takeIf(String::isNotBlank) ?: return null
    val attachmentId = source.chatAttachmentId?.trim()?.takeIf(String::isNotBlank) ?: return null
    val workspaceRoot = workspaceRootProvider()?.toAbsolutePath()?.normalize() ?: return null
    val session = chatSessionStore.loadSession(sessionId) ?: return null
    val preferredMessageId = source.sourceMessageId?.trim()?.takeIf(String::isNotBlank)
    val preferredMatch = preferredMessageId?.let { messageId ->
      session.messages.firstOrNull { message -> message.messageId == messageId }
        ?.findAttachmentHandle(
          workspaceRoot = workspaceRoot,
          attachmentId = attachmentId,
          sessionId = sessionId,
        )
    }
    if (preferredMatch != null) {
      return preferredMatch
    }
    return session.messages
      .asReversed()
      .firstNotNullOfOrNull { message ->
        message.findAttachmentHandle(
          workspaceRoot = workspaceRoot,
          attachmentId = attachmentId,
          sessionId = sessionId,
        )
      }
  }

  private fun resolveRunArtifact(
    source: OpenCrayImageReferenceSource,
  ): AppResolvedImageAssetHandle? {
    val sessionId = source.sourceSessionId?.trim()?.takeIf(String::isNotBlank) ?: return null
    val artifactId = source.artifactId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return runArtifactCatalog.resolve(
      sessionId = sessionId,
      artifactId = artifactId,
    )
  }

  private fun resolveSettingsAsset(
    source: OpenCrayImageReferenceSource,
  ): AppResolvedImageAssetHandle? {
    val settingsAssetId = source.settingsAssetId?.trim()?.takeIf(String::isNotBlank) ?: return null
    return settingsImageAssetStore.resolveImageHandle(settingsAssetId)
  }

  private fun ChatTranscriptMessageEntry.findAttachmentHandle(
    workspaceRoot: Path,
    attachmentId: String,
    sessionId: String,
  ): AppResolvedImageAssetHandle? = attachments
    .asReversed()
    .firstOrNull { attachment ->
      attachment.attachmentId.trim() == attachmentId
    }
    ?.toAppResolvedImageAssetHandle(
      workspaceRoot = workspaceRoot,
      sourceSessionId = sessionId,
      sourceMessageId = messageId,
    )
}
