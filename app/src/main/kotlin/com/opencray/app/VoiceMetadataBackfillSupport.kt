package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import java.nio.file.Files
import java.util.Locale

internal fun OpenCrayHostRuntime.scheduleVoiceMetadataBackfill(
  attachments: List<ChatAttachmentEntry>,
): Boolean {
  val voiceAttachments = attachments.filter { attachment ->
    attachment.kind == ChatAttachmentKind.VOICE
  }
  if (voiceAttachments.isEmpty()) {
    return false
  }
  primeVoiceMetadataCache(voiceAttachments)
  var mergedSynchronously = false
  val cacheStore = voiceMetadataCacheStore
  if (cacheStore != null) {
    val missingMetadataContentHashes = voiceAttachments
      .filter(::hasMissingVoiceMetadata)
      .mapNotNull { attachment -> normalizedContentSha256(attachment.contentSha256) }
      .distinct()
    missingMetadataContentHashes.forEach { contentSha256 ->
      val cachedMetadata = cacheStore.get(contentSha256) ?: return@forEach
      if (chatSessionStore.mergeVoiceAttachmentMetadata(contentSha256, cachedMetadata)) {
        mergedSynchronously = true
      }
    }
    if (mergedSynchronously) {
      emitChatSnapshot()
    }
  }
  voiceAttachments
    .filter(::requiresVoiceMetadataAnalysis)
    .mapNotNull(::voiceMetadataBackfillCandidateFor)
    .distinctBy(VoiceMetadataBackfillCandidate::contentSha256)
    .forEach { candidate ->
      if (cacheStore?.get(candidate.contentSha256)?.let(::hasAnalyzedVoiceMetadata) == true) {
        return@forEach
      }
      if (!voiceMetadataBackfillInFlight.add(candidate.contentSha256)) {
        return@forEach
      }
      voiceMetadataBackfillExecutor.execute {
        try {
          resolveVoiceMetadataBackfill(candidate)
        } finally {
          voiceMetadataBackfillInFlight.remove(candidate.contentSha256)
        }
      }
    }
  return mergedSynchronously
}

internal fun OpenCrayHostRuntime.primeVoiceMetadataCache(attachments: List<ChatAttachmentEntry>) {
  val cacheStore = voiceMetadataCacheStore ?: return
  attachments.forEach { attachment ->
    val contentSha256 = normalizedContentSha256(attachment.contentSha256) ?: return@forEach
    val metadata = AppAgentWorkspaceVoiceMetadata(
      durationMs = attachment.durationMs,
      waveformBars = attachment.waveformBars,
      transcriptText = attachment.transcriptText,
    ).normalized()
    if (!metadata.isMeaningful()) {
      return@forEach
    }
    cacheStore.put(contentSha256, metadata)
  }
}

internal fun hasMissingVoiceMetadata(attachment: ChatAttachmentEntry): Boolean =
  attachment.kind == ChatAttachmentKind.VOICE &&
    (
      attachment.durationMs == null ||
        attachment.waveformBars.isEmpty() ||
        attachment.transcriptText.isNullOrBlank()
      )

internal fun requiresVoiceMetadataAnalysis(attachment: ChatAttachmentEntry): Boolean =
  attachment.kind == ChatAttachmentKind.VOICE &&
    (
      attachment.durationMs == null ||
        attachment.waveformBars.isEmpty()
      )

internal fun voiceMetadataBackfillCandidateFor(
  attachment: ChatAttachmentEntry,
): VoiceMetadataBackfillCandidate? {
  val contentSha256 = normalizedContentSha256(attachment.contentSha256) ?: return null
  val localPath = attachment.localPath.trim().takeIf(String::isNotBlank) ?: return null
  return VoiceMetadataBackfillCandidate(
    contentSha256 = contentSha256,
    localPath = localPath,
    mimeType = attachment.mimeType?.trim()?.takeIf(String::isNotBlank),
  )
}

internal fun OpenCrayHostRuntime.resolveVoiceMetadataBackfill(candidate: VoiceMetadataBackfillCandidate) {
  val workspaceRoot = workspaceRootProvider?.invoke() ?: return
  val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
  val resolvedPath = normalizedWorkspaceRoot
    .resolve(candidate.localPath)
    .normalize()
  if (!resolvedPath.startsWith(normalizedWorkspaceRoot) || !Files.isRegularFile(resolvedPath)) {
    return
  }
  val metadata = voiceMetadataAnalyzer.analyze(
    path = resolvedPath,
    mimeType = candidate.mimeType,
  )?.normalized() ?: return
  if (!metadata.isMeaningful()) {
    return
  }
  voiceMetadataCacheStore?.put(candidate.contentSha256, metadata)
  if (chatSessionStore.mergeVoiceAttachmentMetadata(candidate.contentSha256, metadata)) {
    emitChatSnapshot()
  }
}

internal fun normalizedContentSha256(value: String?): String? =
  value
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf(String::isNotBlank)

internal fun AppAgentWorkspaceVoiceMetadata.normalized(): AppAgentWorkspaceVoiceMetadata =
  AppAgentWorkspaceVoiceMetadata(
    durationMs = durationMs?.takeIf { value -> value >= 0L },
    waveformBars = waveformBars.map { value -> value.coerceIn(0, 100) },
    transcriptText = transcriptText?.trim()?.takeIf(String::isNotBlank),
  )

internal fun AppAgentWorkspaceVoiceMetadata.isMeaningful(): Boolean =
  durationMs != null || waveformBars.isNotEmpty() || !transcriptText.isNullOrBlank()

internal fun hasAnalyzedVoiceMetadata(metadata: AppAgentWorkspaceVoiceMetadata): Boolean =
  metadata.durationMs != null && metadata.waveformBars.isNotEmpty()

internal data class VoiceMetadataBackfillCandidate(
  val contentSha256: String,
  val localPath: String,
  val mimeType: String?,
)
