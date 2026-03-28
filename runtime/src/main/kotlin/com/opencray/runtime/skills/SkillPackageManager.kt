package com.opencray.runtime.skills

import com.opencray.persistence.PersistenceJson
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillLoader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable

enum class SkillInstallSourceType(
  val wireValue: String,
) {
  LOCAL_CATALOG("local_catalog"),
  LOCAL_PATH("local_path"),
  REMOTE_GITHUB("remote_github"),
  REMOTE_GITLAB("remote_gitlab"),
}

@Serializable
data class SkillInstallManifestEntry(
  val skillId: String,
  val installRootPath: String,
  val sourceType: String,
  val sourceRef: String,
  val sourcePath: String? = null,
  val selectedSkillName: String,
  val sourceRelativePath: String? = null,
  val resolvedRevision: String? = null,
  val resolvedCommitSha: String? = null,
  val contentHash: String = "",
  val installedAtEpochMs: Long,
  val updatedAtEpochMs: Long,
  val lastCheckedAtEpochMs: Long? = null,
  val installStrategy: String = "copy",
) {
  init {
    require(skillId.isNotBlank()) { "SkillInstallManifestEntry skillId must not be blank." }
    require(installRootPath.isNotBlank()) { "SkillInstallManifestEntry installRootPath must not be blank." }
    require(sourceType.isNotBlank()) { "SkillInstallManifestEntry sourceType must not be blank." }
    require(sourceRef.isNotBlank()) { "SkillInstallManifestEntry sourceRef must not be blank." }
    require(selectedSkillName.isNotBlank()) { "SkillInstallManifestEntry selectedSkillName must not be blank." }
    require(updatedAtEpochMs >= installedAtEpochMs) {
      "SkillInstallManifestEntry updatedAtEpochMs must be >= installedAtEpochMs."
    }
  }
}

@Serializable
data class SkillInstallManifest(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val installations: List<SkillInstallManifestEntry> = emptyList(),
)

data class SkillPackageInstallResult(
  val skillId: String,
  val installedSkill: LoadedSkill,
  val targetDirectory: File,
  val manifestEntry: SkillInstallManifestEntry,
)

data class SkillPackageBatchInstallEntry(
  val requestedSkillName: String,
  val installedSkillId: String? = null,
  val manifestEntry: SkillInstallManifestEntry? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = installedSkillId != null && errorCode == null
}

data class SkillPackageBatchInstallResult(
  val sourceType: String,
  val sourceRef: String,
  val entries: List<SkillPackageBatchInstallEntry>,
  val sourcePath: String? = null,
  val resolvedRevision: String? = null,
  val resolvedCommitSha: String? = null,
) {
  val requestedCount: Int
    get() = entries.size

  val installedCount: Int
    get() = entries.count(SkillPackageBatchInstallEntry::succeeded)

  val failedCount: Int
    get() = requestedCount - installedCount
}

data class SkillPackageRemoveResult(
  val skillId: String,
  val removedDirectory: File,
  val manifestEntryRemoved: Boolean,
)

data class SkillSourceInspectionCandidate(
  val name: String,
  val description: String,
  val relativePath: String,
)

data class SkillSourceInspectionResult(
  val sourceType: String,
  val sourceRef: String,
  val candidates: List<SkillSourceInspectionCandidate>,
  val sourcePath: String? = null,
  val resolvedRevision: String? = null,
  val resolvedCommitSha: String? = null,
)

data class SkillSourceInspectionAttempt(
  val result: SkillSourceInspectionResult? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = result != null && errorCode == null
}

data class SkillPackageInstructionsResult(
  val skill: LoadedSkill,
  val sourcePath: String,
  val resolvedRevision: String? = null,
  val resolvedCommitSha: String? = null,
)

data class SkillPackageInstructionsAttempt(
  val result: SkillPackageInstructionsResult? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = result != null && errorCode == null
}

enum class SkillPackageCheckStatus(
  val wireValue: String,
) {
  UP_TO_DATE("up_to_date"),
  UPDATE_AVAILABLE("update_available"),
  SOURCE_UNAVAILABLE("source_unavailable"),
  UNSUPPORTED_SOURCE("unsupported_source"),
}

data class SkillPackageCheckResult(
  val skillId: String,
  val sourceType: String,
  val sourceRef: String,
  val status: SkillPackageCheckStatus,
  val checkedAtEpochMs: Long,
  val manifestEntry: SkillInstallManifestEntry? = null,
  val installedRevision: String? = null,
  val installedCommitSha: String? = null,
  val latestRevision: String? = null,
  val latestCommitSha: String? = null,
  val installedContentHash: String = "",
  val latestContentHash: String? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
)

data class SkillPackageCheckReport(
  val results: List<SkillPackageCheckResult>,
) {
  val upToDateCount: Int
    get() = results.count { result -> result.status == SkillPackageCheckStatus.UP_TO_DATE }

  val updateAvailableCount: Int
    get() = results.count { result -> result.status == SkillPackageCheckStatus.UPDATE_AVAILABLE }

  val sourceUnavailableCount: Int
    get() = results.count { result -> result.status == SkillPackageCheckStatus.SOURCE_UNAVAILABLE }

  val unsupportedCount: Int
    get() = results.count { result -> result.status == SkillPackageCheckStatus.UNSUPPORTED_SOURCE }
}

enum class SkillPackageUpdateStatus(
  val wireValue: String,
) {
  UPDATED("updated"),
  SKIPPED("skipped"),
  FAILED("failed"),
}

data class SkillPackageUpdateResult(
  val skillId: String,
  val sourceType: String,
  val sourceRef: String,
  val status: SkillPackageUpdateStatus,
  val manifestEntry: SkillInstallManifestEntry? = null,
  val checkStatus: SkillPackageCheckStatus? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
)

data class SkillPackageUpdateReport(
  val results: List<SkillPackageUpdateResult>,
) {
  val updatedCount: Int
    get() = results.count { result -> result.status == SkillPackageUpdateStatus.UPDATED }

  val skippedCount: Int
    get() = results.count { result -> result.status == SkillPackageUpdateStatus.SKIPPED }

  val failedCount: Int
    get() = results.count { result -> result.status == SkillPackageUpdateStatus.FAILED }
}

class SkillInstallManifestStore private constructor(
  private val storage: DurableTextStorage,
  private val fileName: String,
) {
  fun load(): SkillInstallManifest {
    val encoded = storage.readText(fileName).orEmpty().trim()
    if (encoded.isBlank()) {
      return SkillInstallManifest()
    }
    return PersistenceJson.instance.decodeFromString(
      deserializer = SkillInstallManifest.serializer(),
      string = encoded,
    )
  }

  fun save(manifest: SkillInstallManifest) {
    storage.writeText(
      fileName,
      PersistenceJson.instance.encodeToString(
        serializer = SkillInstallManifest.serializer(),
        value = manifest,
      ),
    )
  }

  companion object {
    fun fromFile(file: File): SkillInstallManifestStore {
      val absoluteFile = file.absoluteFile
      val parentDirectory = absoluteFile.parentFile
        ?: throw IllegalArgumentException("Skill manifest file must have a parent directory.")
      return SkillInstallManifestStore(
        storage = DirectoryDurableTextStorage(parentDirectory),
        fileName = absoluteFile.name,
      )
    }
  }
}

class SkillPackageManager(
  private val managedRoot: File,
  private val catalogRoot: File,
  private val manifestStore: SkillInstallManifestStore,
  private val remoteSearchClient: RemoteSkillSearchClient = SkillsApiRemoteSkillSearchClient(),
  private val sourceResolver: SkillSourceResolver = SkillSourceResolver(),
  private val remoteSourceInspector: RemoteSkillSourceInspector = HostedGitRemoteSkillSourceInspector(),
  private val remoteSourceFetcher: RemoteSkillSourceFetcher = HostedGitArchiveRemoteSkillSourceFetcher(),
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  fun managedRootPath(): File = managedRoot

  fun catalogRootPath(): File = catalogRoot

  fun stagingRootPath(): File = File(managedRoot.parentFile ?: managedRoot, ".skills-staging")

  fun policyReadRoots(): Set<File> = setOf(managedRoot, catalogRoot)

  fun policyWriteRoots(): Set<File> = setOf(managedRoot, stagingRootPath())

  fun listCatalogSkills(): List<LoadedSkill> = loadSkillsFrom(catalogRoot)

  fun listManagedSkills(): List<LoadedSkill> = loadSkillsFrom(managedRoot)

  fun listInstallations(): List<SkillInstallManifestEntry> =
    manifestStore.load().installations.sortedBy(SkillInstallManifestEntry::skillId)

  fun searchRemoteSkills(
    query: String,
    limit: Int,
  ): RemoteSkillSearchResponse = remoteSearchClient.search(
    RemoteSkillSearchRequest(
      query = query,
      limit = limit,
    ),
  )

  fun resolveRemoteSource(
    sourceRef: String,
    selectedSkillName: String? = null,
  ): ResolvedRemoteSkillSource? = sourceResolver.resolve(
    sourceRef = sourceRef,
    requestedSkillName = selectedSkillName,
  )

  fun resolveCatalogInstallTarget(skillId: String): File? {
    val normalizedSkillId = skillId.trim()
    if (normalizedSkillId.isEmpty()) {
      return null
    }
    val catalogSkill = loadCatalogSkill(normalizedSkillId) ?: return null
    val sourceDirectory = File(catalogSkill.source.skillDirectoryPath)
    if (!sourceDirectory.isDirectory) {
      return null
    }
    return File(managedRoot, sourceDirectory.name)
  }

  fun resolveInstalledSkillDirectory(skillId: String): File? {
    val normalizedSkillId = skillId.trim()
    if (normalizedSkillId.isEmpty()) {
      return null
    }
    val manifestEntry = refreshManifest().installations.firstOrNull { entry ->
      entry.skillId == normalizedSkillId
    }
    if (manifestEntry != null) {
      return File(manifestEntry.installRootPath)
    }
    return loadManagedSkill(normalizedSkillId)?.let { skill ->
      File(skill.source.skillDirectoryPath)
    }
  }

  fun refreshManifest(): SkillInstallManifest {
    val existing = manifestStore.load()
    val normalizedInstallations = existing.installations
      .filter { entry ->
        val installRoot = File(entry.installRootPath)
        installRoot.exists() && isInsideManagedRoot(installRoot)
      }
      .sortedBy(SkillInstallManifestEntry::skillId)
    if (normalizedInstallations == existing.installations) {
      return existing
    }
    val now = clock()
    val updated = existing.copy(
      recordVersion = existing.recordVersion + 1L,
      updatedAtEpochMs = now,
      installations = normalizedInstallations,
    )
    manifestStore.save(updated)
    return updated
  }

  fun installFromCatalog(skillId: String): SkillPackageInstallResult? {
    val normalizedSkillId = skillId.trim()
    if (normalizedSkillId.isEmpty()) {
      return null
    }
    val manifest = refreshManifest()
    val catalogSkill = loadCatalogSkill(normalizedSkillId) ?: return null
    val sourceDirectory = File(catalogSkill.source.skillDirectoryPath)
    if (!sourceDirectory.isDirectory) {
      return null
    }
    ensureManagedRoot()

    val staged = stageSkillDirectory(sourceDirectory)
    try {
      return installValidatedSkill(
        manifest = manifest,
        stagedSkillDirectory = staged.skillDirectory,
        expectedSkillId = normalizedSkillId,
        sourceType = SkillInstallSourceType.LOCAL_CATALOG.wireValue,
        sourceRef = normalizedSkillId,
        sourcePath = canonicalInvariantPath(sourceDirectory),
        sourceRelativePath = catalogSkill.source.relativePath,
      )
    } finally {
      staged.rootDirectory.deleteRecursively()
    }
  }

  fun installFromRemoteSource(
    sourceRef: String,
    selectedSkillName: String? = null,
  ): SkillPackageInstallAttempt {
    val resolvedSource = resolveRemoteSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    ) ?: return SkillPackageInstallAttempt(
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = "Source '$sourceRef' is not a supported remote skill source.",
    )
    return try {
      val manifest = refreshManifest()
      ensureManagedRoot()
      val stagingRoot = File(stagingRootPath(), UUID.randomUUID().toString())
      val fetched = remoteSourceFetcher.fetch(
        source = resolvedSource,
        stagingRoot = stagingRoot,
      )
      try {
        val selection = selectSkillFromRoot(
          searchRoot = fetched.searchRoot,
          selectedSkillName = resolvedSource.selectedSkillName,
          sourceHint = resolvedSource.repo,
          sourceRef = resolvedSource.requestedSourceRef,
        )
        val skillDirectory = File(selection.source.skillDirectoryPath)
        SkillPackageInstallAttempt(
          result = installValidatedSkill(
            manifest = manifest,
            stagedSkillDirectory = skillDirectory,
            expectedSkillId = selection.name,
            sourceType = resolvedSource.sourceType.wireValue,
            sourceRef = resolvedSource.requestedSourceRef,
            sourcePath = fetched.repositoryUrl,
            sourceRelativePath = relativePathFromRoot(
              root = fetched.repositoryRoot,
              file = selection.source.skillFile,
            ),
            resolvedRevision = fetched.resolvedRevision,
            resolvedCommitSha = fetched.resolvedCommitSha,
          ),
        )
      } finally {
        stagingRoot.deleteRecursively()
      }
    } catch (error: SkillPackageException) {
      SkillPackageInstallAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillPackageInstallAttempt(
        errorCode = "SKILL_INSTALL_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun installFromRemoteSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String> = emptyList(),
    installAll: Boolean = false,
  ): SkillPackageBatchInstallAttempt {
    val resolvedSource = resolveRemoteSource(sourceRef = sourceRef)
      ?: return SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_SOURCE_UNSUPPORTED",
        errorMessage = "Source '$sourceRef' is not a supported remote skill source.",
      )
    return try {
      ensureManagedRoot()
      val stagingRoot = File(stagingRootPath(), UUID.randomUUID().toString())
      val fetched = remoteSourceFetcher.fetch(
        source = resolvedSource,
        stagingRoot = stagingRoot,
      )
      try {
        val selections = selectSkillsFromRoot(
          searchRoot = fetched.searchRoot,
          requestedSkillNames = selectedSkillNames,
          installAll = installAll,
          sourceHint = resolvedSource.selectedSkillName ?: resolvedSource.repo,
          sourceRef = resolvedSource.requestedSourceRef,
        )
        SkillPackageBatchInstallAttempt(
          result = installSelectedSkills(
            selections = selections,
            sourceType = resolvedSource.sourceType.wireValue,
            sourceRef = resolvedSource.requestedSourceRef,
            sourcePath = fetched.repositoryUrl,
            relativeRoot = fetched.repositoryRoot,
            resolvedRevision = fetched.resolvedRevision,
            resolvedCommitSha = fetched.resolvedCommitSha,
          ),
        )
      } finally {
        stagingRoot.deleteRecursively()
      }
    } catch (error: SkillPackageException) {
      SkillPackageBatchInstallAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun inspectRemoteSource(sourceRef: String): SkillSourceInspectionAttempt {
    val resolvedSource = resolveRemoteSource(sourceRef = sourceRef)
      ?: return SkillSourceInspectionAttempt(
        errorCode = "SKILL_SOURCE_UNSUPPORTED",
        errorMessage = "Source '$sourceRef' is not a supported remote skill source.",
      )
    return try {
      val stagingRoot = File(stagingRootPath(), UUID.randomUUID().toString())
      val fetched = remoteSourceFetcher.fetch(
        source = resolvedSource,
        stagingRoot = stagingRoot,
      )
      try {
        SkillSourceInspectionAttempt(
          result = inspectSkillRoot(
            searchRoot = fetched.searchRoot,
            relativeRoot = fetched.repositoryRoot,
            sourceType = resolvedSource.sourceType.wireValue,
            sourceRef = resolvedSource.requestedSourceRef,
            sourcePath = fetched.repositoryUrl,
            resolvedRevision = fetched.resolvedRevision,
            resolvedCommitSha = fetched.resolvedCommitSha,
          ),
        )
      } finally {
        stagingRoot.deleteRecursively()
      }
    } catch (error: SkillPackageException) {
      SkillSourceInspectionAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillSourceInspectionAttempt(
        errorCode = "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun loadRemoteSkillInstructions(
    sourceRef: String,
    selectedSkillName: String? = null,
  ): SkillPackageInstructionsAttempt {
    val resolvedSource = resolveRemoteSource(
      sourceRef = sourceRef,
      selectedSkillName = selectedSkillName,
    ) ?: return SkillPackageInstructionsAttempt(
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = "Source '$sourceRef' is not a supported remote skill source.",
    )
    return try {
      val stagingRoot = File(stagingRootPath(), UUID.randomUUID().toString())
      val fetched = remoteSourceFetcher.fetch(
        source = resolvedSource,
        stagingRoot = stagingRoot,
      )
      try {
        val selection = selectSkillFromRoot(
          searchRoot = fetched.searchRoot,
          selectedSkillName = selectedSkillName ?: resolvedSource.selectedSkillName,
          sourceHint = resolvedSource.repo,
          sourceRef = resolvedSource.requestedSourceRef,
        )
        SkillPackageInstructionsAttempt(
          result = SkillPackageInstructionsResult(
            skill = selection,
            sourcePath = fetched.repositoryUrl,
            resolvedRevision = fetched.resolvedRevision,
            resolvedCommitSha = fetched.resolvedCommitSha,
          ),
        )
      } finally {
        stagingRoot.deleteRecursively()
      }
    } catch (error: SkillPackageException) {
      SkillPackageInstructionsAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillPackageInstructionsAttempt(
        errorCode = "SKILL_INSTRUCTIONS_LOAD_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun installFromLocalSource(
    sourcePath: File,
    sourceRef: String,
    selectedSkillName: String? = null,
  ): SkillPackageInstallAttempt {
    val normalizedSource = normalizeLocalSourceRoot(sourcePath)
      ?: return SkillPackageInstallAttempt(
        errorCode = "SKILL_SOURCE_NOT_FOUND",
        errorMessage = "Local skill source '$sourceRef' was not found.",
      )
    return try {
      val manifest = refreshManifest()
      ensureManagedRoot()
      val selection = selectSkillFromRoot(
        searchRoot = normalizedSource,
        selectedSkillName = selectedSkillName,
        sourceHint = normalizedSource.name,
        sourceRef = sourceRef,
      )
      val sourceSkillDirectory = File(selection.source.skillDirectoryPath)
      val staged = stageSkillDirectory(sourceSkillDirectory)
      try {
        SkillPackageInstallAttempt(
          result = installValidatedSkill(
            manifest = manifest,
            stagedSkillDirectory = staged.skillDirectory,
            expectedSkillId = selection.name,
            sourceType = SkillInstallSourceType.LOCAL_PATH.wireValue,
            sourceRef = sourceRef,
            sourcePath = canonicalInvariantPath(normalizedSource),
            sourceRelativePath = relativePathFromRoot(
              root = normalizedSource,
              file = selection.source.skillFile,
            ) ?: selection.source.relativePath,
          ),
        )
      } finally {
        staged.rootDirectory.deleteRecursively()
      }
    } catch (error: SkillPackageException) {
      SkillPackageInstallAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillPackageInstallAttempt(
        errorCode = "SKILL_INSTALL_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun installFromLocalSourceBatch(
    sourcePath: File,
    sourceRef: String,
    selectedSkillNames: List<String> = emptyList(),
    installAll: Boolean = false,
  ): SkillPackageBatchInstallAttempt {
    val normalizedSource = normalizeLocalSourceRoot(sourcePath)
      ?: return SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_SOURCE_NOT_FOUND",
        errorMessage = "Local skill source '$sourceRef' was not found.",
      )
    return try {
      ensureManagedRoot()
      val selections = selectSkillsFromRoot(
        searchRoot = normalizedSource,
        requestedSkillNames = selectedSkillNames,
        installAll = installAll,
        sourceHint = normalizedSource.name,
        sourceRef = sourceRef,
      )
      SkillPackageBatchInstallAttempt(
        result = installSelectedSkills(
          selections = selections,
          sourceType = SkillInstallSourceType.LOCAL_PATH.wireValue,
          sourceRef = sourceRef,
          sourcePath = canonicalInvariantPath(normalizedSource),
          relativeRoot = normalizedSource,
        ),
      )
    } catch (error: SkillPackageException) {
      SkillPackageBatchInstallAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_BATCH_INSTALL_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun inspectLocalSource(
    sourcePath: File,
    sourceRef: String,
  ): SkillSourceInspectionAttempt {
    val normalizedSource = normalizeLocalSourceRoot(sourcePath)
      ?: return SkillSourceInspectionAttempt(
        errorCode = "SKILL_SOURCE_NOT_FOUND",
        errorMessage = "Local skill source '$sourceRef' was not found.",
      )
    return try {
      SkillSourceInspectionAttempt(
        result = inspectSkillRoot(
          searchRoot = normalizedSource,
          relativeRoot = normalizedSource,
          sourceType = SkillInstallSourceType.LOCAL_PATH.wireValue,
          sourceRef = sourceRef,
          sourcePath = canonicalInvariantPath(normalizedSource),
        ),
      )
    } catch (error: SkillPackageException) {
      SkillSourceInspectionAttempt(
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    } catch (error: Exception) {
      SkillSourceInspectionAttempt(
        errorCode = "SKILL_SOURCE_INSPECTION_FAILED",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }
  }

  fun checkInstalledSkills(skillId: String? = null): SkillPackageCheckReport {
    val normalizedSkillId = skillId?.trim()?.takeIf(String::isNotBlank)
    val manifest = refreshManifest()
    val managedSkillIds = listManagedSkills().mapTo(linkedSetOf()) { skill -> skill.name }
    val manifestEntries = manifest.installations
      .filter { entry -> normalizedSkillId == null || entry.skillId == normalizedSkillId }
      .sortedBy(SkillInstallManifestEntry::skillId)
    val checkedAtEpochMs = clock()
    val updatedEntriesById = manifest.installations.associateBy(SkillInstallManifestEntry::skillId).toMutableMap()
    val results = manifestEntries.map { entry ->
      updatedEntriesById[entry.skillId] = entry.copy(lastCheckedAtEpochMs = checkedAtEpochMs)
      checkManifestEntry(
        entry = entry,
        checkedAtEpochMs = checkedAtEpochMs,
      )
    }.toMutableList()
    if (manifestEntries.isNotEmpty()) {
      manifestStore.save(
        manifest.copy(
          recordVersion = manifest.recordVersion + 1L,
          updatedAtEpochMs = checkedAtEpochMs,
          installations = updatedEntriesById.values.sortedBy(SkillInstallManifestEntry::skillId),
        ),
      )
    }
    val missingProvenanceSkillIds = when {
      normalizedSkillId != null && normalizedSkillId in managedSkillIds && manifestEntries.isEmpty() ->
        listOf(normalizedSkillId)

      normalizedSkillId == null -> managedSkillIds
        .filterNot { managedSkillId -> managedSkillId in updatedEntriesById }

      else -> emptyList()
    }
    missingProvenanceSkillIds
      .sorted()
      .mapTo(results) { unmanagedSkillId ->
        SkillPackageCheckResult(
          skillId = unmanagedSkillId,
          sourceType = "unknown",
          sourceRef = unmanagedSkillId,
          status = SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
          checkedAtEpochMs = checkedAtEpochMs,
          errorCode = "SKILL_PROVENANCE_MISSING",
          errorMessage = "Skill '$unmanagedSkillId' does not have installation provenance.",
        )
      }
    return SkillPackageCheckReport(
      results = results.sortedBy(SkillPackageCheckResult::skillId),
    )
  }

  fun updateInstalledSkills(skillId: String? = null): SkillPackageUpdateReport =
    updateInstalledSkills(
      checkReport = checkInstalledSkills(skillId),
    )

  fun updateInstalledSkills(checkReport: SkillPackageCheckReport): SkillPackageUpdateReport {
    val results = checkReport.results.map { checkResult ->
      when (checkResult.status) {
        SkillPackageCheckStatus.UP_TO_DATE -> SkillPackageUpdateResult(
          skillId = checkResult.skillId,
          sourceType = checkResult.sourceType,
          sourceRef = checkResult.sourceRef,
          status = SkillPackageUpdateStatus.SKIPPED,
          manifestEntry = checkResult.manifestEntry,
          checkStatus = checkResult.status,
        )

        SkillPackageCheckStatus.UPDATE_AVAILABLE -> updateCheckedSkill(checkResult)

        SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
        SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
        -> SkillPackageUpdateResult(
          skillId = checkResult.skillId,
          sourceType = checkResult.sourceType,
          sourceRef = checkResult.sourceRef,
          status = SkillPackageUpdateStatus.FAILED,
          manifestEntry = checkResult.manifestEntry,
          checkStatus = checkResult.status,
          errorCode = checkResult.errorCode ?: checkResult.status.wireValue.uppercase(),
          errorMessage = checkResult.errorMessage,
        )
      }
    }
    return SkillPackageUpdateReport(
      results = results.sortedBy(SkillPackageUpdateResult::skillId),
    )
  }

  fun removeInstalledSkill(skillId: String): SkillPackageRemoveResult? {
    val normalizedSkillId = skillId.trim()
    if (normalizedSkillId.isEmpty()) {
      return null
    }
    val manifest = refreshManifest()
    val installedSkillDirectory = manifest.installations
      .firstOrNull { entry -> entry.skillId == normalizedSkillId }
      ?.let { entry -> File(entry.installRootPath) }
      ?: loadManagedSkill(normalizedSkillId)?.let { skill -> File(skill.source.skillDirectoryPath) }
      ?: return null
    if (!isInsideManagedRoot(installedSkillDirectory)) {
      return null
    }
    if (!installedSkillDirectory.exists() || !installedSkillDirectory.deleteRecursively()) {
      return null
    }
    val now = clock()
    val updatedInstallations = manifest.installations
      .filterNot { entry -> entry.skillId == normalizedSkillId }
      .sortedBy(SkillInstallManifestEntry::skillId)
    val manifestEntryRemoved = updatedInstallations.size != manifest.installations.size
    if (manifestEntryRemoved) {
      manifestStore.save(
        manifest.copy(
          recordVersion = manifest.recordVersion + 1L,
          updatedAtEpochMs = now,
          installations = updatedInstallations,
        ),
      )
    }
    return SkillPackageRemoveResult(
      skillId = normalizedSkillId,
      removedDirectory = installedSkillDirectory,
      manifestEntryRemoved = manifestEntryRemoved,
    )
  }

  private fun checkManifestEntry(
    entry: SkillInstallManifestEntry,
    checkedAtEpochMs: Long,
  ): SkillPackageCheckResult = when (entry.sourceType) {
    SkillInstallSourceType.LOCAL_CATALOG.wireValue,
    SkillInstallSourceType.LOCAL_PATH.wireValue,
    -> checkLocalManifestEntry(
      entry = entry,
      checkedAtEpochMs = checkedAtEpochMs,
    )

    SkillInstallSourceType.REMOTE_GITHUB.wireValue,
    SkillInstallSourceType.REMOTE_GITLAB.wireValue,
    -> checkRemoteManifestEntry(
      entry = entry,
      checkedAtEpochMs = checkedAtEpochMs,
    )

    else -> SkillPackageCheckResult(
      skillId = entry.skillId,
      sourceType = entry.sourceType,
      sourceRef = entry.sourceRef,
      status = SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
      checkedAtEpochMs = checkedAtEpochMs,
      manifestEntry = entry,
      installedRevision = entry.resolvedRevision,
      installedCommitSha = entry.resolvedCommitSha,
      installedContentHash = entry.contentHash,
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = "Source type '${entry.sourceType}' is not supported for update checks.",
    )
  }

  private fun checkLocalManifestEntry(
    entry: SkillInstallManifestEntry,
    checkedAtEpochMs: Long,
  ): SkillPackageCheckResult {
    val sourcePath = entry.sourcePath?.trim()?.takeIf { candidate -> candidate.isNotBlank() }
      ?: return unavailableLocalManifestEntry(
        entry = entry,
        checkedAtEpochMs = checkedAtEpochMs,
        errorCode = "SKILL_SOURCE_NOT_FOUND",
        errorMessage = "Local source for '${entry.skillId}' is unavailable.",
      )
    val normalizedSource = normalizeLocalSourceRoot(File(sourcePath))
      ?: return unavailableLocalManifestEntry(
        entry = entry,
        checkedAtEpochMs = checkedAtEpochMs,
        errorCode = "SKILL_SOURCE_NOT_FOUND",
        errorMessage = "Local source '$sourcePath' was not found for '${entry.skillId}'.",
      )
    return try {
      val selectedSkill = selectSkillFromRoot(
        searchRoot = normalizedSource,
        selectedSkillName = entry.selectedSkillName,
        sourceHint = normalizedSource.name,
        sourceRef = entry.sourceRef,
      )
      val sourceSkillDirectory = File(selectedSkill.source.skillDirectoryPath)
      val latestContentHash = computeDirectoryHash(sourceSkillDirectory)
      SkillPackageCheckResult(
        skillId = entry.skillId,
        sourceType = entry.sourceType,
        sourceRef = entry.sourceRef,
        status = if (entry.contentHash == latestContentHash) {
          SkillPackageCheckStatus.UP_TO_DATE
        } else {
          SkillPackageCheckStatus.UPDATE_AVAILABLE
        },
        checkedAtEpochMs = checkedAtEpochMs,
        manifestEntry = entry,
        installedRevision = entry.resolvedRevision,
        installedCommitSha = entry.resolvedCommitSha,
        installedContentHash = entry.contentHash,
        latestContentHash = latestContentHash,
      )
    } catch (error: SkillPackageException) {
      unavailableLocalManifestEntry(
        entry = entry,
        checkedAtEpochMs = checkedAtEpochMs,
        errorCode = error.errorCode,
        errorMessage = error.message,
      )
    }
  }

  private fun unavailableLocalManifestEntry(
    entry: SkillInstallManifestEntry,
    checkedAtEpochMs: Long,
    errorCode: String,
    errorMessage: String,
  ): SkillPackageCheckResult = SkillPackageCheckResult(
    skillId = entry.skillId,
    sourceType = entry.sourceType,
    sourceRef = entry.sourceRef,
    status = SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
    checkedAtEpochMs = checkedAtEpochMs,
    manifestEntry = entry,
    installedRevision = entry.resolvedRevision,
    installedCommitSha = entry.resolvedCommitSha,
    installedContentHash = entry.contentHash,
    errorCode = errorCode,
    errorMessage = errorMessage,
  )

  private fun checkRemoteManifestEntry(
    entry: SkillInstallManifestEntry,
    checkedAtEpochMs: Long,
  ): SkillPackageCheckResult {
    val resolvedSource = resolveRemoteSource(
      sourceRef = entry.sourceRef,
      selectedSkillName = entry.selectedSkillName,
    ) ?: return SkillPackageCheckResult(
      skillId = entry.skillId,
      sourceType = entry.sourceType,
      sourceRef = entry.sourceRef,
      status = SkillPackageCheckStatus.UNSUPPORTED_SOURCE,
      checkedAtEpochMs = checkedAtEpochMs,
      manifestEntry = entry,
      installedRevision = entry.resolvedRevision,
      installedCommitSha = entry.resolvedCommitSha,
      installedContentHash = entry.contentHash,
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = "Remote source '${entry.sourceRef}' is not supported.",
    )
    val versionAttempt = remoteSourceInspector.inspect(resolvedSource)
    val version = versionAttempt.version ?: return SkillPackageCheckResult(
      skillId = entry.skillId,
      sourceType = entry.sourceType,
      sourceRef = entry.sourceRef,
      status = SkillPackageCheckStatus.SOURCE_UNAVAILABLE,
      checkedAtEpochMs = checkedAtEpochMs,
      manifestEntry = entry,
      installedRevision = entry.resolvedRevision,
      installedCommitSha = entry.resolvedCommitSha,
      installedContentHash = entry.contentHash,
      errorCode = versionAttempt.errorCode ?: "REMOTE_SKILL_SOURCE_UNAVAILABLE",
      errorMessage = versionAttempt.errorMessage ?: "Remote source '${entry.sourceRef}' is unavailable.",
    )
    val hasUpdate = when {
      !entry.resolvedCommitSha.isNullOrBlank() && !version.resolvedCommitSha.isNullOrBlank() ->
        entry.resolvedCommitSha != version.resolvedCommitSha

      !entry.resolvedRevision.isNullOrBlank() ->
        entry.resolvedRevision != version.resolvedRevision

      else -> false
    }
    return SkillPackageCheckResult(
      skillId = entry.skillId,
      sourceType = entry.sourceType,
      sourceRef = entry.sourceRef,
      status = if (hasUpdate) {
        SkillPackageCheckStatus.UPDATE_AVAILABLE
      } else {
        SkillPackageCheckStatus.UP_TO_DATE
      },
      checkedAtEpochMs = checkedAtEpochMs,
      manifestEntry = entry,
      installedRevision = entry.resolvedRevision,
      installedCommitSha = entry.resolvedCommitSha,
      latestRevision = version.resolvedRevision,
      latestCommitSha = version.resolvedCommitSha,
      installedContentHash = entry.contentHash,
    )
  }

  private fun updateCheckedSkill(
    checkResult: SkillPackageCheckResult,
  ): SkillPackageUpdateResult {
    val manifestEntry = checkResult.manifestEntry
      ?: return SkillPackageUpdateResult(
        skillId = checkResult.skillId,
        sourceType = checkResult.sourceType,
        sourceRef = checkResult.sourceRef,
        status = SkillPackageUpdateStatus.FAILED,
        checkStatus = checkResult.status,
        errorCode = "SKILL_PROVENANCE_MISSING",
        errorMessage = "Skill '${checkResult.skillId}' does not have installation provenance.",
      )
    return when (manifestEntry.sourceType) {
      SkillInstallSourceType.LOCAL_CATALOG.wireValue -> {
        val result = installFromCatalog(manifestEntry.skillId)
        if (result != null) {
          SkillPackageUpdateResult(
            skillId = result.skillId,
            sourceType = manifestEntry.sourceType,
            sourceRef = manifestEntry.sourceRef,
            status = SkillPackageUpdateStatus.UPDATED,
            manifestEntry = result.manifestEntry,
            checkStatus = checkResult.status,
          )
        } else {
          SkillPackageUpdateResult(
            skillId = manifestEntry.skillId,
            sourceType = manifestEntry.sourceType,
            sourceRef = manifestEntry.sourceRef,
            status = SkillPackageUpdateStatus.FAILED,
            manifestEntry = manifestEntry,
            checkStatus = checkResult.status,
            errorCode = "SKILL_INSTALL_FAILED",
            errorMessage = "Failed to update '${manifestEntry.skillId}' from the local catalog.",
          )
        }
      }

      SkillInstallSourceType.LOCAL_PATH.wireValue -> {
        val sourcePath = manifestEntry.sourcePath?.trim()?.takeIf { candidate -> candidate.isNotBlank() }
          ?: return SkillPackageUpdateResult(
            skillId = manifestEntry.skillId,
            sourceType = manifestEntry.sourceType,
            sourceRef = manifestEntry.sourceRef,
            status = SkillPackageUpdateStatus.FAILED,
            manifestEntry = manifestEntry,
            checkStatus = checkResult.status,
            errorCode = "SKILL_SOURCE_NOT_FOUND",
            errorMessage = "Local source for '${manifestEntry.skillId}' is unavailable.",
          )
        val attempt = installFromLocalSource(
          sourcePath = File(sourcePath),
          sourceRef = manifestEntry.sourceRef,
          selectedSkillName = manifestEntry.selectedSkillName,
        )
        attempt.result?.let { result ->
          SkillPackageUpdateResult(
            skillId = result.skillId,
            sourceType = manifestEntry.sourceType,
            sourceRef = manifestEntry.sourceRef,
            status = SkillPackageUpdateStatus.UPDATED,
            manifestEntry = result.manifestEntry,
            checkStatus = checkResult.status,
          )
        } ?: SkillPackageUpdateResult(
          skillId = manifestEntry.skillId,
          sourceType = manifestEntry.sourceType,
          sourceRef = manifestEntry.sourceRef,
          status = SkillPackageUpdateStatus.FAILED,
          manifestEntry = manifestEntry,
          checkStatus = checkResult.status,
          errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
          errorMessage = attempt.errorMessage ?: "Failed to update '${manifestEntry.skillId}' from the local source.",
        )
      }

      SkillInstallSourceType.REMOTE_GITHUB.wireValue,
      SkillInstallSourceType.REMOTE_GITLAB.wireValue,
      -> {
        val attempt = installFromRemoteSource(
          sourceRef = manifestEntry.sourceRef,
          selectedSkillName = manifestEntry.selectedSkillName,
        )
        attempt.result?.let { result ->
          SkillPackageUpdateResult(
            skillId = result.skillId,
            sourceType = manifestEntry.sourceType,
            sourceRef = manifestEntry.sourceRef,
            status = SkillPackageUpdateStatus.UPDATED,
            manifestEntry = result.manifestEntry,
            checkStatus = checkResult.status,
          )
        } ?: SkillPackageUpdateResult(
          skillId = manifestEntry.skillId,
          sourceType = manifestEntry.sourceType,
          sourceRef = manifestEntry.sourceRef,
          status = SkillPackageUpdateStatus.FAILED,
          manifestEntry = manifestEntry,
          checkStatus = checkResult.status,
          errorCode = attempt.errorCode ?: "SKILL_INSTALL_FAILED",
          errorMessage = attempt.errorMessage ?: "Failed to update '${manifestEntry.skillId}' from the remote source.",
        )
      }

      else -> SkillPackageUpdateResult(
        skillId = manifestEntry.skillId,
        sourceType = manifestEntry.sourceType,
        sourceRef = manifestEntry.sourceRef,
        status = SkillPackageUpdateStatus.FAILED,
        manifestEntry = manifestEntry,
        checkStatus = checkResult.status,
        errorCode = "SKILL_SOURCE_UNSUPPORTED",
        errorMessage = "Source type '${manifestEntry.sourceType}' is not supported for updates.",
      )
    }
  }

  private fun loadCatalogSkill(skillId: String): LoadedSkill? {
    if (!catalogRoot.exists()) {
      return null
    }
    return SkillLoader.load(catalogRoot).registry.get(skillId)
  }

  private fun loadManagedSkill(skillId: String): LoadedSkill? {
    if (!managedRoot.exists()) {
      return null
    }
    return SkillLoader.load(managedRoot).registry.get(skillId)
  }

  private fun loadSkillsFrom(root: File): List<LoadedSkill> {
    if (!root.exists()) {
      return emptyList()
    }
    return SkillLoader.load(root).loadedSkills.sortedBy(LoadedSkill::name)
  }

  private fun ensureManagedRoot() {
    if (!managedRoot.exists() && !managedRoot.mkdirs()) {
      throw IOException("Failed to create managed skills directory: ${managedRoot.path}")
    }
  }

  private fun installValidatedSkill(
    manifest: SkillInstallManifest,
    stagedSkillDirectory: File,
    expectedSkillId: String,
    sourceType: String,
    sourceRef: String,
    sourcePath: String? = null,
    sourceRelativePath: String? = null,
    resolvedRevision: String? = null,
    resolvedCommitSha: String? = null,
  ): SkillPackageInstallResult {
    val validatedSkill = validateStagedSkill(
      skillId = expectedSkillId,
      stagedSkillDirectory = stagedSkillDirectory,
    ) ?: throw SkillPackageException(
      errorCode = "SKILL_VALIDATION_FAILED",
      message = "Failed to validate skill '$expectedSkillId' before installation.",
    )
    val targetDirectory = File(managedRoot, stagedSkillDirectory.name)
    replaceInstalledDirectory(
      sourceDirectory = stagedSkillDirectory,
      targetDirectory = targetDirectory,
    )
    val now = clock()
    val entry = SkillInstallManifestEntry(
      skillId = validatedSkill.name,
      installRootPath = canonicalInvariantPath(targetDirectory),
      sourceType = sourceType,
      sourceRef = sourceRef,
      sourcePath = sourcePath,
      selectedSkillName = validatedSkill.name,
      sourceRelativePath = sourceRelativePath,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = resolvedCommitSha,
      contentHash = computeDirectoryHash(targetDirectory),
      installedAtEpochMs = manifest.installations
        .firstOrNull { installation -> installation.skillId == validatedSkill.name }
        ?.installedAtEpochMs
        ?: now,
      updatedAtEpochMs = now,
      lastCheckedAtEpochMs = now,
    )
    saveManifestEntry(existing = manifest, entry = entry, updatedAtEpochMs = now)
    return SkillPackageInstallResult(
      skillId = validatedSkill.name,
      installedSkill = validatedSkill,
      targetDirectory = targetDirectory,
      manifestEntry = entry,
    )
  }

  private fun saveManifestEntry(
    existing: SkillInstallManifest,
    entry: SkillInstallManifestEntry,
    updatedAtEpochMs: Long,
  ) {
    saveManifestSnapshot(
      existing = existing,
      updatedAtEpochMs = updatedAtEpochMs,
      installations = (
        existing.installations.filterNot { installation -> installation.skillId == entry.skillId } + entry
        ).sortedBy(SkillInstallManifestEntry::skillId),
    )
  }

  private fun saveManifestSnapshot(
    existing: SkillInstallManifest,
    updatedAtEpochMs: Long,
    installations: List<SkillInstallManifestEntry>,
  ) {
    manifestStore.save(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = updatedAtEpochMs,
        installations = installations,
      ),
    )
  }

  private fun validateStagedSkill(
    skillId: String,
    stagedSkillDirectory: File,
  ): LoadedSkill? {
    val report = SkillLoader.load(stagedSkillDirectory)
    if (report.invalidSkills.isNotEmpty()) {
      return null
    }
    return report.registry.get(skillId)
  }

  private fun stageSkillDirectory(
    sourceDirectory: File,
  ): StagedSkillDirectory {
    val stagingRoot = stagingRootPath()
    val stageId = UUID.randomUUID().toString()
    val stageDirectory = File(File(stagingRoot, stageId), sourceDirectory.name)
    stageDirectory.parentFile?.mkdirs()
    sourceDirectory.copyRecursively(stageDirectory, overwrite = true)
    return StagedSkillDirectory(
      rootDirectory = stageDirectory.parentFile ?: stageDirectory,
      skillDirectory = stageDirectory,
    )
  }

  private fun replaceInstalledDirectory(
    sourceDirectory: File,
    targetDirectory: File,
  ) {
    targetDirectory.parentFile?.mkdirs()
    if (targetDirectory.exists() && !targetDirectory.deleteRecursively()) {
      throw IOException("Failed to replace existing installed skill directory: ${targetDirectory.path}")
    }
    try {
      Files.move(
        sourceDirectory.toPath(),
        targetDirectory.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: Exception) {
      sourceDirectory.copyRecursively(targetDirectory, overwrite = true)
      if (sourceDirectory.exists()) {
        sourceDirectory.deleteRecursively()
      }
    }
  }

  private fun computeDirectoryHash(directory: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val rootPath = directory.canonicalFile.toPath()
    directory.walkTopDown()
      .toList()
      .sortedBy { file ->
        rootPath.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
      }
      .forEach { file ->
        val relativePath = rootPath.relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
        val entryPrefix = if (file.isDirectory) "D" else "F"
        digest.update("$entryPrefix:$relativePath\n".toByteArray(Charsets.UTF_8))
        if (file.isFile) {
          file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val read = input.read(buffer)
              if (read <= 0) {
                break
              }
              digest.update(buffer, 0, read)
            }
          }
        }
      }
    return digest.digest().joinToString(separator = "") { byte ->
      "%02x".format(byte)
    }
  }

  private fun isInsideManagedRoot(candidate: File): Boolean {
    val rootPath = runCatching { managedRoot.canonicalFile.toPath() }.getOrElse { return false }
    val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrElse { return false }
    return candidatePath.startsWith(rootPath)
  }

  private fun canonicalInvariantPath(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).replace('\\', '/')

  private fun inspectSkillRoot(
    searchRoot: File,
    relativeRoot: File,
    sourceType: String,
    sourceRef: String,
    sourcePath: String? = null,
    resolvedRevision: String? = null,
    resolvedCommitSha: String? = null,
  ): SkillSourceInspectionResult {
    val report = SkillLoader.load(searchRoot)
    if (report.loadedSkills.isEmpty()) {
      val invalidDetail = report.invalidSkills.firstOrNull()?.detail
      throw SkillPackageException(
        errorCode = "SKILL_SOURCE_INVALID",
        message = invalidDetail ?: "Source '$sourceRef' did not contain any valid skills.",
      )
    }
    val candidates = report.loadedSkills
      .sortedBy(LoadedSkill::name)
      .map { skill ->
        SkillSourceInspectionCandidate(
          name = skill.name,
          description = skill.metadata.skillSpec.description,
          relativePath = relativePathFromRoot(
            root = relativeRoot,
            file = skill.source.skillFile,
          ) ?: skill.source.relativePath,
        )
      }
    return SkillSourceInspectionResult(
      sourceType = sourceType,
      sourceRef = sourceRef,
      candidates = candidates,
      sourcePath = sourcePath,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = resolvedCommitSha,
    )
  }

  private fun installSelectedSkills(
    selections: List<LoadedSkill>,
    sourceType: String,
    sourceRef: String,
    sourcePath: String? = null,
    relativeRoot: File,
    resolvedRevision: String? = null,
    resolvedCommitSha: String? = null,
  ): SkillPackageBatchInstallResult {
    val entries = selections.map { skill ->
      try {
        val staged = stageSkillDirectory(File(skill.source.skillDirectoryPath))
        try {
          val result = installValidatedSkill(
            manifest = refreshManifest(),
            stagedSkillDirectory = staged.skillDirectory,
            expectedSkillId = skill.name,
            sourceType = sourceType,
            sourceRef = sourceRef,
            sourcePath = sourcePath,
            sourceRelativePath = relativePathFromRoot(
              root = relativeRoot,
              file = skill.source.skillFile,
            ) ?: skill.source.relativePath,
            resolvedRevision = resolvedRevision,
            resolvedCommitSha = resolvedCommitSha,
          )
          SkillPackageBatchInstallEntry(
            requestedSkillName = skill.name,
            installedSkillId = result.skillId,
            manifestEntry = result.manifestEntry,
          )
        } finally {
          staged.rootDirectory.deleteRecursively()
        }
      } catch (error: SkillPackageException) {
        SkillPackageBatchInstallEntry(
          requestedSkillName = skill.name,
          errorCode = error.errorCode,
          errorMessage = error.message,
        )
      } catch (error: Exception) {
        SkillPackageBatchInstallEntry(
          requestedSkillName = skill.name,
          errorCode = "SKILL_BATCH_INSTALL_FAILED",
          errorMessage = error.message ?: error::class.java.simpleName,
        )
      }
    }
    return SkillPackageBatchInstallResult(
      sourceType = sourceType,
      sourceRef = sourceRef,
      entries = entries,
      sourcePath = sourcePath,
      resolvedRevision = resolvedRevision,
      resolvedCommitSha = resolvedCommitSha,
    )
  }

  private fun selectSkillsFromRoot(
    searchRoot: File,
    requestedSkillNames: List<String>,
    installAll: Boolean,
    sourceHint: String,
    sourceRef: String,
  ): List<LoadedSkill> {
    val report = SkillLoader.load(searchRoot)
    if (report.loadedSkills.isEmpty()) {
      val invalidDetail = report.invalidSkills.firstOrNull()?.detail
      throw SkillPackageException(
        errorCode = "SKILL_SOURCE_INVALID",
        message = invalidDetail ?: "Source '$sourceRef' did not contain any valid skills.",
      )
    }
    val normalizedRequestedSkillNames = requestedSkillNames
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    if (normalizedRequestedSkillNames.isNotEmpty()) {
      return normalizedRequestedSkillNames.map { requestedSkillName ->
        report.registry.get(requestedSkillName) ?: throw SkillPackageException(
          errorCode = "SKILL_NOT_FOUND",
          message = "Skill '$requestedSkillName' was not found in source '$sourceRef'.",
        )
      }
    }
    if (installAll) {
      return report.loadedSkills.sortedBy(LoadedSkill::name)
    }
    if (report.loadedSkills.size == 1) {
      return listOf(report.loadedSkills.single())
    }
    report.registry.get(sourceHint)?.let { return listOf(it) }
    report.loadedSkills.firstOrNull { skill ->
      File(skill.source.skillDirectoryPath).name.equals(sourceHint, ignoreCase = true)
    }?.let { return listOf(it) }
    throw SkillPackageException(
      errorCode = "SKILL_SELECTION_AMBIGUOUS",
      message = "Source '$sourceRef' contains multiple skills. Specify skills explicitly or set install_all to true.",
    )
  }

  private fun selectSkillFromRoot(
    searchRoot: File,
    selectedSkillName: String?,
    sourceHint: String,
    sourceRef: String,
  ): LoadedSkill {
    val report = SkillLoader.load(searchRoot)
    val requestedSkillName = selectedSkillName?.trim()
    if (!requestedSkillName.isNullOrBlank()) {
      return report.registry.get(requestedSkillName) ?: throw SkillPackageException(
        errorCode = "SKILL_NOT_FOUND",
        message = "Skill '$requestedSkillName' was not found in source '$sourceRef'.",
      )
    }
    if (report.loadedSkills.isEmpty()) {
      val invalidDetail = report.invalidSkills.firstOrNull()?.detail
      throw SkillPackageException(
        errorCode = "SKILL_SOURCE_INVALID",
        message = invalidDetail ?: "Source '$sourceRef' did not contain any valid skills.",
      )
    }
    if (report.loadedSkills.size == 1) {
      return report.loadedSkills.single()
    }
    report.registry.get(sourceHint)?.let { return it }
    report.loadedSkills.firstOrNull { skill ->
      File(skill.source.skillDirectoryPath).name.equals(sourceHint, ignoreCase = true)
    }?.let { return it }
    throw SkillPackageException(
      errorCode = "SKILL_SELECTION_AMBIGUOUS",
      message = "Source '$sourceRef' contains multiple skills. Specify one with @skill-name or the skill argument.",
    )
  }

  private fun normalizeLocalSourceRoot(sourcePath: File): File? {
    val canonical = runCatching { sourcePath.canonicalFile }.getOrElse { sourcePath.absoluteFile }
    if (!canonical.exists()) {
      return null
    }
    return when {
      canonical.isDirectory -> canonical
      canonical.isFile && canonical.name.equals("SKILL.md", ignoreCase = true) -> canonical.parentFile
      else -> null
    }
  }

  private fun relativePathFromRoot(
    root: File,
    file: File,
  ): String? = runCatching {
    root.canonicalFile.toPath()
      .relativize(file.canonicalFile.toPath())
      .toString()
      .replace('\\', '/')
      .trim('/')
      .ifBlank { null }
  }.getOrNull()

  private data class StagedSkillDirectory(
    val rootDirectory: File,
    val skillDirectory: File,
  )
}

data class SkillPackageInstallAttempt(
  val result: SkillPackageInstallResult? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = result != null
}

data class SkillPackageBatchInstallAttempt(
  val result: SkillPackageBatchInstallResult? = null,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = result != null && errorCode == null
}
