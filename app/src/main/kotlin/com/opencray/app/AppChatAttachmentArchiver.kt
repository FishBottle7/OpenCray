package com.opencray.app

import com.opencray.persistence.model.ChatAttachmentEntry
import com.opencray.persistence.model.ChatAttachmentKind
import com.opencray.runtime.OpenCrayFinalAttachment
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

internal object AppChatAttachmentArchiver {
  fun archive(
    workspaceRoot: Path,
    approvedReadRoots: Set<Path>,
    sessionId: String,
    attachments: List<OpenCrayFinalAttachment>,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer =
      DefaultAppAgentWorkspaceVoiceMetadataAnalyzer,
  ): List<ChatAttachmentEntry> {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    val allowedRoots = (approvedReadRoots + normalizedWorkspaceRoot)
      .mapTo(linkedSetOf()) { root -> root.toAbsolutePath().normalize() }
    val archiveRoot = normalizedWorkspaceRoot
      .resolve(".opencray")
      .resolve("chat-media")
      .resolve(safePathSegment(sessionId))
    var imageCount = 0

    return attachments.mapIndexedNotNull { index, attachment ->
      runCatching {
        val resolved = resolveSource(
          attachment = attachment,
          workspaceRoot = normalizedWorkspaceRoot,
          allowedRoots = allowedRoots,
        ) ?: return@runCatching null
        val source = resolved.toAbsolutePath().normalize()
        if (!source.exists() || !source.isRegularFile()) {
          return@runCatching null
        }

        val preferredName = attachment.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: source.name
        val mimeType = resolveMimeType(
          preferredMimeType = attachment.mimeType,
          fileName = preferredName,
        )
        val kind = resolveKind(
          rawKind = attachment.kind,
          fileName = preferredName,
          mimeType = mimeType,
        ) ?: return@runCatching null
        if (kind == ChatAttachmentKind.IMAGE) {
          if (imageCount >= MAX_IMAGE_ATTACHMENTS_PER_MESSAGE) {
            return@runCatching null
          }
          imageCount += 1
        }

        val contentSha256 = sha256Hex(source)
        val archivedPath = archiveCopy(
          archiveRoot = archiveRoot,
          contentSha256 = contentSha256,
          source = source,
          preferredName = preferredName,
        )
        val archivedMimeType = resolveMimeType(
          preferredMimeType = mimeType,
          fileName = archivedPath.fileName.toString(),
        )
        val resolvedVoiceMetadata = if (kind == ChatAttachmentKind.VOICE) {
          resolveVoiceMetadata(
            attachment = attachment,
            archivedPath = archivedPath,
            mimeType = archivedMimeType,
            voiceMetadataAnalyzer = voiceMetadataAnalyzer,
          )
        } else {
          null
        }
        ChatAttachmentEntry(
          attachmentId = "attachment-${index + 1}-${contentSha256.take(12)}",
          kind = kind,
          displayName = preferredName,
          localPath = normalizedWorkspaceRoot.relativize(archivedPath).toString().replace('\\', '/'),
          mimeType = archivedMimeType,
          sizeBytes = Files.size(archivedPath),
          durationMs = attachment.durationMs ?: resolvedVoiceMetadata?.durationMs,
          waveformBars = normalizeWaveformBars(
            attachment.waveformBars.ifEmpty {
              resolvedVoiceMetadata?.waveformBars.orEmpty()
            },
          ),
          transcriptText = attachment.transcriptText
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: resolvedVoiceMetadata?.transcriptText
              ?.trim()
              ?.takeIf(String::isNotBlank),
          contentSha256 = contentSha256,
        )
      }.getOrNull()
    }
  }

  private fun resolveSource(
    attachment: OpenCrayFinalAttachment,
    workspaceRoot: Path,
    allowedRoots: Set<Path>,
  ): Path? {
    val relativePath = attachment.relativePath
      ?.trim()
      ?.replace('\\', '/')
      ?.removePrefix("/")
      ?.takeIf(String::isNotBlank)
    if (relativePath != null) {
      val resolved = workspaceRoot.resolve(relativePath).normalize()
      require(resolved.startsWith(workspaceRoot)) {
        "Attachment path escapes the workspace root."
      }
      return resolved
    }

    val rawPath = attachment.path?.trim()?.takeIf(String::isNotBlank) ?: return null
    val candidate = Path.of(rawPath)
    val resolved = (if (candidate.isAbsolute) candidate else workspaceRoot.resolve(candidate))
      .toAbsolutePath()
      .normalize()
    require(allowedRoots.any { root -> resolved.startsWith(root) }) {
      "Attachment path is outside the approved read roots."
    }
    return resolved
  }

  private fun archiveCopy(
    archiveRoot: Path,
    contentSha256: String,
    source: Path,
    preferredName: String,
  ): Path {
    val contentDirectory = archiveRoot.resolve(contentSha256)
    Files.createDirectories(contentDirectory)
    Files.list(contentDirectory).use { stream ->
      stream
        .filter { candidate -> Files.isRegularFile(candidate) }
        .findFirst()
        .orElse(null)
        ?.let { existing -> return existing }
    }
    val destination = contentDirectory.resolve(safeFileName(preferredName))
    if (source != destination) {
      Files.copy(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
      )
    }
    return destination
  }

  private fun resolveKind(
    rawKind: String?,
    fileName: String,
    mimeType: String?,
  ): ChatAttachmentKind? {
    val normalizedKind = rawKind?.trim()?.lowercase(Locale.US)
    val extension = fileName.trim().substringAfterLast('.', "").lowercase(Locale.US)
    val normalizedMimeType = mimeType?.trim()?.lowercase(Locale.US)
    return when {
      normalizedKind == "image" || (normalizedKind == null && isSupportedImage(extension, normalizedMimeType)) ->
        ChatAttachmentKind.IMAGE

      normalizedKind == "voice" ||
        normalizedKind == "audio" ||
        (normalizedKind == null && isSupportedAudio(extension, normalizedMimeType)) ->
        ChatAttachmentKind.VOICE

      normalizedKind == "file" || normalizedKind == null -> ChatAttachmentKind.FILE
      else -> null
    }?.takeIf { kind ->
      when (kind) {
        ChatAttachmentKind.IMAGE -> isSupportedImage(extension, normalizedMimeType)
        ChatAttachmentKind.VOICE,
        ChatAttachmentKind.AUDIO -> isSupportedAudio(extension, normalizedMimeType)
        ChatAttachmentKind.FILE -> true
      }
    }
  }

  private fun resolveMimeType(
    preferredMimeType: String?,
    fileName: String,
  ): String? {
    val normalizedPreferred = preferredMimeType?.trim()?.takeIf(String::isNotBlank)
    if (normalizedPreferred != null) {
      return normalizedPreferred
    }
    val guessed = URLConnection.guessContentTypeFromName(fileName)
      ?.trim()
      ?.takeIf(String::isNotBlank)
    if (guessed != null) {
      return guessed
    }
    val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return FALLBACK_MIME_TYPES[extension]
  }

  private fun isSupportedImage(
    extension: String,
    mimeType: String?,
  ): Boolean = extension in SUPPORTED_IMAGE_EXTENSIONS || mimeType in SUPPORTED_IMAGE_MIME_TYPES

  private fun isSupportedAudio(
    extension: String,
    mimeType: String?,
  ): Boolean = extension in SUPPORTED_AUDIO_EXTENSIONS || mimeType in SUPPORTED_AUDIO_MIME_TYPES

  private fun resolveVoiceMetadata(
    attachment: OpenCrayFinalAttachment,
    archivedPath: Path,
    mimeType: String?,
    voiceMetadataAnalyzer: AppAgentWorkspaceVoiceMetadataAnalyzer,
  ): AppAgentWorkspaceVoiceMetadata? {
    if (attachment.durationMs != null && attachment.waveformBars.isNotEmpty()) {
      return null
    }
    return voiceMetadataAnalyzer.analyze(
      path = archivedPath,
      mimeType = mimeType,
    )
  }

  private fun normalizeWaveformBars(values: List<Int>): List<Int> = values.map { value ->
    value.coerceIn(0, 100)
  }

  private fun safePathSegment(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "session" }

  private fun safeFileName(value: String): String = value
    .trim()
    .replace(Regex("""[\\/:*?"<>|]"""), "_")
    .ifBlank { "attachment.bin" }

  private fun sha256Hex(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) {
          digest.update(buffer, 0, read)
        }
      }
    }
    return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private const val MAX_IMAGE_ATTACHMENTS_PER_MESSAGE: Int = 9

  private val SUPPORTED_IMAGE_EXTENSIONS = setOf(
    "png",
    "jpg",
    "jpeg",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
  )

  private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
    "mp3",
    "wav",
    "m4a",
  )

  private val SUPPORTED_IMAGE_MIME_TYPES = setOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heic",
    "image/heif",
  )

  private val SUPPORTED_AUDIO_MIME_TYPES = setOf(
    "audio/mpeg",
    "audio/mp3",
    "audio/wav",
    "audio/x-wav",
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
  )

  private val FALLBACK_MIME_TYPES = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "bmp" to "image/bmp",
    "heic" to "image/heic",
    "heif" to "image/heif",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "m4a" to "audio/mp4",
  )
}
