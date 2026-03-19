package com.opencray.app

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.nio.file.Path
import java.util.LinkedHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AppAgentWorkspaceVoiceMetadataCacheStore(
  directory: File,
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  private val storage: DurableTextStorage = DirectoryDurableTextStorage(directory)
  private var loaded = false
  private var entries: MutableMap<String, AppAgentWorkspaceVoiceMetadataCacheEntry> = linkedMapOf()

  @Synchronized
  fun get(contentSha256: String): AppAgentWorkspaceVoiceMetadata? {
    val key = contentSha256.trim().lowercase()
    if (key.isEmpty()) {
      return null
    }
    ensureLoaded()
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
    ensureLoaded()
    val updatedEntry = AppAgentWorkspaceVoiceMetadataCacheEntry(
      durationMs = normalized.durationMs,
      waveformBars = normalized.waveformBars,
      transcriptText = normalized.transcriptText,
      updatedAtEpochMs = nowEpochMs(),
    )
    if (entries[key] == updatedEntry) {
      return
    }
    entries[key] = updatedEntry
    storage.writeText(
      FILE_NAME,
      json.encodeToString(
        AppAgentWorkspaceVoiceMetadataCacheSnapshot(
          entries = entries.toMap(),
        ),
      ),
    )
  }

  @Synchronized
  fun clear() {
    loaded = true
    entries = linkedMapOf()
    storage.delete(FILE_NAME)
  }

  private fun ensureLoaded() {
    if (loaded) {
      return
    }
    loaded = true
    val snapshot = storage.readText(FILE_NAME)
      ?.takeIf(String::isNotBlank)
      ?.let { raw ->
        runCatching {
          json.decodeFromString<AppAgentWorkspaceVoiceMetadataCacheSnapshot>(raw)
        }.getOrNull()
      }
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
