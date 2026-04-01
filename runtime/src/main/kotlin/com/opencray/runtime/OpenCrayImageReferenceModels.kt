package com.opencray.runtime

import kotlinx.serialization.Serializable

@Serializable
enum class OpenCrayImageReferenceRole {
  EVIDENCE,
  PORTRAIT,
  REFERENCE,
}

@Serializable
enum class OpenCrayImageReferenceStorageScope {
  WORKSPACE,
  AGENT_PRIVATE,
}

@Serializable
enum class OpenCrayImageReferenceSourceKind {
  CHAT_ATTACHMENT,
  RUN_ARTIFACT,
  SETTINGS_ASSET,
  WORKSPACE_PATH,
  DURABLE_ASSET,
}

@Serializable
data class OpenCrayImageReferenceSource(
  val sourceKind: OpenCrayImageReferenceSourceKind,
  val chatAttachmentId: String? = null,
  val artifactId: String? = null,
  val settingsAssetId: String? = null,
  val relativePath: String? = null,
  val displayName: String? = null,
  val mimeType: String? = null,
  val sourceSessionId: String? = null,
  val sourceMessageId: String? = null,
) {
  init {
    requireRequiredValue(
      value = when (sourceKind) {
        OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT -> chatAttachmentId
        OpenCrayImageReferenceSourceKind.RUN_ARTIFACT -> artifactId
        OpenCrayImageReferenceSourceKind.SETTINGS_ASSET -> settingsAssetId
        OpenCrayImageReferenceSourceKind.WORKSPACE_PATH,
        OpenCrayImageReferenceSourceKind.DURABLE_ASSET,
        -> relativePath
      },
      label = sourceKind.name.lowercase(),
    )
    requireOptionalValue(displayName, "displayName")
    requireOptionalValue(mimeType, "mimeType")
    requireOptionalValue(sourceSessionId, "sourceSessionId")
    requireOptionalValue(sourceMessageId, "sourceMessageId")
  }

  private fun requireRequiredValue(
    value: String?,
    label: String,
  ) {
    require(!value.isNullOrBlank()) {
      "OpenCrayImageReferenceSource $label must not be blank."
    }
  }

  private fun requireOptionalValue(
    value: String?,
    label: String,
  ) {
    require(value == null || value.isNotBlank()) {
      "OpenCrayImageReferenceSource $label must not be blank when provided."
    }
  }
}

@Serializable
data class OpenCrayImageReference(
  val refId: String,
  val role: OpenCrayImageReferenceRole,
  val storageScope: OpenCrayImageReferenceStorageScope,
  val relativePath: String,
  val mimeType: String? = null,
  val sha256: String? = null,
  val widthPx: Int? = null,
  val heightPx: Int? = null,
  val caption: String? = null,
  val summary: String,
  val sourceLabel: String? = null,
  val sourceSessionId: String? = null,
  val sourceMessageId: String? = null,
  val createdAtEpochMs: Long,
) {
  init {
    require(refId.isNotBlank()) { "OpenCrayImageReference refId must not be blank." }
    require(relativePath.isNotBlank()) { "OpenCrayImageReference relativePath must not be blank." }
    require(summary.isNotBlank()) { "OpenCrayImageReference summary must not be blank." }
    require(mimeType == null || mimeType.isNotBlank()) {
      "OpenCrayImageReference mimeType must not be blank when provided."
    }
    require(sha256 == null || sha256.matches(SHA256_REGEX)) {
      "OpenCrayImageReference sha256 must be a 64-character hex string when provided."
    }
    require(widthPx == null || widthPx > 0) { "OpenCrayImageReference widthPx must be > 0 when provided." }
    require(heightPx == null || heightPx > 0) { "OpenCrayImageReference heightPx must be > 0 when provided." }
    require(caption == null || caption.isNotBlank()) {
      "OpenCrayImageReference caption must not be blank when provided."
    }
    require(sourceLabel == null || sourceLabel.isNotBlank()) {
      "OpenCrayImageReference sourceLabel must not be blank when provided."
    }
    require(sourceSessionId == null || sourceSessionId.isNotBlank()) {
      "OpenCrayImageReference sourceSessionId must not be blank when provided."
    }
    require(sourceMessageId == null || sourceMessageId.isNotBlank()) {
      "OpenCrayImageReference sourceMessageId must not be blank when provided."
    }
    require(createdAtEpochMs >= 0L) { "OpenCrayImageReference createdAtEpochMs must be >= 0." }
  }

  companion object {
    private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
  }
}

@Serializable
data class OpenCraySoulVisualIdentity(
  val portraitSummary: String? = null,
  val primaryPortrait: OpenCrayImageReference? = null,
  val referenceImages: List<OpenCrayImageReference> = emptyList(),
) {
  init {
    require(portraitSummary == null || portraitSummary.isNotBlank()) {
      "OpenCraySoulVisualIdentity portraitSummary must not be blank when provided."
    }
    require(referenceImages.none { it.role == OpenCrayImageReferenceRole.PORTRAIT }) {
      "OpenCraySoulVisualIdentity referenceImages must not contain PORTRAIT roles."
    }
  }

  fun isMeaningful(): Boolean =
    !portraitSummary.isNullOrBlank() ||
      primaryPortrait != null ||
      referenceImages.isNotEmpty()
}
