package com.opencray.ui.skills

import android.content.Context
import com.opencray.skills.LoadedSkill
import com.opencray.skills.SkillLoader
import java.io.File
import org.opencray.ui.R

internal enum class SkillsPage {
  MANAGE,
  INSTALL,
}

internal data class WorkspaceSkillItem(
  val id: String,
  val name: String,
  val description: String,
  val isEnabled: Boolean,
  val sourceDirectoryPath: String,
  val canDelete: Boolean,
)

internal data class InstallSourceItem(
  val id: String,
  val title: String,
  val actionLabel: String,
)

internal data class SuggestedSkillItem(
  val id: String,
  val name: String,
  val description: String,
)

internal data class SkillsManagementUiState(
  val selectedPage: SkillsPage = SkillsPage.MANAGE,
  val skills: List<WorkspaceSkillItem> = emptyList(),
  val expandedSkillId: String? = null,
  val installQuery: String = "",
  val installSources: List<InstallSourceItem> = emptyList(),
  val suggestedSkills: List<SuggestedSkillItem> = emptyList(),
)

class SkillEditorViewModel private constructor(
  private val context: Context,
  private val repository: WorkspaceSkillsRepository,
) {
  private val listeners = linkedSetOf<(SkillsManagementUiState) -> Unit>()
  private var state = SkillsManagementUiState(
    installSources = defaultInstallSources(context),
  )

  init {
    refresh()
  }

  internal fun observe(listener: (SkillsManagementUiState) -> Unit): () -> Unit {
    listeners += listener
    listener(state)
    return {
      listeners.remove(listener)
    }
  }

  fun refresh() {
    val skills = repository.loadInstalledSkills()
    val expandedSkillId = state.expandedSkillId?.takeIf { expandedId ->
      skills.any { skill -> skill.id == expandedId }
    }
    state = state.copy(
      skills = skills,
      expandedSkillId = expandedSkillId,
      installSources = filterInstallSources(state.installQuery),
      suggestedSkills = repository.loadSuggestedSkills(state.installQuery),
    )
    publish()
  }

  internal fun selectPage(page: SkillsPage) {
    if (state.selectedPage == page) {
      return
    }
    state = state.copy(
      selectedPage = page,
      expandedSkillId = if (page == SkillsPage.MANAGE) state.expandedSkillId else null,
    )
    publish()
  }

  internal fun updateInstallQuery(value: String) {
    state = state.copy(
      installQuery = value,
      installSources = filterInstallSources(value),
      suggestedSkills = repository.loadSuggestedSkills(value),
    )
    publish()
  }

  internal fun toggleSkillEnabled(skillId: String) {
    val skill = state.skills.firstOrNull { it.id == skillId } ?: return
    val nextEnabled = !skill.isEnabled
    repository.setSkillEnabled(skillName = skill.name, enabled = nextEnabled)
    state = state.copy(
      skills = state.skills.map { current ->
        if (current.id == skillId) {
          current.copy(isEnabled = nextEnabled)
        } else {
          current
        }
      },
    )
    publish()
  }

  internal fun toggleSkillMenu(skillId: String) {
    state = state.copy(
      expandedSkillId = if (state.expandedSkillId == skillId) null else skillId,
    )
    publish()
  }

  internal fun dismissSkillMenu() {
    if (state.expandedSkillId == null) {
      return
    }
    state = state.copy(expandedSkillId = null)
    publish()
  }

  internal fun upgradeSkill(skillId: String): String {
    val skill = state.skills.firstOrNull { it.id == skillId }
      ?: return context.getString(R.string.skills_message_skill_unavailable)
    refresh()
    return context.getString(R.string.skills_message_reloaded, skill.name)
  }

  internal fun deleteSkill(skillId: String): String {
    val skill = state.skills.firstOrNull { it.id == skillId }
      ?: return context.getString(R.string.skills_message_skill_unavailable)
    if (!skill.canDelete) {
      return context.getString(R.string.skills_message_delete_unavailable)
    }
    val deleted = repository.deleteInstalledSkill(skillName = skill.name)
    if (!deleted) {
      return context.getString(R.string.skills_message_delete_failed, skill.name)
    }
    refresh()
    return context.getString(R.string.skills_message_deleted, skill.name)
  }

  internal fun installSuggestedSkill(skillId: String): String {
    val suggestion = state.suggestedSkills.firstOrNull { it.id == skillId }
      ?: return context.getString(R.string.skills_message_suggestion_unavailable)
    val installed = repository.installSuggestedSkill(skillName = skillId)
    if (!installed) {
      return context.getString(R.string.skills_message_install_failed, suggestion.name)
    }
    selectPage(SkillsPage.MANAGE)
    refresh()
    return context.getString(R.string.skills_message_installed, suggestion.name)
  }

  internal fun activateInstallSource(sourceId: String): String = when (sourceId) {
    INSTALL_SOURCE_CURATED -> context.getString(R.string.skills_message_source_curated_pending)
    INSTALL_SOURCE_LOCAL -> context.getString(R.string.skills_message_source_local_pending)
    INSTALL_SOURCE_GIT -> context.getString(R.string.skills_message_source_git_pending)
    INSTALL_SOURCE_CLAWHUB -> context.getString(R.string.skills_message_source_clawhub_pending)
    else -> context.getString(R.string.skills_message_source_unavailable)
  }

  private fun publish() {
    val snapshot = state
    listeners.forEach { listener -> listener(snapshot) }
  }

  private fun filterInstallSources(query: String): List<InstallSourceItem> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) {
      return defaultInstallSources(context)
    }
    return defaultInstallSources(context).filter { item ->
      item.title.contains(normalizedQuery, ignoreCase = true) ||
        item.actionLabel.contains(normalizedQuery, ignoreCase = true)
    }
  }

  companion object {
    fun fromContext(context: Context): SkillEditorViewModel =
      SkillEditorViewModel(
        context = context.applicationContext,
        repository = WorkspaceSkillsRepository(context.applicationContext),
      )

    fun managedSkillsRootForContext(context: Context): File =
      WorkspaceSkillsRepository.managedSkillsRootForContext(context.applicationContext)

    fun catalogSkillsRootForContext(context: Context): File =
      WorkspaceSkillsRepository.catalogSkillsRootForContext(context.applicationContext)
  }
}

private const val PREFERENCES_NAME = "opencray.skills.workspace"
private const val PREF_PREFIX_ENABLED = "enabled:"
private const val MANAGED_SKILLS_DIRECTORY = "skills"
private const val CATALOG_SKILLS_DIRECTORY = "skills-catalog"

private const val INSTALL_SOURCE_CURATED = "curated-library"
private const val INSTALL_SOURCE_LOCAL = "local-folder"
private const val INSTALL_SOURCE_GIT = "git-repository"
private const val INSTALL_SOURCE_CLAWHUB = "clawhub"

private class WorkspaceSkillsRepository(
  private val context: Context,
) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val managedRoot = managedSkillsRootForContext(context)
  private val catalogRoot = catalogSkillsRootForContext(context)

  fun loadInstalledSkills(): List<WorkspaceSkillItem> =
    loadSkillsFrom(managedRoot).map { skill ->
      val sourceDirectory = File(skill.source.skillDirectoryPath)
      WorkspaceSkillItem(
        id = skill.name,
        name = skill.name,
        description = skill.metadata.skillSpec.description,
        isEnabled = preferences.getBoolean(preferenceKey(skill.name), true),
        sourceDirectoryPath = sourceDirectory.invariantSeparatorsPath,
        canDelete = isInsideManagedRoot(sourceDirectory),
      )
    }

  fun loadSuggestedSkills(query: String): List<SuggestedSkillItem> {
    val installedNames = loadSkillsFrom(managedRoot).mapTo(linkedSetOf()) { it.name }
    val normalizedQuery = query.trim()
    return loadSkillsFrom(catalogRoot)
      .filter { skill -> skill.name !in installedNames }
      .map { skill ->
        SuggestedSkillItem(
          id = skill.name,
          name = skill.name,
          description = skill.metadata.skillSpec.description,
        )
      }
      .filter { item ->
        normalizedQuery.isEmpty() ||
          item.name.contains(normalizedQuery, ignoreCase = true) ||
          item.description.contains(normalizedQuery, ignoreCase = true)
      }
  }

  fun setSkillEnabled(
    skillName: String,
    enabled: Boolean,
  ) {
    preferences.edit().putBoolean(preferenceKey(skillName), enabled).apply()
  }

  fun deleteInstalledSkill(skillName: String): Boolean {
    val loadedSkill = loadSkillsFrom(managedRoot).firstOrNull { it.name == skillName } ?: return false
    val sourceDirectory = File(loadedSkill.source.skillDirectoryPath)
    if (!isInsideManagedRoot(sourceDirectory)) {
      return false
    }
    val deleted = sourceDirectory.deleteRecursively()
    if (deleted) {
      preferences.edit().remove(preferenceKey(skillName)).apply()
    }
    return deleted
  }

  fun installSuggestedSkill(skillName: String): Boolean {
    val loadedSkill = loadSkillsFrom(catalogRoot).firstOrNull { it.name == skillName } ?: return false
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
    }.isSuccess
  }

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
    fun managedSkillsRootForContext(context: Context): File = File(context.filesDir, MANAGED_SKILLS_DIRECTORY)

    fun catalogSkillsRootForContext(context: Context): File = File(context.filesDir, CATALOG_SKILLS_DIRECTORY)
  }
}

private fun defaultInstallSources(context: Context): List<InstallSourceItem> = listOf(
  InstallSourceItem(
    id = INSTALL_SOURCE_CURATED,
    title = context.getString(R.string.skills_source_curated_title),
    actionLabel = context.getString(R.string.skills_source_action_browse),
  ),
  InstallSourceItem(
    id = INSTALL_SOURCE_LOCAL,
    title = context.getString(R.string.skills_source_local_title),
    actionLabel = context.getString(R.string.skills_source_action_import),
  ),
  InstallSourceItem(
    id = INSTALL_SOURCE_GIT,
    title = context.getString(R.string.skills_source_git_title),
    actionLabel = context.getString(R.string.skills_source_action_connect),
  ),
  InstallSourceItem(
    id = INSTALL_SOURCE_CLAWHUB,
    title = context.getString(R.string.skills_source_clawhub_title),
    actionLabel = context.getString(R.string.skills_source_action_browse),
  ),
)
