package com.opencray.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
) : OpenCrayMediaArtifactRegistry {
  private val normalizedWorkspaceRoot: Path = workspaceRoot.toAbsolutePath().normalize()
  private val lock = lockFor(registryFile)

  override fun register(
    artifacts: List<OpenCrayAttachmentArtifact>,
    source: OpenCrayMediaArtifactSource,
  ) {
    val normalized = artifacts.mapNotNull(::normalizeArtifact)
    if (normalized.isEmpty()) {
      return
    }
    synchronized(lock) {
      val record = loadRecord()
      val now = nowEpochMs()
      val byId = record.artifacts.associateByTo(linkedMapOf()) { artifact -> artifact.artifactId }
      normalized.forEach { artifact ->
        val path = normalizedWorkspaceRoot.resolve(artifact.relativePath).normalize()
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
          contentSha256 = if (path.exists() && path.isRegularFile()) sha256Hex(path) else existing?.contentSha256,
          sizeBytes = if (path.exists() && path.isRegularFile()) Files.size(path) else existing?.sizeBytes,
          source = source.source.trim().takeIf(String::isNotBlank) ?: existing?.source ?: "generated",
          runIds = mergeSet(existing?.runIds.orEmpty(), source.runId),
          toolNames = mergeSet(existing?.toolNames.orEmpty(), source.toolName),
          firstSeenAtEpochMs = existing?.firstSeenAtEpochMs ?: now,
          lastSeenAtEpochMs = now,
        )
      }
      saveRecord(
        record.copy(
          recordVersion = record.recordVersion + 1L,
          updatedAtEpochMs = now,
          artifacts = byId.values
            .sortedWith(
              compareByDescending<PersistedMediaArtifactRecord> { artifact -> artifact.lastSeenAtEpochMs }
                .thenBy { artifact -> artifact.artifactId },
            ),
        ),
      )
    }
  }

  override fun resolve(artifactId: String): OpenCrayRegisteredMediaArtifact? {
    val normalizedArtifactId = artifactId.trim().takeIf(String::isNotBlank) ?: return null
    return synchronized(lock) {
      loadRecord().artifacts
        .firstOrNull { artifact -> artifact.artifactId == normalizedArtifactId }
        ?.toDomain()
    }
  }

  override fun sweep(workspaceRoot: Path): OpenCrayMediaArtifactSweepResult {
    val normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize()
    return synchronized(lock) {
      val record = loadRecord()
      val retained = record.artifacts.filter { artifact ->
        val relativePath = artifact.relativePath.trim().replace('\\', '/').trim('/')
        if (relativePath.isBlank()) {
          return@filter false
        }
        val path = normalizedWorkspaceRoot.resolve(relativePath).normalize()
        path.startsWith(normalizedWorkspaceRoot) && Files.isRegularFile(path)
      }
      if (retained.size != record.artifacts.size) {
        saveRecord(
          record.copy(
            recordVersion = record.recordVersion + 1L,
            updatedAtEpochMs = nowEpochMs(),
            artifacts = retained,
          ),
        )
      }
      OpenCrayMediaArtifactSweepResult(
        removedRecords = record.artifacts.size - retained.size,
        retainedRecords = retained.size,
      )
    }
  }

  private fun loadRecord(): MediaArtifactRegistryRecord {
    val encoded = runCatching {
      String(Files.readAllBytes(registryFile), StandardCharsets.UTF_8)
    }.getOrNull()?.trim().orEmpty()
    if (encoded.isBlank()) {
      return MediaArtifactRegistryRecord()
    }
    return runCatching {
      json.decodeFromString(MediaArtifactRegistryRecord.serializer(), encoded)
    }.getOrDefault(MediaArtifactRegistryRecord())
  }

  private fun saveRecord(record: MediaArtifactRegistryRecord) {
    Files.createDirectories(registryFile.parent)
    Files.write(
      registryFile,
      json.encodeToString(MediaArtifactRegistryRecord.serializer(), record).toByteArray(StandardCharsets.UTF_8),
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

  private companion object {
    private val FILE_LOCKS = ConcurrentHashMap<String, Any>()

    private fun lockFor(path: Path): Any =
      FILE_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize().toString()) { Any() }

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
