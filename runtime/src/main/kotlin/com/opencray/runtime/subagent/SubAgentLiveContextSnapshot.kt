package com.opencray.runtime.subagent

import kotlinx.serialization.Serializable

@Serializable
data class SubAgentLiveContextSnapshot(
  val mode: String? = null,
  val soulEnabled: Boolean? = null,
  val memoryRecallEnabled: Boolean? = null,
  val replaySource: String? = null,
  val replayMessageCount: Int? = null,
  val canonicalSource: String? = null,
  val canonicalMessageCount: Int? = null,
  val canonicalHistoryPreserved: Boolean? = null,
  val inheritanceSource: String? = null,
  val parentMode: String? = null,
  val parentReplayMessageCount: Int? = null,
  val budgetPreset: String? = null,
) {
  val isEmpty: Boolean
    get() = mode.isNullOrBlank() &&
      soulEnabled == null &&
      memoryRecallEnabled == null &&
      replaySource.isNullOrBlank() &&
      replayMessageCount == null &&
      canonicalSource.isNullOrBlank() &&
      canonicalMessageCount == null &&
      canonicalHistoryPreserved == null &&
      inheritanceSource.isNullOrBlank() &&
      parentMode.isNullOrBlank() &&
      parentReplayMessageCount == null &&
      budgetPreset.isNullOrBlank()

  fun toMetadataMap(
    prefix: String = CHILD_METADATA_PREFIX,
  ): Map<String, String> = if (isEmpty) {
    emptyMap()
  } else {
    linkedMapOf<String, String>().apply {
      mode?.let { put("${prefix}Mode", it) }
      soulEnabled?.let { put("${prefix}SoulEnabled", it.toString()) }
      memoryRecallEnabled?.let { put("${prefix}MemoryRecallEnabled", it.toString()) }
      replaySource?.let { put("${prefix}ReplaySource", it) }
      replayMessageCount?.let { put("${prefix}ReplayMessageCount", it.toString()) }
      canonicalSource?.let { put("${prefix}CanonicalSource", it) }
      canonicalMessageCount?.let { put("${prefix}CanonicalMessageCount", it.toString()) }
      canonicalHistoryPreserved?.let {
        put("${prefix}CanonicalHistoryPreserved", it.toString())
      }
      inheritanceSource?.let { put("${prefix}InheritanceSource", it) }
      parentMode?.let { put("${prefix}ParentMode", it) }
      parentReplayMessageCount?.let { put("${prefix}ParentReplayMessageCount", it.toString()) }
      budgetPreset?.let { put("${prefix}BudgetPreset", it) }
    }
  }

  fun toMap(): Map<String, Any?>? = if (isEmpty) {
    null
  } else {
    buildMap {
      mode?.let { put("mode", it) }
      soulEnabled?.let { put("soulEnabled", it) }
      memoryRecallEnabled?.let { put("memoryRecallEnabled", it) }
      replaySource?.let { put("replaySource", it) }
      replayMessageCount?.let { put("replayMessageCount", it) }
      canonicalSource?.let { put("canonicalSource", it) }
      canonicalMessageCount?.let { put("canonicalMessageCount", it) }
      canonicalHistoryPreserved?.let { put("canonicalHistoryPreserved", it) }
      inheritanceSource?.let { put("inheritanceSource", it) }
      parentMode?.let { put("parentMode", it) }
      parentReplayMessageCount?.let { put("parentReplayMessageCount", it) }
      budgetPreset?.let { put("budgetPreset", it) }
    }
  }

  companion object {
    const val CHILD_METADATA_PREFIX: String = "childContextLive"
    private const val LEGACY_METADATA_PREFIX: String = "contextLive"

    fun fromRuntimeMetadata(
      metadata: Map<String, String>,
    ): SubAgentLiveContextSnapshot = SubAgentLiveContextSnapshot(
      mode = metadataPreferringChild(metadata, "Mode")?.takeIf(String::isNotBlank),
      soulEnabled = metadataPreferringChild(metadata, "SoulEnabled")?.toBooleanStrictOrNull(),
      memoryRecallEnabled =
        metadataPreferringChild(metadata, "MemoryRecallEnabled")?.toBooleanStrictOrNull(),
      replaySource = metadataPreferringChild(metadata, "ReplaySource")?.takeIf(String::isNotBlank),
      replayMessageCount = metadataPreferringChild(metadata, "ReplayMessageCount")?.toIntOrNull(),
      canonicalSource =
        metadataPreferringChild(metadata, "CanonicalSource")?.takeIf(String::isNotBlank),
      canonicalMessageCount =
        metadataPreferringChild(metadata, "CanonicalMessageCount")?.toIntOrNull(),
      canonicalHistoryPreserved =
        metadataPreferringChild(metadata, "CanonicalHistoryPreserved")?.toBooleanStrictOrNull(),
      inheritanceSource =
        metadataPreferringChild(metadata, "InheritanceSource")?.takeIf(String::isNotBlank),
      parentMode = metadataPreferringChild(metadata, "ParentMode")?.takeIf(String::isNotBlank),
      parentReplayMessageCount =
        metadataPreferringChild(metadata, "ParentReplayMessageCount")?.toIntOrNull(),
      budgetPreset = metadataPreferringChild(metadata, "BudgetPreset")?.takeIf(String::isNotBlank),
    )

    private fun metadataPreferringChild(
      metadata: Map<String, String>,
      suffix: String,
    ): String? = metadata["$CHILD_METADATA_PREFIX$suffix"] ?: metadata["$LEGACY_METADATA_PREFIX$suffix"]
  }
}
