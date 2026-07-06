package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.nio.file.Path
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AppAgentWorkspaceVoiceMetadataCacheStore(
  directory: File,
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory),
  private val json: Json = Json { ignoreUnknownKeys = true },
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
  private var entries: MutableMap<String, AppAgentWorkspaceVoiceMetadataCacheEntry> = linkedMapOf()

  @Synchronized
  fun get(contentSha256: String): AppAgentWorkspaceVoiceMetadata? {
    val key = contentSha256.trim().lowercase()
    if (key.isEmpty()) {
      return null
    }
    replaceEntriesLocked(loadSnapshot())
    val entry = entries[key] ?: return null
    return AppAgentWorkspaceVoiceMetadata(
      durationMs = entry.durationMs,
      waveformBars = entry.waveformBars,
      transcriptText = entry.transcriptText,
    )
  }

  @Synchronized
  fun put(contentSha256: String, metadata: AppAgentWorkspaceVoiceMetadata) {
    val key = contentSha256.trim().lowercase()
    if (key.isEmpty()) {
      return
    }
    val normalized = metadata.normalized().takeIf { candidate -> candidate.hasMeaningfulData() } ?: return
    val updatedEntry = AppAgentWorkspaceVoiceMetadataCacheEntry(
      durationMs = normalized.durationMs,
      waveformBars = normalized.waveformBars,
      transcriptText = normalized.transcriptText,
      updatedAtEpochMs = nowEpochMs(),
    )
    val updatedSnapshot = storage.updateText(FILE_NAME) { currentText ->
      val currentEntries = LinkedHashMap(decodeSnapshot(currentText)?.entries ?: emptyMap())
      currentEntries[key] = updatedEntry
      val snapshot = AppAgentWorkspaceVoiceMetadataCacheSnapshot(
        entries = currentEntries.toMap(),
      )
      DurableTextUpdate(
        text = json.encodeToString(snapshot),
        result = snapshot,
      )
    }
    replaceEntriesLocked(updatedSnapshot)
  }

  @Synchronized
  fun clear() {
    entries = linkedMapOf()
    storage.delete(FILE_NAME)
  }

  private fun loadSnapshot(): AppAgentWorkspaceVoiceMetadataCacheSnapshot? =
    decodeSnapshot(storage.readText(FILE_NAME))

  private fun decodeSnapshot(text: String?): AppAgentWorkspaceVoiceMetadataCacheSnapshot? {
    val encoded = text?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
      json.decodeFromString<AppAgentWorkspaceVoiceMetadataCacheSnapshot>(encoded)
    }.getOrNull()
  }

  private fun replaceEntriesLocked(snapshot: AppAgentWorkspaceVoiceMetadataCacheSnapshot?) {
    entries = LinkedHashMap(snapshot?.entries ?: emptyMap())
  }

  private fun AppAgentWorkspaceVoiceMetadata.normalized(): AppAgentWorkspaceVoiceMetadata =
    AppAgentWorkspaceVoiceMetadata(
      durationMs = durationMs?.takeIf { value -> value >= 0L },
      waveformBars = waveformBars.map { value -> value.coerceIn(0, 100) },
      transcriptText = transcriptText?.trim()?.takeIf(String::isNotBlank),
    )

  private fun AppAgentWorkspaceVoiceMetadata.hasMeaningfulData(): Boolean =
    durationMs != null || waveformBars.isNotEmpty() || !transcriptText.isNullOrBlank()

  companion object {
    private const val FILE_NAME: String = "voice-metadata-cache.json"

    fun fromWorkspaceRoot(
      workspaceRoot: Path,
      nowEpochMs: () -> Long = System::currentTimeMillis,
    ): AppAgentWorkspaceVoiceMetadataCacheStore = AppAgentWorkspaceVoiceMetadataCacheStore(
      directory = workspaceRoot
        .resolve(".opencray")
        .resolve("voice-metadata-cache")
        .toFile(),
      nowEpochMs = nowEpochMs,
    )
  }
}

@Serializable
internal data class AppAgentWorkspaceVoiceMetadataCacheSnapshot(
  val entries: Map<String, AppAgentWorkspaceVoiceMetadataCacheEntry> = emptyMap(),
)

@Serializable
internal data class AppAgentWorkspaceVoiceMetadataCacheEntry(
  val durationMs: Long? = null,
  val waveformBars: List<Int> = emptyList(),
  val transcriptText: String? = null,
  val updatedAtEpochMs: Long = 0L,
)
