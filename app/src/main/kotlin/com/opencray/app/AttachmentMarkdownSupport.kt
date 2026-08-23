package com.opencray.app

import java.util.Locale

internal val ATTACHMENT_MARKDOWN_REFERENCE_REGEX: Regex =
  Regex("""(!?)\[([^\]]*)]\(([^)]+)\)""")

internal val IMAGE_ATTACHMENT_EXTENSIONS: Set<String> = setOf(
  "apng",
  "avif",
  "bmp",
  "gif",
  "jpeg",
  "jpg",
  "png",
  "svg",
  "webp",
)

internal fun normalizeAttachmentMarkdownToken(value: String): String = value
  .trim()
  .removePrefix("/")
  .replace('\\', '/')
  .lowercase(Locale.US)

internal fun isAttachmentMarkdownHref(href: String): Boolean {
  val normalized = href.trim()
  if (normalized.isBlank()) {
    return false
  }
  if (normalized.startsWith("attachment:", ignoreCase = true)) {
    return true
  }
  return !listOf("http://", "https://", "mailto:", "data:").any { prefix ->
    normalized.startsWith(prefix, ignoreCase = true)
  }
}

internal fun cleanupAttachmentMarkdownText(text: String): String = text
  .replace(Regex("""[ \t]+\n"""), "\n")
  .replace(Regex("""\n[ \t]+"""), "\n")
  .replace(Regex("""\n{3,}"""), "\n\n")
  .trim()

internal data class AttachmentMarkdownReference(
  val raw: String,
  val label: String,
  val targetToken: String,
  val isImage: Boolean,
) {
  val fallbackLabel: String
    get() = label.ifBlank { targetToken.substringAfterLast('/').trim() }
}

internal data class ResolvedAttachmentMarkdownReference(
  val reference: AttachmentMarkdownReference,
  val attachment: AttachmentMarkdownCandidate?,
)

internal data class AttachmentMarkdownCandidate(
  val attachmentId: String? = null,
  val relativePath: String,
  val displayName: String,
  val kindHint: String? = null,
  val mimeType: String? = null,
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
  val artifactId: String? = null,
  val filePath: String? = null,
) {
  private val normalizedRelativePath: String = normalizeAttachmentMarkdownToken(relativePath)
  private val normalizedDisplayName: String = normalizeAttachmentMarkdownToken(displayName)
  private val normalizedBaseName: String = normalizedRelativePath.substringAfterLast('/')
  private val normalizedArtifactId: String? = artifactId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.lowercase(Locale.US)
  private val normalizedAttachmentId: String? = attachmentId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.lowercase(Locale.US)

  val isImageLike: Boolean
    get() = kindHint?.trim()?.lowercase(Locale.US) == "image" ||
      mimeType?.trim()?.lowercase(Locale.US)?.startsWith("image/") == true ||
      IMAGE_ATTACHMENT_EXTENSIONS.contains(normalizedDisplayName.substringAfterLast('.', ""))

  fun matches(
    token: String,
    includeArtifactId: Boolean = true,
    includeAttachmentId: Boolean = true,
  ): Boolean {
    val normalizedToken = normalizeAttachmentMarkdownToken(token)
    if (normalizedToken.isBlank()) {
      return false
    }
    return normalizedToken == normalizedRelativePath ||
      normalizedToken == normalizedDisplayName ||
      normalizedToken == normalizedBaseName ||
      (includeAttachmentId && normalizedToken == normalizedAttachmentId) ||
      (includeArtifactId && normalizedToken == normalizedArtifactId)
  }
}

internal fun parseAttachmentMarkdownReferences(
  text: String,
): List<AttachmentMarkdownReference> = ATTACHMENT_MARKDOWN_REFERENCE_REGEX.findAll(text)
  .mapNotNull { match ->
    val href = match.groupValues[3]
      .trim()
      .substringBefore(' ')
      .trim()
    if (!isAttachmentMarkdownHref(href)) {
      return@mapNotNull null
    }
    val normalizedHref = href
      .removePrefix("attachment:")
      .removePrefix("//")
      .trim()
    AttachmentMarkdownReference(
      raw = match.value,
      label = match.groupValues[2].trim(),
      targetToken = normalizeAttachmentMarkdownToken(normalizedHref),
      isImage = match.groupValues[1] == "!",
    )
  }
  .toList()

internal fun resolveAttachmentMarkdownReference(
  reference: AttachmentMarkdownReference,
  candidates: List<AttachmentMarkdownCandidate>,
): AttachmentMarkdownCandidate? {
  if (candidates.isEmpty()) {
    return null
  }
  val preferredCandidates = if (reference.isImage) {
    candidates.filter(AttachmentMarkdownCandidate::isImageLike).ifEmpty { candidates }
  } else {
    candidates
  }
  val targetToken = reference.targetToken
  if (targetToken.isNotBlank() && targetToken != "artifact") {
    preferredCandidates.firstOrNull { candidate -> candidate.matches(targetToken) }?.let { match ->
      return match
    }
  }
  val labelToken = normalizeAttachmentMarkdownToken(reference.label)
  if (labelToken.isNotBlank()) {
    preferredCandidates.firstOrNull { candidate ->
      candidate.matches(
        labelToken,
        includeArtifactId = false,
        includeAttachmentId = false,
      )
    }?.let { match ->
      return match
    }
  }
  return if ((targetToken.isBlank() || targetToken == "artifact") && preferredCandidates.size == 1) {
    preferredCandidates.first()
  } else {
    null
  }
}

internal fun rewriteAttachmentMarkdownText(
  text: String,
  resolvedReferences: List<ResolvedAttachmentMarkdownReference>,
): String {
  if (resolvedReferences.isEmpty()) {
    return text
  }
  val resolvedTokens = resolvedReferences.filter { it.attachment != null }
  if (resolvedTokens.isNotEmpty()) {
    val unresolvedOnly = resolvedTokens.fold(text) { current, resolved ->
      current.replace(resolved.reference.raw, "")
    }
    if (cleanupAttachmentMarkdownText(unresolvedOnly).isBlank()) {
      return ""
    }
  }
  var rewritten = text
  resolvedReferences.forEach { resolved ->
    val replacement = when {
      resolved.attachment != null && resolved.reference.isImage -> ""
      else -> resolved.reference.fallbackLabel
    }
    rewritten = rewritten.replace(resolved.reference.raw, replacement)
  }
  return cleanupAttachmentMarkdownText(rewritten)
}
