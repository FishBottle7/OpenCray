package com.opencray.app

import com.opencray.persistence.model.MemoryRecord
import com.opencray.runtime.OpenCrayImageReference
import com.opencray.runtime.OpenCrayImageReferenceRole
import com.opencray.runtime.OpenCrayImageReferenceSource
import com.opencray.runtime.OpenCrayImageReferenceSourceKind
import com.opencray.runtime.OpenCrayImageReferenceStorageScope
import com.opencray.runtime.OpenCraySoulVisualIdentity
import com.opencray.runtime.memory.MemoryImageReferenceSupport
import java.util.Locale

internal fun AppSettingsImageAsset.toMap(): Map<String, Any?> = buildMap {
  put("assetId", assetId)
  put("displayName", displayName)
  put("relativePath", relativePath)
  put("mimeType", mimeType)
  put("sha256", sha256)
  put("sizeBytes", sizeBytes)
  put("createdAtEpochMs", createdAtEpochMs)
}

internal fun OpenCrayImageReference.toMap(): Map<String, Any?> = buildMap {
  put("refId", refId)
  put("role", role.toWireValue())
  put("storageScope", storageScope.toWireValue())
  put("relativePath", relativePath)
  mimeType?.let { put("mimeType", it) }
  sha256?.let { put("sha256", it) }
  widthPx?.let { put("widthPx", it) }
  heightPx?.let { put("heightPx", it) }
  caption?.let { put("caption", it) }
  put("summary", summary)
  sourceLabel?.let { put("sourceLabel", it) }
  sourceSessionId?.let { put("sourceSessionId", it) }
  sourceMessageId?.let { put("sourceMessageId", it) }
  put("createdAtEpochMs", createdAtEpochMs)
}

internal fun OpenCraySoulVisualIdentity.toMap(): Map<String, Any?> = buildMap {
  portraitSummary?.let { put("portraitSummary", it) }
  primaryPortrait?.let { put("primaryPortrait", it.toMap()) }
  put("referenceImages", referenceImages.map(OpenCrayImageReference::toMap))
}

internal fun MemoryRecord.toMemoryImageReferenceResultMap(): Map<String, Any?> = buildMap {
  put("memoryId", id)
  put("recordVersion", recordVersion)
  put("updatedAtEpochMs", updatedAtEpochMs)
  put(
    "imageReferences",
    MemoryImageReferenceSupport.decodeFromExtensions(extensions).map(OpenCrayImageReference::toMap),
  )
}

internal fun parseOpenCrayImageReferenceSource(
  payload: Map<String, Any?>,
): OpenCrayImageReferenceSource? {
  val sourceKind = parseImageReferenceSourceKind(payload["sourceKind"] as? String) ?: return null
  return runCatching {
    OpenCrayImageReferenceSource(
      sourceKind = sourceKind,
      chatAttachmentId = payload["chatAttachmentId"] as? String,
      artifactId = payload["artifactId"] as? String,
      settingsAssetId = payload["settingsAssetId"] as? String,
      relativePath = payload["relativePath"] as? String,
      displayName = payload["displayName"] as? String,
      mimeType = payload["mimeType"] as? String,
      sourceSessionId = payload["sourceSessionId"] as? String,
      sourceMessageId = payload["sourceMessageId"] as? String,
    )
  }.getOrNull()
}

internal fun parseAppImageReferencePromotionMode(
  rawValue: String?,
): AppImageReferencePromotionMode? = when (rawValue?.trim()?.lowercase(Locale.US)) {
  null,
  "",
  -> null

  "copy_promote" -> AppImageReferencePromotionMode.COPY_PROMOTE
  "reference_promote" -> AppImageReferencePromotionMode.REFERENCE_PROMOTE
  else -> null
}

private fun parseImageReferenceSourceKind(
  rawValue: String?,
): OpenCrayImageReferenceSourceKind? = when (rawValue?.trim()?.lowercase(Locale.US)) {
  "chat_attachment" -> OpenCrayImageReferenceSourceKind.CHAT_ATTACHMENT
  "run_artifact" -> OpenCrayImageReferenceSourceKind.RUN_ARTIFACT
  "settings_asset" -> OpenCrayImageReferenceSourceKind.SETTINGS_ASSET
  "workspace_path" -> OpenCrayImageReferenceSourceKind.WORKSPACE_PATH
  "durable_asset" -> OpenCrayImageReferenceSourceKind.DURABLE_ASSET
  else -> null
}

private fun OpenCrayImageReferenceRole.toWireValue(): String = name.lowercase(Locale.US)

private fun OpenCrayImageReferenceStorageScope.toWireValue(): String = name.lowercase(Locale.US)
