package com.opencray.app.facade.skills

import android.content.Context
import android.content.SharedPreferences
import com.opencray.app.AppSkillsStorage
import com.opencray.app.FileBackedAgentQueueSnapshotStoreFactory
import com.opencray.app.OpenCrayLocaleManager
import com.opencray.persistence.PersistenceSchemaVersion
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import com.opencray.persistence.store.file.RecordStorageUpdate
import com.opencray.persistence.store.file.updateRecord
import com.opencray.runtime.skills.SkillPackageBatchInstallAttempt
import com.opencray.runtime.skills.SkillPackageCheckReport
import com.opencray.runtime.skills.SkillInstallManifestStore
import com.opencray.runtime.skills.SkillPackageInstallAttempt
import com.opencray.runtime.skills.SkillPackageManager
import com.opencray.runtime.skills.SkillPackageUpdateReport
import com.opencray.runtime.skills.SkillSourceInspectionAttempt
import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillLoader
import java.io.File
import kotlinx.serialization.Serializable
import org.opencray.app.R

private const val PREFERENCES_NAME = "opencray.skills.workspace"
private const val PREF_PREFIX_ENABLED = "enabled:"
private const val SKILL_ENABLEMENT_FILE_NAME = "skill-enablement.json"

private const val INSTALL_SOURCE_CURATED = "curated-library"
private const val INSTALL_SOURCE_LOCAL = "local-path"
private const val INSTALL_SOURCE_GITHUB = "github-url"
private const val INSTALL_SOURCE_GITLAB = "gitlab-url"
private const val DEFAULT_REMOTE_SKILL_SEARCH_LIMIT = 12
private const val MAX_REMOTE_SKILL_SEARCH_LIMIT = 20
private val WINDOWS_ABSOLUTE_PATH_REGEX: Regex = Regex("^[A-Za-z]:[\\\\/].+")

internal interface SkillEnablementStateStore {
  fun isEnabled(skillId: String): Boolean

  fun setEnabled(skillId: String, enabled: Boolean)

  fun remove(skillId: String)

  fun explicitEnablement(): Map<String, Boolean>
}

internal class SharedPreferencesSkillEnablementStateStore(
  private val sharedPreferences: SharedPreferences,
) : SkillEnablementStateStore {
  override fun isEnabled(skillId: String): Boolean =
    sharedPreferences.getBoolean(preferenceKey(skillId), true)

  override fun setEnabled(skillId: String, enabled: Boolean) {
    sharedPreferences.edit().putBoolean(preferenceKey(skillId), enabled).apply()
  }

  override fun remove(skillId: String) {
    sharedPreferences.edit().remove(preferenceKey(skillId)).apply()
  }

  override fun explicitEnablement(): Map<String, Boolean> =
    sharedPreferences.all.mapNotNull { (key, value) ->
      val skillId = key.removePrefix(PREF_PREFIX_ENABLED)
        .takeIf { key.startsWith(PREF_PREFIX_ENABLED) }
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return@mapNotNull null
      val enabled = value as? Boolean ?: return@mapNotNull null
      skillId to enabled
    }.toMap()
}

internal class FileBackedSkillEnablementStateStore(
  private val storage: DurableTextStorage,
  private val clock: () -> Long = System::currentTimeMillis,
) : SkillEnablementStateStore {
  private val lock = Any()

  override fun isEnabled(skillId: String): Boolean = synchronized(lock) {
    loadRecord().enabledBySkillId[skillId.trim()] ?: true
  }

  override fun setEnabled(skillId: String, enabled: Boolean) {
    val normalizedSkillId = skillId.trim().takeIf(String::isNotBlank) ?: return
    synchronized(lock) {
      updateValues { values -> values + (normalizedSkillId to enabled) }
    }
  }

  override fun remove(skillId: String) {
    val normalizedSkillId = skillId.trim().takeIf(String::isNotBlank) ?: return
    synchronized(lock) {
      updateValues { values -> values - normalizedSkillId }
    }
  }

  override fun explicitEnablement(): Map<String, Boolean> = synchronized(lock) {
    loadRecord().enabledBySkillId
  }

  fun migrateFromLegacyIfEmpty(legacyStore: SkillEnablementStateStore) {
    synchronized(lock) {
      if (hasPersistedRecord()) {
        return
      }
      val legacyValues = legacyStore.explicitEnablement()
      if (legacyValues.isEmpty()) {
        return
      }
      updateValues { values -> values + legacyValues }
    }
  }

  private fun hasPersistedRecord(): Boolean =
    !storage.readText(SKILL_ENABLEMENT_FILE_NAME).isNullOrBlank()

  private fun loadRecord(): PersistedSkillEnablementRecord =
    storage.updateRecord(
      name = SKILL_ENABLEMENT_FILE_NAME,
      serializer = PersistedSkillEnablementRecord.serializer(),
    ) { persisted ->
      val existing = persisted ?: PersistedSkillEnablementRecord()
      val repaired = existing.normalized()
      RecordStorageUpdate(
        value = repaired,
        result = repaired,
        write = persisted != null && repaired != existing,
      )
    }

  private fun updateValues(
    update: (Map<String, Boolean>) -> Map<String, Boolean>,
  ) {
    val now = clock()
    storage.updateRecord(
      name = SKILL_ENABLEMENT_FILE_NAME,
      serializer = PersistedSkillEnablementRecord.serializer(),
    ) { persisted ->
      val existing = (persisted ?: PersistedSkillEnablementRecord()).normalized()
      val updatedValues = update(existing.enabledBySkillId)
        .filterKeys { skillId -> skillId.isNotBlank() }
      RecordStorageUpdate(
        value = existing.copy(
          recordVersion = existing.recordVersion + 1L,
          updatedAtEpochMs = now,
          enabledBySkillId = updatedValues,
        ),
        result = Unit,
      )
    }
  }
}

private fun createSkillEnablementStateStore(context: Context): SkillEnablementStateStore {
  val appContext = context.applicationContext
  val fileBackedStore = FileBackedSkillEnablementStateStore(
    storage = DirectoryDurableTextStorage(
      File(
        appContext.filesDir,
        FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
      ),
    ),
  )
  val legacyStore = SharedPreferencesSkillEnablementStateStore(
    appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
  )
  if (legacyStore.explicitEnablement().isNotEmpty()) {
    fileBackedStore.migrateFromLegacyIfEmpty(legacyStore)
  }
  return fileBackedStore
}

private fun preferenceKey(skillName: String): String = PREF_PREFIX_ENABLED + skillName.trim()

@Serializable
private data class PersistedSkillEnablementRecord(
  val schemaVersion: Int = PersistenceSchemaVersion.CURRENT,
  val recordVersion: Long = 0L,
  val updatedAtEpochMs: Long = 0L,
  val enabledBySkillId: Map<String, Boolean> = emptyMap(),
) {
  fun normalized(): PersistedSkillEnablementRecord = copy(
    enabledBySkillId = enabledBySkillId
      .mapKeys { entry -> entry.key.trim() }
      .filterKeys(String::isNotBlank),
  )
}

data class SkillsSnapshot(
  val installedSkills: List<InstalledSkillSnapshot>,
  val installSources: List<InstallSourceSnapshot>,
  val suggestedSkills: List<SuggestedSkillSnapshot>,
  val suggestedSkillsMayHaveMore: Boolean = false,
)

data class InstalledSkillSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val isEnabled: Boolean,
  val sourceDirectoryPath: String,
  val canDelete: Boolean,
)

data class InstallSourceSnapshot(
  val id: String,
  val title: String,
  val subtitle: String,
  val actionLabel: String,
  val isAvailable: Boolean,
)

data class SuggestedSkillSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val sourceRef: String,
  val sourceLabel: String,
  val installs: Int? = null,
  val detailUrl: String = "",
)

data class SkillInstructionsSnapshot(
  val id: String,
  val name: String,
  val description: String,
  val body: String,
  val sourceDirectoryPath: String,
  val isEnabled: Boolean,
  val canDelete: Boolean,
)

data class SkillInstallRequestResult(
  val installedSkillId: String? = null,
  val errorMessage: String? = null,
) {
  val succeeded: Boolean
    get() = installedSkillId != null
}

interface SkillsFacade {
  fun loadSnapshot(query: String = "", suggestedLimit: Int = 0): SkillsSnapshot

  fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean

  fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String = "",
  ): SkillInstallRequestResult

  fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): SkillPackageBatchInstallAttempt

  fun inspectSkillSource(sourceRef: String): SkillSourceInspectionAttempt

  fun installSuggestedSkill(skillId: String): Boolean

  fun deleteInstalledSkill(skillId: String): Boolean

  fun refresh()

  fun checkInstalledSkillUpdates(skillId: String = ""): SkillPackageCheckReport

  fun updateInstalledSkill(skillId: String = ""): SkillPackageUpdateReport

  fun loadInstructions(skillId: String): SkillInstructionsSnapshot?

  fun loadSuggestedInstructions(
    sourceRef: String,
    selectedSkillName: String = "",
  ): SkillInstructionsSnapshot?

  fun enabledSkillRoots(): List<File>

  fun activateInstallSource(sourceId: String): String
}

internal class LocalSkillsFacade private constructor(
  private val context: Context,
) : SkillsFacade {
  private val skillEnablementStore = createSkillEnablementStateStore(context)
  private val managedRoot = AppSkillsStorage.managedSkillsRootForContext(context)
  private val catalogRoot = AppSkillsStorage.catalogSkillsRootForContext(context)
  private val packageManager = SkillPackageManager(
    managedRoot = managedRoot,
    catalogRoot = catalogRoot,
    manifestStore = SkillInstallManifestStore.fromFile(
      AppSkillsStorage.manifestFileForContext(context),
    ),
  )

  override fun loadSnapshot(query: String, suggestedLimit: Int): SkillsSnapshot {
    val normalizedQuery = query.trim()
    val installedSkills = loadInstalledSkills()
    val suggestedSnapshot = loadSuggestedSkills(
      query = normalizedQuery,
      installedSkills = installedSkills,
      suggestedLimit = suggestedLimit,
    )
    return SkillsSnapshot(
      installedSkills = installedSkills,
      installSources = installSources(),
      suggestedSkills = suggestedSnapshot.skills,
      suggestedSkillsMayHaveMore = suggestedSnapshot.mayHaveMore,
    )
  }

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): SkillInstallRequestResult {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    if (normalizedSourceRef.isEmpty()) {
      return SkillInstallRequestResult(
        errorMessage = context.getString(R.string.skills_install_error_source_blank),
      )
    }
    if (loadCatalogSkills().any { skill -> skill.name == normalizedSourceRef }) {
      val result = runCatching {
        packageManager.installFromCatalog(normalizedSourceRef)
      }.getOrNull() ?: return SkillInstallRequestResult(
        errorMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
      enableInstalledSkill(result.skillId)
      return SkillInstallRequestResult(installedSkillId = result.skillId)
    }
    packageManager.resolveRemoteSource(
      sourceRef = normalizedSourceRef,
      selectedSkillName = normalizedSelectedSkillName.takeIf(String::isNotBlank),
    )?.let {
      return installAttempt(
        attempt = packageManager.installFromRemoteSource(
          sourceRef = normalizedSourceRef,
          selectedSkillName = normalizedSelectedSkillName.takeIf(String::isNotBlank),
        ),
        fallbackMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
    }
    if (looksLikeExplicitLocalSkillSource(normalizedSourceRef)) {
      return installAttempt(
        attempt = packageManager.installFromLocalSource(
          sourcePath = File(normalizedSourceRef),
          sourceRef = normalizedSourceRef,
          selectedSkillName = normalizedSelectedSkillName.takeIf(String::isNotBlank),
        ),
        fallbackMessage = context.getString(
          R.string.skills_install_error_source_failed,
          normalizedSourceRef,
        ),
      )
    }
    return SkillInstallRequestResult(
      errorMessage = context.getString(
        R.string.skills_install_error_source_unrecognized,
        normalizedSourceRef,
      ),
    )
  }

  override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean {
    val skillName = skillId.trim()
    if (skillName.isEmpty()) {
      return false
    }
    val installed = loadManagedSkills().any { skill -> skill.name == skillName }
    if (!installed) {
      return false
    }
    skillEnablementStore.setEnabled(skillName, enabled)
    return true
  }

  override fun installSuggestedSkill(skillId: String): Boolean {
    val result = installSkillSource(sourceRef = skillId, selectedSkillName = "")
    return result.succeeded
  }

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): SkillPackageBatchInstallAttempt {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillNames = selectedSkillNames
      .asSequence()
      .map(String::trim)
      .filter(String::isNotBlank)
      .distinct()
      .toList()
    if (normalizedSourceRef.isEmpty()) {
      return SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_SOURCE_BLANK",
        errorMessage = context.getString(R.string.skills_install_error_source_blank),
      )
    }
    if (normalizedSelectedSkillNames.isEmpty()) {
      return SkillPackageBatchInstallAttempt(
        errorCode = "SKILL_SELECTION_EMPTY",
        errorMessage = "At least one skill must be selected.",
      )
    }
    if (looksLikeExplicitLocalSkillSource(normalizedSourceRef)) {
      return packageManager.installFromLocalSourceBatch(
        sourcePath = File(normalizedSourceRef),
        sourceRef = normalizedSourceRef,
        selectedSkillNames = normalizedSelectedSkillNames,
        installAll = false,
      )
    }
    if (packageManager.resolveRemoteSource(normalizedSourceRef) != null) {
      return packageManager.installFromRemoteSourceBatch(
        sourceRef = normalizedSourceRef,
        selectedSkillNames = normalizedSelectedSkillNames,
        installAll = false,
      )
    }
    return SkillPackageBatchInstallAttempt(
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = "Batch installation requires an explicit local path, GitHub source, or GitLab source.",
    )
  }

  override fun inspectSkillSource(sourceRef: String): SkillSourceInspectionAttempt {
    val normalizedSourceRef = sourceRef.trim()
    if (normalizedSourceRef.isEmpty()) {
      return SkillSourceInspectionAttempt(
        errorCode = "SKILL_SOURCE_BLANK",
        errorMessage = context.getString(R.string.skills_install_error_source_blank),
      )
    }
    if (looksLikeExplicitLocalSkillSource(normalizedSourceRef)) {
      return packageManager.inspectLocalSource(
        sourcePath = File(normalizedSourceRef),
        sourceRef = normalizedSourceRef,
      )
    }
    if (packageManager.resolveRemoteSource(normalizedSourceRef) != null) {
      return packageManager.inspectRemoteSource(normalizedSourceRef)
    }
    return SkillSourceInspectionAttempt(
      errorCode = "SKILL_SOURCE_UNSUPPORTED",
      errorMessage = context.getString(
        R.string.skills_install_error_source_unrecognized,
        normalizedSourceRef,
      ),
    )
  }

  override fun deleteInstalledSkill(skillId: String): Boolean {
    val result = runCatching {
      packageManager.removeInstalledSkill(skillId)
    }.getOrNull() ?: return false
    skillEnablementStore.remove(result.skillId)
    return true
  }

  override fun refresh() {
    // Filesystem-backed snapshots reload on every access, so refresh is an explicit rescan hook.
    runCatching {
      packageManager.refreshManifest()
    }
    loadManagedSkills()
    loadCatalogSkills()
  }

  override fun checkInstalledSkillUpdates(skillId: String): SkillPackageCheckReport {
    val normalizedSkillId = skillId.trim().takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    require(skillIsInstalledOrTracked(normalizedSkillId)) {
      "Skill '${normalizedSkillId ?: ""}' is not installed in the host-managed skills directory."
    }
    return packageManager.checkInstalledSkills(normalizedSkillId)
  }

  override fun updateInstalledSkill(skillId: String): SkillPackageUpdateReport {
    val normalizedSkillId = skillId.trim().takeIf(String::isNotBlank)
    packageManager.refreshManifest()
    require(skillIsInstalledOrTracked(normalizedSkillId)) {
      "Skill '${normalizedSkillId ?: ""}' is not installed in the host-managed skills directory."
    }
    return packageManager.updateInstalledSkills(normalizedSkillId)
  }

  override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? {
    val skill = loadManagedSkills().firstOrNull { it.name == skillId }
      ?: loadCatalogSkills().firstOrNull { it.name == skillId }
      ?: return null
    val sourceDirectory = File(skill.source.skillDirectoryPath)
    return SkillInstructionsSnapshot(
      id = skill.name,
      name = skill.name,
      description = skill.metadata.skillSpec.description,
      body = skill.document.markdownBody,
      sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
      isEnabled = skillEnablementStore.isEnabled(skill.name),
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  override fun loadSuggestedInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): SkillInstructionsSnapshot? {
    val normalizedSourceRef = sourceRef.trim()
    val normalizedSelectedSkillName = selectedSkillName.trim()
    if (normalizedSourceRef.isEmpty()) {
      return null
    }
    val localSkill = loadCatalogSkills().firstOrNull { skill ->
      when {
        normalizedSelectedSkillName.isNotEmpty() -> skill.name == normalizedSelectedSkillName
        else -> skill.name == normalizedSourceRef
      }
    }
    if (localSkill != null) {
      val sourceDirectory = File(localSkill.source.skillDirectoryPath)
      return SkillInstructionsSnapshot(
        id = localSkill.name,
        name = localSkill.name,
        description = localSkill.metadata.skillSpec.description,
        body = localSkill.document.markdownBody,
        sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
        isEnabled = skillEnablementStore.isEnabled(localSkill.name),
        canDelete = isInsideManagedRoot(sourceDirectory),
      )
    }
    val remoteAttempt = packageManager.loadRemoteSkillInstructions(
      sourceRef = normalizedSourceRef,
      selectedSkillName = normalizedSelectedSkillName.takeIf(String::isNotBlank),
    )
    val remoteResult = remoteAttempt.result ?: return null
    return SkillInstructionsSnapshot(
      id = remoteResult.skill.name,
      name = remoteResult.skill.name,
      description = remoteResult.skill.metadata.skillSpec.description,
      body = remoteResult.skill.document.markdownBody,
      sourceDirectoryPath = remoteResult.sourcePath,
      isEnabled = skillEnablementStore.isEnabled(remoteResult.skill.name),
      canDelete = false,
    )
  }

  override fun enabledSkillRoots(): List<File> = loadManagedSkills()
    .filter { skill -> skillEnablementStore.isEnabled(skill.name) }
    .map { skill -> File(skill.source.skillDirectoryPath) }
    .filter(File::exists)
    .sortedBy { file -> file.invariantSeparatorsPath }

  override fun activateInstallSource(sourceId: String): String = when (sourceId) {
    INSTALL_SOURCE_CURATED -> if (loadCatalogSkills().isEmpty()) {
      context.getString(R.string.skills_activate_curated_empty)
    } else {
      context.getString(R.string.skills_activate_curated_available)
    }

    INSTALL_SOURCE_LOCAL -> context.getString(R.string.skills_activate_local_ready)
    INSTALL_SOURCE_GITHUB -> context.getString(R.string.skills_activate_github_ready)
    INSTALL_SOURCE_GITLAB -> context.getString(R.string.skills_activate_gitlab_ready)
    else -> context.getString(R.string.skills_activate_unknown)
  }

  private fun loadInstalledSkills(): List<InstalledSkillSnapshot> = loadManagedSkills().map { skill ->
    val sourceDirectory = File(skill.source.skillDirectoryPath)
    InstalledSkillSnapshot(
      id = skill.name,
      name = skill.name,
      description = skill.metadata.skillSpec.description,
      isEnabled = skillEnablementStore.isEnabled(skill.name),
      sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  private fun loadSuggestedSkills(
    query: String,
    installedSkills: List<InstalledSkillSnapshot>,
    suggestedLimit: Int,
  ): SuggestedSkillsSearchSnapshot {
    val installedNames = installedSkills.mapTo(linkedSetOf()) { item -> item.name }
    val localMatches = loadCatalogSkills()
      .asSequence()
      .filter { skill -> skill.name !in installedNames }
      .map { skill ->
        SuggestedSkillSnapshot(
          id = skill.name,
          name = skill.name,
          description = skill.metadata.skillSpec.description,
          sourceRef = skill.name,
          sourceLabel = context.getString(R.string.skills_suggested_source_local_catalog),
        )
      }
      .filter { item ->
        query.isEmpty() ||
          item.name.contains(query, ignoreCase = true) ||
          item.description.contains(query, ignoreCase = true)
      }
      .toMutableList()
    if (query.isEmpty()) {
      return SuggestedSkillsSearchSnapshot(
        skills = localMatches,
        mayHaveMore = false,
      )
    }
    val requestedLimit = normalizeRemoteSuggestedLimit(suggestedLimit)
    val remoteResponse = packageManager.searchRemoteSkills(
      query = query,
      limit = requestedLimit,
    )
    val remoteMatches = remoteResponse.hits
      .asSequence()
      .filter { hit -> hit.name !in installedNames }
      .map { hit ->
        SuggestedSkillSnapshot(
          id = hit.id,
          name = hit.name,
          description = if (hit.installs > 0) {
            context.getString(
              R.string.skills_suggested_remote_description_with_installs,
              hit.source,
              hit.installs,
            )
          } else {
            context.getString(
              R.string.skills_suggested_remote_description,
              hit.source,
            )
          },
          sourceRef = hit.installRef,
          sourceLabel = context.getString(R.string.skills_suggested_source_remote_index),
          installs = hit.installs,
          detailUrl = hit.detailUrl,
        )
      }
      .sortedWith(
        compareByDescending<SuggestedSkillSnapshot> { it.installs ?: 0 }
          .thenBy { it.name.lowercase() },
      )
      .toList()
    val remoteNames = remoteMatches.mapTo(linkedSetOf()) { item -> item.name.lowercase() }
    val mergedMatches = buildList {
      addAll(remoteMatches)
      addAll(localMatches.filterNot { local -> local.name.lowercase() in remoteNames })
    }
    val mayHaveMore = requestedLimit < MAX_REMOTE_SKILL_SEARCH_LIMIT &&
      remoteResponse.hits.size >= requestedLimit
    return SuggestedSkillsSearchSnapshot(
      skills = mergedMatches.take(requestedLimit),
      mayHaveMore = mayHaveMore,
    )
  }

  private fun installSources(): List<InstallSourceSnapshot> {
    val catalogAvailable = loadCatalogSkills().isNotEmpty()
    return listOf(
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_CURATED,
        title = context.getString(R.string.skills_install_source_curated_title),
        subtitle = if (catalogAvailable) {
          context.getString(R.string.skills_install_source_curated_subtitle_available)
        } else {
          context.getString(R.string.skills_install_source_curated_subtitle_empty)
        },
        actionLabel = if (catalogAvailable) {
          context.getString(R.string.skills_install_source_curated_action_browse)
        } else {
          context.getString(R.string.skills_install_source_curated_action_empty)
        },
        isAvailable = catalogAvailable,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_LOCAL,
        title = context.getString(R.string.skills_install_source_local_title),
        subtitle = context.getString(R.string.skills_install_source_local_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_GITHUB,
        title = context.getString(R.string.skills_install_source_github_title),
        subtitle = context.getString(R.string.skills_install_source_github_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_GITLAB,
        title = context.getString(R.string.skills_install_source_gitlab_title),
        subtitle = context.getString(R.string.skills_install_source_gitlab_subtitle_ready),
        actionLabel = context.getString(R.string.skills_install_source_action_inspect),
        isAvailable = true,
      ),
    )
  }

  private fun loadManagedSkills(): List<LoadedSkill> = loadSkillsFrom(managedRoot)

  private fun loadCatalogSkills(): List<LoadedSkill> = loadSkillsFrom(catalogRoot)

  private fun loadSkillsFrom(root: File): List<LoadedSkill> {
    if (!root.exists()) {
      return emptyList()
    }
    return SkillLoader.load(root).loadedSkills
  }

  private fun enableInstalledSkill(skillId: String) {
    skillEnablementStore.setEnabled(skillId, true)
  }

  private fun isInsideManagedRoot(candidate: File): Boolean {
    val rootPath = runCatching { managedRoot.canonicalFile.toPath() }.getOrElse { return false }
    val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrElse { return false }
    return candidatePath.startsWith(rootPath)
  }

  private fun installAttempt(
    attempt: SkillPackageInstallAttempt,
    fallbackMessage: String,
  ): SkillInstallRequestResult {
    val result = attempt.result ?: return SkillInstallRequestResult(
      errorMessage = attempt.errorMessage ?: fallbackMessage,
    )
    enableInstalledSkill(result.skillId)
    return SkillInstallRequestResult(installedSkillId = result.skillId)
  }

  private fun looksLikeExplicitLocalSkillSource(sourceRef: String): Boolean {
    val normalized = sourceRef.trim()
    return normalized.startsWith(".") ||
      normalized.startsWith("/") ||
      normalized.startsWith("\\") ||
      normalized.contains("\\") ||
      WINDOWS_ABSOLUTE_PATH_REGEX.matches(normalized)
  }

  private fun normalizeRemoteSuggestedLimit(suggestedLimit: Int): Int =
    suggestedLimit.takeIf { it > 0 }?.coerceIn(1, MAX_REMOTE_SKILL_SEARCH_LIMIT)
      ?: DEFAULT_REMOTE_SKILL_SEARCH_LIMIT

  private fun skillIsInstalledOrTracked(skillId: String?): Boolean {
    if (skillId == null) {
      return true
    }
    val managedSkillIds = loadManagedSkills().mapTo(linkedSetOf()) { skill -> skill.name }
    if (skillId in managedSkillIds) {
      return true
    }
    return packageManager.listInstallations().any { entry -> entry.skillId == skillId }
  }

  private data class SuggestedSkillsSearchSnapshot(
    val skills: List<SuggestedSkillSnapshot>,
    val mayHaveMore: Boolean,
  )

  companion object {
    fun fromContext(context: Context): LocalSkillsFacade =
      LocalSkillsFacade(OpenCrayLocaleManager.wrap(context.applicationContext))
  }
}

internal object EmptySkillsFacade : SkillsFacade {
  override fun loadSnapshot(query: String, suggestedLimit: Int): SkillsSnapshot = SkillsSnapshot(
    installedSkills = emptyList(),
    installSources = emptyList(),
    suggestedSkills = emptyList(),
  )

  override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = false

  override fun installSkillSource(
    sourceRef: String,
    selectedSkillName: String,
  ): SkillInstallRequestResult =
    SkillInstallRequestResult(errorMessage = "Skills host support is unavailable.")

  override fun installSkillSourceBatch(
    sourceRef: String,
    selectedSkillNames: List<String>,
  ): SkillPackageBatchInstallAttempt = SkillPackageBatchInstallAttempt(
    errorMessage = "Skills host support is unavailable.",
  )

  override fun inspectSkillSource(sourceRef: String): SkillSourceInspectionAttempt =
    SkillSourceInspectionAttempt(errorMessage = "Skills host support is unavailable.")

  override fun installSuggestedSkill(skillId: String): Boolean = false

  override fun deleteInstalledSkill(skillId: String): Boolean = false

  override fun refresh() {}

  override fun checkInstalledSkillUpdates(skillId: String): SkillPackageCheckReport =
    SkillPackageCheckReport(results = emptyList())

  override fun updateInstalledSkill(skillId: String): SkillPackageUpdateReport =
    SkillPackageUpdateReport(results = emptyList())

  override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

  override fun loadSuggestedInstructions(
    sourceRef: String,
    selectedSkillName: String,
  ): SkillInstructionsSnapshot? = null

  override fun enabledSkillRoots(): List<File> = emptyList()

  override fun activateInstallSource(sourceId: String): String =
    "Skills host support is unavailable."
}
