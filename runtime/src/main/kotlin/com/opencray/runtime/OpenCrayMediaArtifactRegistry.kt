package com.opencray.runtime

import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.DurableTextUpdate
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface OpenCrayMediaArtifactRegistry {
  fun register(
    artifacts: List<OpenCrayAttachmentArtifact>,
    source: OpenCrayMediaArtifactSource,
  )

  fun resolve(artifactId: String): OpenCrayRegisteredMediaArtifact?

  fun sweep(workspaceRoot: Path): OpenCrayMediaArtifactSweepResult
}

data class OpenCrayMediaArtifactSource(
  val runId: String? = null,
  val toolName: String? = null,
  val source: String = "generated",
)

data class OpenCrayRegisteredMediaArtifact(
  val artifact: OpenCrayAttachmentArtifact,
  val contentSha256: String? = null,
  val sizeBytes: Long? = null,
  val source: String = "generated",
  val runIds: Set<String> = emptySet(),
  val toolNames: Set<String> = emptySet(),
  val firstSeenAtEpochMs: Long = 0L,
  val lastSeenAtEpochMs: Long = 0L,
)

data class OpenCrayMediaArtifactSweepResult(
  val removedRecords: Int,
  val retainedRecords: Int,
)

object NoOpOpenCrayMediaArtifactRegistry : OpenCrayMediaArtifactRegistry {
  override fun register(
    artifacts: List<OpenCrayAttachmentArtifact>,
    source: OpenCrayMediaArtifactSource,
  ) = Unit

  override fun resolve(artifactId: String): OpenCrayRegisteredMediaArtifact? = null

  override fun sweep(workspaceRoot: Path): OpenCrayMediaArtifactSweepResult =
    OpenCrayMediaArtifactSweepResult(removedRecords = 0, retainedRecords = 0)
}

class FileBackedOpenCrayMediaArtifactRegistry(
  private val workspaceRoot: Path,
  private val registryFile: Path,
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  private val nowEpochMs: () -> Long = System::currentTimeMillis,
  storage: DurableTextStorage? = null,
) : OpenCrayMediaArtifactRegistry {
  private val normalizedWorkspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()
  private val normalizedRegistryFile: Path = registryFile.toAbsolutePath().normalize()
  private val storageName: String = requireNotNull(normalizedRegistryFile.fileName) {
    "Media artifact registry file must have a file name."
  }.toString()
  private val storage: DurableTextStorage = storage ?: DirectoryDurableTextStorage(
    requireNotNull(normalizedRegistryFile.parent) {
      "Media artifact registry file must have a parent directory."
    }.toFile(),
  )

  override fun register(
    artifacts: List<OpenCrayAttachmentArtifact>,
    source: OpenCrayMediaArtifactSource,
  ) {
    val normalized = artifacts.mapNotNull(::normalizeArtifact)
    if (normalized.isEmpty()) {
      return
    }
    val observedArtifacts = normalized.map(::observeArtifact)
    val normalizedSource = source.source.trim().takeIf(String::isNotBlank)
    storage.updateText(storageName) { currentText ->
      val record = decodeRecord(currentText)
      val now = nowEpochMs()
      val byId = record.artifacts.associateByTo(linkedMapOf()) { artifact -> artifact.artifactId }
      observedArtifacts.forEach { observed ->
        val artifact = observed.artifact
        val existing = byId[artifact.artifactId]
        byId[artifact.artifactId] = PersistedMediaArtifactRecord(
          artifactId = artifact.artifactId,
          relativePath = artifact.relativePath,
          displayName = artifact.displayName,
          kindHint = artifact.kindHint,
          mimeType = artifact.mimeType,
          durationMs = artifact.durationMs,
          waveformBars = artifact.waveformBars,
          transcriptText = artifact.transcriptText,
          contentSha256 = observed.contentSha256 ?: existing?.contentSha256,
          sizeBytes = observed.sizeBytes ?: existing?.sizeBytes,
          source = normalizedSource ?: existing?.source ?: "generated",
          runIds = mergeSet(existing?.runIds.orEmpty(), source.runId),
          toolNames = mergeSet(existing?.toolNames.orEmpty(), source.toolName),
          firstSeenAtEpochMs = existing?.firstSeenAtEpochMs ?: now,
          lastSeenAtEpochMs = now,
        )
      }
      val updated = record.copy(
        recordVersion = record.recordVersion + 1L,
        updatedAtEpochMs = now,
        artifacts = byId.values
          .sortedWith(
            compareByDescending<PersistedMediaArtifactRecord> { artifact -> artifact.lastSeenAtEpochMs }
              .thenBy { artifact -> artifact.artifactId },
          ),
      )
      DurableTextUpdate(
        text = encodeRecord(updated),
        result = Unit,
      )
    }
  }

  override fun resolve(artifactId: String): OpenCrayRegisteredMediaArtifact? {
    val normalizedArtifactId = artifactId.trim().takeIf(String::isNotBlank) ?: return null
    return loadRecord().artifacts
      .firstOrNull { artifact -> artifact.artifactId == normalizedArtifactId }
      ?.toDomain()
  }

  override fun sweep(workspaceRoot: Path): OpenCrayMediaArtifactSweepResult {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    return storage.updateText(storageName) { currentText ->
      val record = decodeRecord(currentText)
      val retained = record.artifacts.filter { artifact ->
        val relativePath = artifact.relativePath.trim().replace('\\', '/').trim('/')
        if (relativePath.isBlank()) {
          return@filter false
        }
        val path = normalizedWorkspaceRoot.resolve(relativePath).normalize()
        path.startsWith(normalizedWorkspaceRoot) && Files.isRegularFile(path)
      }
      val changed = retained.size != record.artifacts.size
      val result = OpenCrayMediaArtifactSweepResult(
        removedRecords = record.artifacts.size - retained.size,
        retainedRecords = retained.size,
      )
      DurableTextUpdate(
        text = if (changed) {
          encodeRecord(
            record.copy(
              recordVersion = record.recordVersion + 1L,
              updatedAtEpochMs = nowEpochMs(),
              artifacts = retained,
            ),
          )
        } else {
          currentText
        },
        result = result,
        write = changed,
      )
    }
  }

  private fun loadRecord(): MediaArtifactRegistryRecord =
    decodeRecord(storage.readText(storageName))

  private fun decodeRecord(text: String?): MediaArtifactRegistryRecord {
    val encoded = text?.trim().orEmpty()
    if (encoded.isBlank()) {
      return MediaArtifactRegistryRecord()
    }
    return runCatching {
      json.decodeFromString(MediaArtifactRegistryRecord.serializer(), encoded)
    }.getOrDefault(MediaArtifactRegistryRecord())
  }

  private fun encodeRecord(record: MediaArtifactRegistryRecord): String =
    json.encodeToString(MediaArtifactRegistryRecord.serializer(), record)

  private fun observeArtifact(artifact: OpenCrayAttachmentArtifact): ObservedMediaArtifact {
    val path = normalizedWorkspaceRoot.resolve(artifact.relativePath).normalize()
    val fileFacts = runCatching {
      if (path.exists() && path.isRegularFile()) {
        sha256Hex(path) to Files.size(path)
      } else {
        null
      }
    }.getOrNull()
    return ObservedMediaArtifact(
      artifact = artifact,
      contentSha256 = fileFacts?.first,
      sizeBytes = fileFacts?.second,
    )
  }

  private fun normalizeArtifact(artifact: OpenCrayAttachmentArtifact): OpenCrayAttachmentArtifact? {
    val artifactId = artifact.artifactId.trim().takeIf(String::isNotBlank) ?: return null
    val relativePath = artifact.relativePath.trim().replace('\\', '/').trim('/')
      .takeIf(String::isNotBlank) ?: return null
    val normalizedPath = normalizedWorkspaceRoot.resolve(relativePath).normalize()
    if (!normalizedPath.startsWith(normalizedWorkspaceRoot)) {
      return null
    }
    return artifact.copy(
      artifactId = artifactId,
      relativePath = relativePath,
      displayName = artifact.displayName?.trim()?.takeIf(String::isNotBlank),
      kindHint = artifact.kindHint?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotBlank),
      mimeType = artifact.mimeType?.trim()?.takeIf(String::isNotBlank),
      waveformBars = artifact.waveformBars.map { value -> value.coerceIn(0, 100) },
      transcriptText = artifact.transcriptText?.trim()?.takeIf(String::isNotBlank),
    )
  }

  private fun PersistedMediaArtifactRecord.toDomain(): OpenCrayRegisteredMediaArtifact =
    OpenCrayRegisteredMediaArtifact(
      artifact = OpenCrayAttachmentArtifact(
        artifactId = artifactId,
        relativePath = relativePath,
        displayName = displayName,
        kindHint = kindHint,
        mimeType = mimeType,
        durationMs = durationMs,
        waveformBars = waveformBars,
        transcriptText = transcriptText,
      ),
      contentSha256 = contentSha256,
      sizeBytes = sizeBytes,
      source = source,
      runIds = runIds.toSet(),
      toolNames = toolNames.toSet(),
      firstSeenAtEpochMs = firstSeenAtEpochMs,
      lastSeenAtEpochMs = lastSeenAtEpochMs,
    )

  @Serializable
  private data class MediaArtifactRegistryRecord(
    val recordVersion: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    val artifacts: List<PersistedMediaArtifactRecord> = emptyList(),
  )

  @Serializable
  private data class PersistedMediaArtifactRecord(
    val artifactId: String,
    val relativePath: String,
    val displayName: String? = null,
    val kindHint: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val waveformBars: List<Int> = emptyList(),
    val transcriptText: String? = null,
    val contentSha256: String? = null,
    val sizeBytes: Long? = null,
    val source: String = "generated",
    val runIds: List<String> = emptyList(),
    val toolNames: List<String> = emptyList(),
    val firstSeenAtEpochMs: Long = 0L,
    val lastSeenAtEpochMs: Long = 0L,
  )

  private data class ObservedMediaArtifact(
    val artifact: OpenCrayAttachmentArtifact,
    val contentSha256: String?,
    val sizeBytes: Long?,
  )

  private companion object {
    private fun mergeSet(existing: List<String>, value: String?): List<String> =
      (existing + value.orEmpty())
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

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
  }
}

fun defaultOpenCrayMediaArtifactRegistry(
  workspaceRoot: Path,
  json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
): OpenCrayMediaArtifactRegistry = FileBackedOpenCrayMediaArtifactRegistry(
  workspaceRoot = workspaceRoot,
  registryFile = workspaceRoot
    .toAbsolutePath()
    .normalize()
    .resolve(".opencray")
    .resolve("media-artifacts")
    .resolve("media-artifact-registry.json"),
  json = json,
)
