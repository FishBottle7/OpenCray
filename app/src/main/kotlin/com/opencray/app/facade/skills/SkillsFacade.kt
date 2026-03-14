package com.opencray.app.facade.skills

import android.content.Context
import com.opencray.app.AppSkillsStorage
import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillLoader
import java.io.File

private const val PREFERENCES_NAME = "opencray.skills.workspace"
private const val PREF_PREFIX_ENABLED = "enabled:"

private const val INSTALL_SOURCE_CURATED = "curated-library"
private const val INSTALL_SOURCE_LOCAL = "local-folder"
private const val INSTALL_SOURCE_GIT = "git-repository"

data class SkillsSnapshot(
  val installedSkills: List<InstalledSkillSnapshot>,
  val installSources: List<InstallSourceSnapshot>,
  val suggestedSkills: List<SuggestedSkillSnapshot>,
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

interface SkillsFacade {
  fun loadSnapshot(query: String = ""): SkillsSnapshot

  fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean

  fun installSuggestedSkill(skillId: String): Boolean

  fun deleteInstalledSkill(skillId: String): Boolean

  fun refresh()

  fun loadInstructions(skillId: String): SkillInstructionsSnapshot?

  fun enabledSkillRoots(): List<File>

  fun activateInstallSource(sourceId: String): String
}

internal class LocalSkillsFacade private constructor(
  private val context: Context,
) : SkillsFacade {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val managedRoot = AppSkillsStorage.managedSkillsRootForContext(context)
  private val catalogRoot = AppSkillsStorage.catalogSkillsRootForContext(context)

  override fun loadSnapshot(query: String): SkillsSnapshot {
    val normalizedQuery = query.trim()
    val installedSkills = loadInstalledSkills()
    return SkillsSnapshot(
      installedSkills = installedSkills,
      installSources = installSources(),
      suggestedSkills = loadSuggestedSkills(
        query = normalizedQuery,
        installedSkills = installedSkills,
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
    preferences.edit().putBoolean(preferenceKey(skillName), enabled).apply()
    return true
  }

  override fun installSuggestedSkill(skillId: String): Boolean {
    val loadedSkill = loadCatalogSkills().firstOrNull { skill -> skill.name == skillId } ?: return false
    val sourceDirectory = File(loadedSkill.source.skillDirectoryPath)
    if (!sourceDirectory.isDirectory) {
      return false
    }
    if (!managedRoot.exists() && !managedRoot.mkdirs()) {
      return false
    }
    val targetDirectory = File(managedRoot, sourceDirectory.name)
    if (targetDirectory.exists() && !targetDirectory.deleteRecursively()) {
      return false
    }
    return runCatching {
      sourceDirectory.copyRecursively(targetDirectory, overwrite = true)
      preferences.edit().putBoolean(preferenceKey(loadedSkill.name), true).apply()
    }.isSuccess
  }

  override fun deleteInstalledSkill(skillId: String): Boolean {
    val loadedSkill = loadManagedSkills().firstOrNull { skill -> skill.name == skillId } ?: return false
    val sourceDirectory = File(loadedSkill.source.skillDirectoryPath)
    if (!isInsideManagedRoot(sourceDirectory)) {
      return false
    }
    val deleted = sourceDirectory.deleteRecursively()
    if (deleted) {
      preferences.edit().remove(preferenceKey(loadedSkill.name)).apply()
    }
    return deleted
  }

  override fun refresh() {
    // Filesystem-backed snapshots reload on every access, so refresh is an explicit rescan hook.
    loadManagedSkills()
    loadCatalogSkills()
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
      isEnabled = preferences.getBoolean(preferenceKey(skill.name), true),
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  override fun enabledSkillRoots(): List<File> = loadManagedSkills()
    .filter { skill -> preferences.getBoolean(preferenceKey(skill.name), true) }
    .map { skill -> File(skill.source.skillDirectoryPath) }
    .filter(File::exists)
    .sortedBy { file -> file.invariantSeparatorsPath }

  override fun activateInstallSource(sourceId: String): String = when (sourceId) {
    INSTALL_SOURCE_CURATED -> if (loadCatalogSkills().isEmpty()) {
      "No local skills catalog is installed on this device."
    } else {
      "Use the suggestions list below to install from the local catalog."
    }

    INSTALL_SOURCE_LOCAL -> "Local folder install is not wired into the Flutter host yet."
    INSTALL_SOURCE_GIT -> "Git repository install is not wired into the Flutter host yet."
    else -> "That install source is unavailable."
  }

  private fun loadInstalledSkills(): List<InstalledSkillSnapshot> = loadManagedSkills().map { skill ->
    val sourceDirectory = File(skill.source.skillDirectoryPath)
    InstalledSkillSnapshot(
      id = skill.name,
      name = skill.name,
      description = skill.metadata.skillSpec.description,
      isEnabled = preferences.getBoolean(preferenceKey(skill.name), true),
      sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
      canDelete = isInsideManagedRoot(sourceDirectory),
    )
  }

  private fun loadSuggestedSkills(
    query: String,
    installedSkills: List<InstalledSkillSnapshot>,
  ): List<SuggestedSkillSnapshot> {
    val installedNames = installedSkills.mapTo(linkedSetOf()) { item -> item.name }
    return loadCatalogSkills()
      .asSequence()
      .filter { skill -> skill.name !in installedNames }
      .map { skill ->
        SuggestedSkillSnapshot(
          id = skill.name,
          name = skill.name,
          description = skill.metadata.skillSpec.description,
        )
      }
      .filter { item ->
        query.isEmpty() ||
          item.name.contains(query, ignoreCase = true) ||
          item.description.contains(query, ignoreCase = true)
      }
      .toList()
  }

  private fun installSources(): List<InstallSourceSnapshot> {
    val catalogAvailable = loadCatalogSkills().isNotEmpty()
    return listOf(
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_CURATED,
        title = "Curated skills",
        subtitle = if (catalogAvailable) {
          "Install from the local catalog already available on this device."
        } else {
          "No local catalog is installed on this device yet."
        },
        actionLabel = if (catalogAvailable) "Browse" else "Empty",
        isAvailable = catalogAvailable,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_LOCAL,
        title = "Local path",
        subtitle = "Importing a skill from a local folder is not wired into Flutter yet.",
        actionLabel = "Unavailable",
        isAvailable = false,
      ),
      InstallSourceSnapshot(
        id = INSTALL_SOURCE_GIT,
        title = "GitHub repository",
        subtitle = "Downloading a skill package from GitHub is not wired into Flutter yet.",
        actionLabel = "Unavailable",
        isAvailable = false,
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

  private fun preferenceKey(skillName: String): String = PREF_PREFIX_ENABLED + skillName

  private fun isInsideManagedRoot(candidate: File): Boolean {
    val rootPath = runCatching { managedRoot.canonicalFile.toPath() }.getOrElse { return false }
    val candidatePath = runCatching { candidate.canonicalFile.toPath() }.getOrElse { return false }
    return candidatePath.startsWith(rootPath)
  }

  companion object {
    fun fromContext(context: Context): LocalSkillsFacade =
      LocalSkillsFacade(context.applicationContext)
  }
}

internal object EmptySkillsFacade : SkillsFacade {
  override fun loadSnapshot(query: String): SkillsSnapshot = SkillsSnapshot(
    installedSkills = emptyList(),
    installSources = emptyList(),
    suggestedSkills = emptyList(),
  )

  override fun setSkillEnabled(skillId: String, enabled: Boolean): Boolean = false

  override fun installSuggestedSkill(skillId: String): Boolean = false

  override fun deleteInstalledSkill(skillId: String): Boolean = false

  override fun refresh() {}

  override fun loadInstructions(skillId: String): SkillInstructionsSnapshot? = null

  override fun enabledSkillRoots(): List<File> = emptyList()

  override fun activateInstallSource(sourceId: String): String =
    "Skills host support is unavailable."
}
