package com.opencray.app.facade.llm

import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.MAX_INLINE_IMAGE_BYTES
import com.opencray.app.OpenAiCompatibleLiteLlmProviderClient.Companion.MAX_INLINE_PDF_BYTES
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmProviderRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal data class EncodedImageAttachment(
  val attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  val mimeType: String,
  val base64Data: String,
)

internal data class EncodedPdfAttachment(
  val attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  val displayName: String,
  val mimeType: String,
  val base64Data: String,
)

internal data class MultimodalMessageAssembly(
  val text: String? = null,
  val inlinePdfs: List<EncodedPdfAttachment> = emptyList(),
  val inlineImages: List<EncodedImageAttachment> = emptyList(),
)

internal fun OpenAiCompatibleLiteLlmProviderClient.multimodalAssemblyFor(
    request: LiteLlmProviderRequest,
    message: LiteLlmGatewayMessage,
    allowInlineImages: Boolean,
  ): MultimodalMessageAssembly {
    val inlinePdfs = if (pdfInputSupported(request)) {
      message.attachments.mapNotNull(::encodeInlinePdfAttachment)
    } else {
      emptyList()
    }
    val inlineImages = if (allowInlineImages && visionInputSupported(request)) {
      message.attachments.mapNotNull(::encodeInlineImageAttachment)
    } else {
      emptyList()
    }
    val consumedAttachments = (inlinePdfs.map { encoded -> encoded.attachment } +
      inlineImages.map { encoded -> encoded.attachment })
      .toSet()
    val residualAttachments = message.attachments.filterNot { attachment -> attachment in consumedAttachments }
    return MultimodalMessageAssembly(
      text = contentWithAttachmentFallback(
        content = message.content,
        attachments = residualAttachments,
      ),
      inlinePdfs = inlinePdfs,
      inlineImages = inlineImages,
    )
  }

private fun visionInputSupported(request: LiteLlmProviderRequest): Boolean =
    request.route.metadata["visionInputSupported"]
      ?.trim()
      ?.lowercase() == "true"

private fun pdfInputSupported(request: LiteLlmProviderRequest): Boolean =
    request.route.metadata["pdfInputSupported"]
      ?.trim()
      ?.lowercase() == "true"

private fun contentWithAttachmentFallback(
    content: String?,
    attachments: List<com.opencray.llm.LiteLlmGatewayAttachment>,
  ): String? {
    val blocks = mutableListOf<String>()
    content?.trim()?.takeIf(String::isNotBlank)?.let(blocks::add)
    if (attachments.isNotEmpty()) {
      blocks += attachmentFallbackText(attachments)
    }
    return blocks.joinToString(separator = "\n\n").takeIf(String::isNotBlank)
  }

private fun attachmentFallbackText(
    attachments: List<com.opencray.llm.LiteLlmGatewayAttachment>,
  ): String = buildString {
    appendLine("Attachments:")
    attachments.forEach { attachment ->
      append("- ")
      append(
        attachment.displayName
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: attachment.attachmentId
          ?: "attachment",
      )
      append(" [kind=")
      append(attachment.kind.name.lowercase())
      attachment.attachmentId?.trim()?.takeIf(String::isNotBlank)?.let { attachmentId ->
        append(", attachment_id=")
        append(attachmentId)
      }
      attachment.mimeType?.trim()?.takeIf(String::isNotBlank)?.let { mimeType ->
        append(", mime_type=")
        append(mimeType)
      }
      append(']')
      appendLine()
    }
  }.trim()

internal fun OpenAiCompatibleLiteLlmProviderClient.inlineImageDataUrl(image: EncodedImageAttachment): String =
    "data:${image.mimeType};base64,${image.base64Data}"

internal fun OpenAiCompatibleLiteLlmProviderClient.inlinePdfDataUrl(pdf: EncodedPdfAttachment): String =
    "data:${pdf.mimeType};base64,${pdf.base64Data}"

private fun encodeInlinePdfAttachment(
    attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  ): EncodedPdfAttachment? {
    if (attachment.kind != com.opencray.llm.LiteLlmGatewayAttachmentKind.FILE) {
      return null
    }
    val filePath = attachment.filePath?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      return null
    }
    val sizeBytes = runCatching { Files.size(path) }.getOrNull() ?: return null
    if (sizeBytes <= 0L || sizeBytes > MAX_INLINE_PDF_BYTES) {
      return null
    }
    val mimeType = normalizedPdfMimeType(
      preferredMimeType = attachment.mimeType,
      path = path,
    ) ?: return null
    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return EncodedPdfAttachment(
      attachment = attachment,
      displayName = attachment.displayName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: path.fileName.toString(),
      mimeType = mimeType,
      base64Data = Base64.getEncoder().encodeToString(bytes),
    )
  }

private fun encodeInlineImageAttachment(
    attachment: com.opencray.llm.LiteLlmGatewayAttachment,
  ): EncodedImageAttachment? {
    if (attachment.kind != com.opencray.llm.LiteLlmGatewayAttachmentKind.IMAGE) {
      return null
    }
    val filePath = attachment.filePath?.trim()?.takeIf(String::isNotBlank) ?: return null
    val path = runCatching { Path.of(filePath).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (!Files.exists(path) || !Files.isRegularFile(path)) {
      return null
    }
    val sizeBytes = runCatching { Files.size(path) }.getOrNull() ?: return null
    if (sizeBytes <= 0L || sizeBytes > MAX_INLINE_IMAGE_BYTES) {
      return null
    }
    val mimeType = normalizedImageMimeType(
      preferredMimeType = attachment.mimeType,
      path = path,
    ) ?: return null
    val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return null
    return EncodedImageAttachment(
      attachment = attachment,
      mimeType = mimeType,
      base64Data = Base64.getEncoder().encodeToString(bytes),
    )
  }

private fun normalizedImageMimeType(
    preferredMimeType: String?,
    path: Path,
  ): String? {
    val normalizedPreferred = preferredMimeType
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (normalizedPreferred?.startsWith("image/") == true) {
      return normalizedPreferred
    }
    val probedMimeType = runCatching { Files.probeContentType(path) }
      .getOrNull()
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (probedMimeType?.startsWith("image/") == true) {
      return probedMimeType
    }
    return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
      "png" -> "image/png"
      "jpg",
      "jpeg",
      -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "bmp" -> "image/bmp"
      "heic" -> "image/heic"
      "heif" -> "image/heif"
      else -> null
    }
  }

private fun normalizedPdfMimeType(
    preferredMimeType: String?,
    path: Path,
  ): String? {
    val normalizedPreferred = preferredMimeType
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (normalizedPreferred == "application/pdf") {
      return normalizedPreferred
    }
    val probedMimeType = runCatching { Files.probeContentType(path) }
      .getOrNull()
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase()
      ?.takeIf(String::isNotBlank)
    if (probedMimeType == "application/pdf") {
      return probedMimeType
    }
    return path.fileName.toString()
      .substringAfterLast('.', "")
      .lowercase()
      .takeIf { extension -> extension == "pdf" }
      ?.let { "application/pdf" }
  }
