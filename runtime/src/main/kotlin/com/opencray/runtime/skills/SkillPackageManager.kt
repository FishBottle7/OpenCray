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

data class SkillPackageRemoveResult(
  val skillId: String,
  val removedDirectory: File,
  val manifestEntryRemoved: Boolean,
)

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
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  fun managedRootPath(): File = managedRoot

  fun catalogRootPath(): File = catalogRoot

  fun listCatalogSkills(): List<LoadedSkill> = loadSkillsFrom(catalogRoot)

  fun listManagedSkills(): List<LoadedSkill> = loadSkillsFrom(managedRoot)

  fun listInstallations(): List<SkillInstallManifestEntry> =
    manifestStore.load().installations.sortedBy(SkillInstallManifestEntry::skillId)

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
    if (!managedRoot.exists() && !managedRoot.mkdirs()) {
      throw IOException("Failed to create managed skills directory: ${managedRoot.path}")
    }

    val staged = stageSkillDirectory(sourceDirectory)
    try {
      val validatedSkill = validateStagedSkill(
        skillId = normalizedSkillId,
        stagedSkillDirectory = staged.skillDirectory,
      ) ?: return null
      val targetDirectory = File(managedRoot, sourceDirectory.name)
      replaceInstalledDirectory(
        sourceDirectory = staged.skillDirectory,
        targetDirectory = targetDirectory,
      )
      val now = clock()
      val entry = SkillInstallManifestEntry(
        skillId = validatedSkill.name,
        installRootPath = canonicalInvariantPath(targetDirectory),
        sourceType = SkillInstallSourceType.LOCAL_CATALOG.wireValue,
        sourceRef = normalizedSkillId,
        sourcePath = canonicalInvariantPath(sourceDirectory),
        selectedSkillName = validatedSkill.name,
        sourceRelativePath = catalogSkill.source.relativePath,
        contentHash = computeDirectoryHash(targetDirectory),
        installedAtEpochMs = manifest.installations
          .firstOrNull { entry -> entry.skillId == validatedSkill.name }
          ?.installedAtEpochMs
          ?: now,
        updatedAtEpochMs = now,
      )
      saveManifestEntry(existing = manifest, entry = entry, updatedAtEpochMs = now)
      return SkillPackageInstallResult(
        skillId = validatedSkill.name,
        installedSkill = validatedSkill,
        targetDirectory = targetDirectory,
        manifestEntry = entry,
      )
    } finally {
      staged.rootDirectory.deleteRecursively()
    }
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

  private fun saveManifestEntry(
    existing: SkillInstallManifest,
    entry: SkillInstallManifestEntry,
    updatedAtEpochMs: Long,
  ) {
    val updatedInstallations = (
      existing.installations.filterNot { installation -> installation.skillId == entry.skillId } + entry
      ).sortedBy(SkillInstallManifestEntry::skillId)
    manifestStore.save(
      existing.copy(
        recordVersion = existing.recordVersion + 1L,
        updatedAtEpochMs = updatedAtEpochMs,
        installations = updatedInstallations,
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
    val stagingRoot = File(managedRoot.parentFile ?: managedRoot, ".skills-staging")
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

  private data class StagedSkillDirectory(
    val rootDirectory: File,
    val skillDirectory: File,
  )
}
