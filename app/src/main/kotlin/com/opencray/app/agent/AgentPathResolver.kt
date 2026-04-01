package com.opencray.app.agent

import android.content.Context
import java.nio.file.Files
import java.nio.file.Path

internal data class AgentStoragePaths(
  val agentId: String,
  val agentRoot: Path,
  val privateRoot: Path,
  val privateSoulFile: Path,
  val privateConfigFile: Path,
  val workspaceRoot: Path,
  val chatLocalStateRoot: Path,
  val personalizationLocalStateRoot: Path,
  val queueSnapshotsRoot: Path,
  val runRecordsRoot: Path,
  val transcriptStoreRoot: Path,
  val transcriptSupplementsRoot: Path,
  val compactionRoot: Path,
  val voiceMetadataCacheRoot: Path,
) {
  val managedDirectories: List<Path>
    get() = listOf(
      privateRoot,
      workspaceRoot,
      chatLocalStateRoot,
      personalizationLocalStateRoot,
      queueSnapshotsRoot,
      runRecordsRoot,
      transcriptStoreRoot,
      transcriptSupplementsRoot,
      compactionRoot,
      voiceMetadataCacheRoot,
    )
}

internal class AgentPathResolver(
  filesRoot: Path,
) {
  private val normalizedFilesRoot = filesRoot.toAbsolutePath().normalize()

  init {
    require(
      !Files.exists(normalizedFilesRoot) || Files.isDirectory(normalizedFilesRoot),
    ) {
      "AgentPathResolver filesRoot must resolve to a directory: $normalizedFilesRoot"
    }
  }

  fun filesRoot(): Path = normalizedFilesRoot

  fun agentsRoot(): Path = normalizedFilesRoot.resolve(AGENTS_DIRECTORY_NAME).normalize()

  fun registryDirectory(): Path = normalizedFilesRoot.resolve(REGISTRY_DIRECTORY_NAME).normalize()

  fun ensureRegistryDirectory(): Path = registryDirectory().also(Files::createDirectories)

  fun resolve(agentId: String): AgentStoragePaths {
    val normalizedAgentId = normalizeAgentId(agentId)
    val agentsRoot = agentsRoot()
    val agentRoot = agentsRoot.resolve(normalizedAgentId).normalize()
    require(agentRoot.startsWith(agentsRoot)) {
      "Agent root must stay inside the agents directory."
    }
    val privateRoot = agentRoot.resolve(PRIVATE_DIRECTORY_NAME).normalize()
    val workspaceRoot = agentRoot.resolve(WORKSPACE_DIRECTORY_NAME).normalize()
    val chatLocalStateRoot = agentRoot.resolve(CHAT_LOCAL_STATE_DIRECTORY_NAME).normalize()
    val personalizationLocalStateRoot = agentRoot.resolve(PERSONALIZATION_LOCAL_STATE_DIRECTORY_NAME).normalize()
    val queueSnapshotsRoot = agentRoot.resolve(QUEUE_SNAPSHOTS_DIRECTORY_NAME).normalize()
    val runRecordsRoot = agentRoot.resolve(RUN_RECORDS_DIRECTORY_NAME).normalize()
    val transcriptStoreRoot = agentRoot.resolve(TRANSCRIPT_STORE_DIRECTORY_NAME).normalize()
    val transcriptSupplementsRoot = agentRoot.resolve(TRANSCRIPT_SUPPLEMENTS_DIRECTORY_NAME).normalize()
    val compactionRoot = agentRoot.resolve(COMPACTION_DIRECTORY_NAME).normalize()
    val voiceMetadataCacheRoot = agentRoot.resolve(VOICE_METADATA_CACHE_DIRECTORY_NAME).normalize()
    val allPaths = listOf(
      privateRoot,
      workspaceRoot,
      chatLocalStateRoot,
      personalizationLocalStateRoot,
      queueSnapshotsRoot,
      runRecordsRoot,
      transcriptStoreRoot,
      transcriptSupplementsRoot,
      compactionRoot,
      voiceMetadataCacheRoot,
    )
    require(allPaths.all { path -> path.startsWith(agentRoot) }) {
      "All resolved agent paths must stay inside the agent root."
    }
    return AgentStoragePaths(
      agentId = normalizedAgentId,
      agentRoot = agentRoot,
      privateRoot = privateRoot,
      privateSoulFile = privateRoot.resolve(PRIVATE_SOUL_FILE_NAME).normalize(),
      privateConfigFile = privateRoot.resolve(PRIVATE_CONFIG_FILE_NAME).normalize(),
      workspaceRoot = workspaceRoot,
      chatLocalStateRoot = chatLocalStateRoot,
      personalizationLocalStateRoot = personalizationLocalStateRoot,
      queueSnapshotsRoot = queueSnapshotsRoot,
      runRecordsRoot = runRecordsRoot,
      transcriptStoreRoot = transcriptStoreRoot,
      transcriptSupplementsRoot = transcriptSupplementsRoot,
      compactionRoot = compactionRoot,
      voiceMetadataCacheRoot = voiceMetadataCacheRoot,
    )
  }

  fun ensureAgentDirectories(agentId: String): AgentStoragePaths {
    val paths = resolve(agentId)
    Files.createDirectories(agentsRoot())
    paths.managedDirectories.forEach(Files::createDirectories)
    return paths
  }

  companion object {
    internal const val AGENTS_DIRECTORY_NAME: String = "agents"
    internal const val REGISTRY_DIRECTORY_NAME: String = "agent-registry"
    internal const val PRIVATE_DIRECTORY_NAME: String = "private"
    internal const val PRIVATE_SOUL_FILE_NAME: String = "SOUL.md"
    internal const val PRIVATE_CONFIG_FILE_NAME: String = "agent-config.json"
    internal const val WORKSPACE_DIRECTORY_NAME: String = "workspace"
    internal const val CHAT_LOCAL_STATE_DIRECTORY_NAME: String = "chat-local-state"
    internal const val PERSONALIZATION_LOCAL_STATE_DIRECTORY_NAME: String = "personalization-local-state"
    internal const val QUEUE_SNAPSHOTS_DIRECTORY_NAME: String = "queue-snapshots"
    internal const val RUN_RECORDS_DIRECTORY_NAME: String = "run-records"
    internal const val TRANSCRIPT_STORE_DIRECTORY_NAME: String = "transcript-store"
    internal const val TRANSCRIPT_SUPPLEMENTS_DIRECTORY_NAME: String = "transcript-supplements"
    internal const val COMPACTION_DIRECTORY_NAME: String = "compaction"
    internal const val VOICE_METADATA_CACHE_DIRECTORY_NAME: String = "voice-metadata-cache"
    private val AGENT_ID_REGEX = Regex("^[a-z0-9][a-z0-9-]*$")

    fun fromContext(context: Context): AgentPathResolver =
      AgentPathResolver(context.applicationContext.filesDir.toPath())

    internal fun normalizeAgentId(agentId: String): String {
      val normalized = agentId.trim()
      require(normalized.matches(AGENT_ID_REGEX)) {
        "Agent id must contain only lowercase letters, digits, or hyphens: $agentId"
      }
      return normalized
    }
  }
}
