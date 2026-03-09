package com.opencray.ui.skills

import com.opencray.skills.NormalizedSkillMetadata
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import com.opencray.skills.SkillValidationResult
import com.opencray.skills.SkillValidator

internal const val FIELD_NAME: String = "name"
internal const val FIELD_DESCRIPTION: String = "description"
internal const val FIELD_LICENSE: String = "license"
internal const val FIELD_COMPATIBILITY: String = "compatibility"
internal const val FIELD_METADATA: String = "metadata"
internal const val FIELD_INVOCATION_CONTROL: String = "invocation-control"
internal const val FIELD_USER_INVOCABLE: String = "user-invocable"
internal const val FIELD_ALLOWED_TOOLS: String = "allowed-tools"
internal const val FIELD_CONTEXT: String = "context"
internal const val FIELD_AGENT: String = "agent"
internal const val FIELD_TOOL_PERMISSIONS: String = "tool-permissions"
internal const val FIELD_SUBAGENT_PERMISSIONS: String = "subagent-permissions"

private const val DEFAULT_EDITOR_MESSAGE: String =
  "Save validates name, description, invocation-control, context, and allow|ask|deny permission metadata."

internal enum class SkillLifecycleState {
  ACTIVE,
  DISABLED,
}

internal enum class SkillInstallState {
  INSTALLED,
  NOT_INSTALLED,
}

internal data class SkillDraft(
  val name: String = "",
  val description: String = "",
  val license: String = "",
  val compatibility: String = "",
  val metadataText: String = "",
  val invocationControl: SkillInvocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
  val userInvocable: Boolean = true,
  val executionContext: SkillExecutionContext = SkillExecutionContext.INLINE,
  val subagent: String = "",
  val allowedTools: String = "",
  val toolPermissionsText: String = "",
  val subagentPermissionsText: String = "",
)

internal data class SkillPackageSource(
  val packageType: String,
  val sourceReference: String,
  val packageRoot: String,
  val manifestPath: String,
)

internal data class ManagedSkill(
  val id: String,
  val draft: SkillDraft,
  val metadata: NormalizedSkillMetadata,
  val lifecycleState: SkillLifecycleState = SkillLifecycleState.ACTIVE,
  val installState: SkillInstallState = SkillInstallState.NOT_INSTALLED,
  val packageSource: SkillPackageSource? = null,
)

internal data class SkillEditorState(
  val draft: SkillDraft = SkillDraft(),
  val fieldErrors: Map<String, String> = emptyMap(),
  val validationMessage: String = DEFAULT_EDITOR_MESSAGE,
)

internal data class SkillsManagementUiState(
  val skills: List<ManagedSkill> = emptyList(),
  val selectedSkillId: String? = null,
  val editor: SkillEditorState = SkillEditorState(),
  val statusMessage: String = "Skills management foundation is ready.",
  val importExportMessage: String = "In-memory package import/export is idle.",
)

class SkillEditorViewModel {
  private val listeners = linkedSetOf<(SkillsManagementUiState) -> Unit>()
  private val packageSnapshotsBySkillId = linkedMapOf<String, InMemorySkillPackage>()
  private var nextSkillId: Int = 0
  private var nextImportPackageIndex: Int = 0
  private var state: SkillsManagementUiState = buildInitialState()

  internal fun observe(listener: (SkillsManagementUiState) -> Unit): () -> Unit {
    listeners += listener
    listener(snapshot())
    return {
      listeners.remove(listener)
    }
  }

  internal fun createNewSkillDraft() {
    state = state.copy(
      selectedSkillId = null,
      editor = SkillEditorState(),
      statusMessage = "Creating a new in-memory skill draft.",
    )
    publish()
  }

  internal fun selectSkill(skillId: String) {
    val selectedSkill = state.skills.firstOrNull { it.id == skillId } ?: return
    state = state.copy(
      selectedSkillId = selectedSkill.id,
      editor = editorStateFor(selectedSkill, "Editing ${selectedSkill.metadata.skillSpec.name}. Save re-validates the draft."),
      statusMessage = "Selected ${selectedSkill.metadata.skillSpec.name}.",
    )
    publish()
  }

  internal fun updateName(value: String) = updateDraft(FIELD_NAME) { copy(name = value) }

  internal fun updateDescription(value: String) = updateDraft(FIELD_DESCRIPTION) { copy(description = value) }

  internal fun updateLicense(value: String) = updateDraft(FIELD_LICENSE) { copy(license = value) }

  internal fun updateCompatibility(value: String) = updateDraft(FIELD_COMPATIBILITY) { copy(compatibility = value) }

  internal fun updateMetadataText(value: String) = updateDraft(FIELD_METADATA) { copy(metadataText = value) }

  internal fun updateInvocationControl(value: SkillInvocationControl) =
    updateDraft(FIELD_INVOCATION_CONTROL, FIELD_USER_INVOCABLE) {
      copy(invocationControl = value)
    }

  internal fun updateUserInvocable(value: Boolean) = updateDraft(FIELD_USER_INVOCABLE) { copy(userInvocable = value) }

  internal fun updateExecutionContext(value: SkillExecutionContext) =
    updateDraft(FIELD_CONTEXT, FIELD_AGENT, FIELD_SUBAGENT_PERMISSIONS) {
      if (value == SkillExecutionContext.INLINE) {
        copy(
          executionContext = value,
          subagent = "",
          subagentPermissionsText = "",
        )
      } else {
        copy(executionContext = value)
      }
    }

  internal fun updateSubagent(value: String) = updateDraft(FIELD_AGENT) { copy(subagent = value) }

  internal fun updateAllowedTools(value: String) = updateDraft(FIELD_ALLOWED_TOOLS) { copy(allowedTools = value) }

  internal fun updateToolPermissionsText(value: String) = updateDraft(FIELD_TOOL_PERMISSIONS) {
    copy(toolPermissionsText = value)
  }

  internal fun updateSubagentPermissionsText(value: String) = updateDraft(FIELD_SUBAGENT_PERMISSIONS) {
    copy(subagentPermissionsText = value)
  }

  internal fun saveDraft() {
    when (val preparedDraft = prepareDraft(state.editor.draft)) {
      is PreparedDraft.Invalid -> applyFieldError(preparedDraft.field, preparedDraft.detail)
      is PreparedDraft.Valid -> {
        when (val validation = SkillValidator.validate(preparedDraft.frontMatter)) {
          is SkillValidationResult.Invalid -> applyFieldError(validation.error.field, validation.error.detail)
          is SkillValidationResult.Valid -> saveValidatedDraft(preparedDraft.draft, validation.metadata)
        }
      }
    }
  }

  internal fun toggleSelectedLifecycle() {
    val selectedSkill = selectedSkill() ?: run {
      state = state.copy(statusMessage = "Select a saved skill before toggling lifecycle state.")
      publish()
      return
    }

    val updatedSkill = selectedSkill.copy(
      lifecycleState = when (selectedSkill.lifecycleState) {
        SkillLifecycleState.ACTIVE -> SkillLifecycleState.DISABLED
        SkillLifecycleState.DISABLED -> SkillLifecycleState.ACTIVE
      },
    )
    replaceSkill(updatedSkill)
    state = state.copy(
      selectedSkillId = updatedSkill.id,
      editor = editorStateFor(updatedSkill, "Editing ${updatedSkill.metadata.skillSpec.name}."),
      statusMessage = when (updatedSkill.lifecycleState) {
        SkillLifecycleState.ACTIVE -> "${updatedSkill.metadata.skillSpec.name} is active in memory."
        SkillLifecycleState.DISABLED -> "${updatedSkill.metadata.skillSpec.name} is disabled in memory."
      },
    )
    publish()
  }

  internal fun toggleSelectedInstallState() {
    val selectedSkill = selectedSkill() ?: run {
      state = state.copy(statusMessage = "Select a saved skill before toggling install state.")
      publish()
      return
    }

    val updatedSkill = selectedSkill.copy(
      installState = when (selectedSkill.installState) {
        SkillInstallState.INSTALLED -> SkillInstallState.NOT_INSTALLED
        SkillInstallState.NOT_INSTALLED -> SkillInstallState.INSTALLED
      },
    )
    replaceSkill(updatedSkill)
    state = state.copy(
      selectedSkillId = updatedSkill.id,
      editor = editorStateFor(updatedSkill, "Editing ${updatedSkill.metadata.skillSpec.name}."),
      statusMessage = when (updatedSkill.installState) {
        SkillInstallState.INSTALLED -> "Install placeholder marked ${updatedSkill.metadata.skillSpec.name} as installed."
        SkillInstallState.NOT_INSTALLED -> "Install placeholder marked ${updatedSkill.metadata.skillSpec.name} as not installed."
      },
    )
    publish()
  }

  internal fun deleteSelectedSkill() {
    val selectedSkill = selectedSkill() ?: run {
      state = state.copy(statusMessage = "Select a saved skill before deleting it.")
      publish()
      return
    }

    val remainingSkills = state.skills.filterNot { it.id == selectedSkill.id }
    val nextSelected = remainingSkills.firstOrNull()
    state = state.copy(
      skills = remainingSkills,
      selectedSkillId = nextSelected?.id,
      editor = nextSelected?.let { editorStateFor(it, "Editing ${it.metadata.skillSpec.name}.") } ?: SkillEditorState(),
      statusMessage = "Deleted ${selectedSkill.metadata.skillSpec.name} from in-memory state.",
    )
    packageSnapshotsBySkillId.remove(selectedSkill.id)
    publish()
  }

  internal fun triggerImportPlaceholder() {
    val importedPackage = importPackageFor(nextImportPackageIndex)
    nextImportPackageIndex += 1
    val importedMetadata = when (val validation = SkillValidator.validate(importedPackage.frontMatter)) {
      is SkillValidationResult.Invalid -> {
        state = state.copy(
          importExportMessage = "Import blocked: ${validation.error.field}=${validation.error.detail}",
          statusMessage = "Import blocked by invalid in-memory package metadata.",
        )
        publish()
        return
      }

      is SkillValidationResult.Valid -> validation.metadata
    }
    val existingSkill = state.skills.firstOrNull { it.metadata.skillSpec.name == importedMetadata.skillSpec.name }
    val importedSkill = ManagedSkill(
      id = existingSkill?.id ?: allocateSkillId(),
      draft = importedPackage.draft,
      metadata = importedMetadata,
      lifecycleState = existingSkill?.lifecycleState ?: importedPackage.lifecycleState,
      installState = existingSkill?.installState ?: importedPackage.installState,
      packageSource = importedPackage.source,
    )
    val updatedSkills = if (existingSkill == null) {
      (state.skills + importedSkill).sortedBy { it.metadata.skillSpec.name }
    } else {
      state.skills.map { current ->
        if (current.id == importedSkill.id) importedSkill else current
      }.sortedBy { it.metadata.skillSpec.name }
    }
    packageSnapshotsBySkillId[importedSkill.id] = importedPackage
    state = state.copy(
      skills = updatedSkills,
      selectedSkillId = importedSkill.id,
      editor = editorStateFor(
        importedSkill,
        "Imported ${importedSkill.metadata.skillSpec.name} from a ${importedPackage.source.packageType} package.",
      ),
      importExportMessage = buildPackageSummary(importedPackage),
      statusMessage = if (existingSkill == null) {
        "Imported ${importedPackage.source.packageType} package for ${importedSkill.metadata.skillSpec.name} from ${importedPackage.source.sourceReference}."
      } else {
        "Updated ${importedSkill.metadata.skillSpec.name} from the ${importedPackage.source.packageType} package at ${importedPackage.source.manifestPath}."
      },
    )
    publish()
  }

  internal fun triggerExportPlaceholder() {
    val selectedSkill = selectedSkill()
    val exportDraft = selectedSkill?.draft ?: state.editor.draft
    val validatedDraft = validateDraftForImportExport(
      draft = exportDraft,
      action = "export",
    ) ?: return
    val existingSnapshot = selectedSkill?.let { skill -> packageSnapshotsBySkillId[skill.id] }
    val packageType = selectedSkill?.packageSource?.packageType
      ?: existingSnapshot?.source?.packageType
      ?: if (validatedDraft.draft.executionContext == SkillExecutionContext.FORK) "git-style" else "directory-style"
    val exportPackage = buildExportPackage(
      packageType = packageType,
      draft = validatedDraft.draft,
      metadata = validatedDraft.metadata,
    )
    selectedSkill?.let { skill ->
      packageSnapshotsBySkillId[skill.id] = exportPackage
    }
    state = state.copy(
      importExportMessage = buildPackageSummary(exportPackage),
      statusMessage = "Exported $packageType package snapshot for ${exportPackage.metadata.skillSpec.name}.",
    )
    publish()
  }

  private fun buildInitialState(): SkillsManagementUiState {
    val seededSkills = listOf(
      createSeededSkill(
        draft = SkillDraft(
          name = "workspace-audit",
          description = "Reviews workspace state before deeper execution.",
          metadataText = "surface=skills-management\nowner=android-sandbox",
          invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
          userInvocable = true,
          executionContext = SkillExecutionContext.INLINE,
          allowedTools = "read, grep",
          toolPermissionsText = "read=allow\ngrep=allow",
        ),
        installState = SkillInstallState.INSTALLED,
      ),
      createSeededSkill(
        draft = SkillDraft(
          name = "forked-review",
          description = "Escalates a heavier review path through a forked subagent.",
          metadataText = "surface=skills-management\nmode=fork",
          invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
          userInvocable = true,
          executionContext = SkillExecutionContext.FORK,
          subagent = "review-agent",
          allowedTools = "read, grep",
          toolPermissionsText = "read=allow\ngrep=ask",
          subagentPermissionsText = "review-agent=allow",
        ),
        lifecycleState = SkillLifecycleState.DISABLED,
        installState = SkillInstallState.NOT_INSTALLED,
      ),
    )

    val firstSkill = seededSkills.first()
    return SkillsManagementUiState(
      skills = seededSkills,
      selectedSkillId = firstSkill.id,
      editor = editorStateFor(firstSkill, "Editing ${firstSkill.metadata.skillSpec.name}. Save re-validates the draft."),
      statusMessage = "Loaded seeded in-memory skills for the Task 13 foundation.",
    )
  }

  private fun createSeededSkill(
    draft: SkillDraft,
    lifecycleState: SkillLifecycleState = SkillLifecycleState.ACTIVE,
    installState: SkillInstallState = SkillInstallState.NOT_INSTALLED,
  ): ManagedSkill {
    val preparedDraft = when (val prepared = prepareDraft(draft)) {
      is PreparedDraft.Invalid -> error("Seed draft is invalid: ${prepared.detail}")
      is PreparedDraft.Valid -> prepared
    }

    val validation = when (val result = SkillValidator.validate(preparedDraft.frontMatter)) {
      is SkillValidationResult.Invalid -> error("Seed draft failed validation: ${result.error.detail}")
      is SkillValidationResult.Valid -> result.metadata
    }

    return ManagedSkill(
      id = allocateSkillId(),
      draft = preparedDraft.draft,
      metadata = validation,
      lifecycleState = lifecycleState,
      installState = installState,
    )
  }

  private fun saveValidatedDraft(
    draft: SkillDraft,
    validation: NormalizedSkillMetadata,
  ) {
    val duplicateSkill = state.skills.firstOrNull {
      it.id != state.selectedSkillId && it.metadata.skillSpec.name == validation.skillSpec.name
    }
    if (duplicateSkill != null) {
      applyFieldError(FIELD_NAME, "Another in-memory skill already uses '${validation.skillSpec.name}'.")
      return
    }

    val existingSkill = selectedSkill()
    val updatedSkill = ManagedSkill(
      id = existingSkill?.id ?: allocateSkillId(),
      draft = draft,
      metadata = validation,
      lifecycleState = existingSkill?.lifecycleState ?: SkillLifecycleState.ACTIVE,
      installState = existingSkill?.installState ?: SkillInstallState.NOT_INSTALLED,
      packageSource = existingSkill?.packageSource,
    )

    val updatedSkills = if (existingSkill == null) {
      (state.skills + updatedSkill).sortedBy { it.metadata.skillSpec.name }
    } else {
      state.skills.map { existing ->
        if (existing.id == existingSkill.id) updatedSkill else existing
      }.sortedBy { it.metadata.skillSpec.name }
    }

    state = state.copy(
      skills = updatedSkills,
      selectedSkillId = updatedSkill.id,
      editor = editorStateFor(updatedSkill, "Saved ${updatedSkill.metadata.skillSpec.name} to in-memory state."),
      statusMessage = if (existingSkill == null) {
        "Created ${updatedSkill.metadata.skillSpec.name}. Persistence remains deferred."
      } else {
        "Updated ${updatedSkill.metadata.skillSpec.name}."
      },
    )
    publish()
  }

  private fun prepareDraft(draft: SkillDraft): PreparedDraft {
    val compatibility = when (val result = parseListInput(draft.compatibility, FIELD_COMPATIBILITY)) {
      is ParseResult.Failure -> return PreparedDraft.Invalid(result.field, result.detail)
      is ParseResult.Success -> result.value
    }
    val metadata = when (val result = parseMapInput(draft.metadataText, FIELD_METADATA)) {
      is ParseResult.Failure -> return PreparedDraft.Invalid(result.field, result.detail)
      is ParseResult.Success -> result.value
    }
    val allowedTools = when (val result = parseListInput(draft.allowedTools, FIELD_ALLOWED_TOOLS)) {
      is ParseResult.Failure -> return PreparedDraft.Invalid(result.field, result.detail)
      is ParseResult.Success -> result.value
    }
    val toolPermissions = when (val result = parsePermissionInput(draft.toolPermissionsText, FIELD_TOOL_PERMISSIONS)) {
      is ParseResult.Failure -> return PreparedDraft.Invalid(result.field, result.detail)
      is ParseResult.Success -> result.value
    }
    val subagentPermissions = when (val result = parsePermissionInput(draft.subagentPermissionsText, FIELD_SUBAGENT_PERMISSIONS)) {
      is ParseResult.Failure -> return PreparedDraft.Invalid(result.field, result.detail)
      is ParseResult.Success -> result.value
    }

    val normalizedDraft = draft.copy(
      name = draft.name.trim(),
      description = draft.description.trim(),
      license = draft.license.trim(),
      compatibility = compatibility.joinToString(separator = "\n"),
      metadataText = metadata.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" },
      subagent = draft.subagent.trim(),
      allowedTools = allowedTools.joinToString(separator = ", "),
      toolPermissionsText = toolPermissions.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" },
      subagentPermissionsText = subagentPermissions.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" },
    )

    val frontMatter = linkedMapOf<String, Any?>(
      FIELD_NAME to normalizedDraft.name,
      FIELD_DESCRIPTION to normalizedDraft.description,
      FIELD_INVOCATION_CONTROL to normalizedDraft.invocationControl.serializedValue(),
      FIELD_USER_INVOCABLE to normalizedDraft.userInvocable,
      FIELD_CONTEXT to normalizedDraft.executionContext.serializedValue(),
    )
    if (normalizedDraft.license.isNotEmpty()) {
      frontMatter[FIELD_LICENSE] = normalizedDraft.license
    }
    if (compatibility.isNotEmpty()) {
      frontMatter[FIELD_COMPATIBILITY] = compatibility
    }
    if (metadata.isNotEmpty()) {
      frontMatter[FIELD_METADATA] = metadata
    }
    if (normalizedDraft.subagent.isNotEmpty()) {
      frontMatter[FIELD_AGENT] = normalizedDraft.subagent
    }
    if (allowedTools.isNotEmpty()) {
      frontMatter[FIELD_ALLOWED_TOOLS] = allowedTools
    }
    if (toolPermissions.isNotEmpty()) {
      frontMatter[FIELD_TOOL_PERMISSIONS] = toolPermissions
    }
    if (subagentPermissions.isNotEmpty()) {
      frontMatter[FIELD_SUBAGENT_PERMISSIONS] = subagentPermissions
    }

    return PreparedDraft.Valid(
      frontMatter = frontMatter,
      draft = normalizedDraft,
    )
  }

  private fun parseListInput(
    rawValue: String,
    field: String,
  ): ParseResult<List<String>> {
    if (rawValue.isBlank()) {
      return ParseResult.Success(emptyList())
    }

    val values = mutableListOf<String>()
    for (line in rawValue.replace("\r", "").lines()) {
      if (line.isBlank()) {
        continue
      }
      for (segment in line.split(',')) {
        val normalizedSegment = segment.trim()
        if (normalizedSegment.isBlank()) {
          return ParseResult.Failure(field, "$field must not contain blank entries.")
        }
        values += normalizedSegment
      }
    }
    return ParseResult.Success(values)
  }

  private fun parseMapInput(
    rawValue: String,
    field: String,
  ): ParseResult<Map<String, String>> {
    if (rawValue.isBlank()) {
      return ParseResult.Success(emptyMap())
    }

    val result = linkedMapOf<String, String>()
    for (line in rawValue.replace("\r", "").lines()) {
      if (line.isBlank()) {
        continue
      }
      val parts = splitKeyValue(line) ?: return ParseResult.Failure(
        field,
        "$field must use one key=value entry per line.",
      )
      val key = parts.first.trim()
      if (key.isBlank()) {
        return ParseResult.Failure(field, "$field keys must not be blank.")
      }
      result[key] = parts.second.trim()
    }
    return ParseResult.Success(result)
  }

  private fun parsePermissionInput(
    rawValue: String,
    field: String,
  ): ParseResult<Map<String, String>> {
    if (rawValue.isBlank()) {
      return ParseResult.Success(emptyMap())
    }

    val result = linkedMapOf<String, String>()
    for (line in rawValue.replace("\r", "").lines()) {
      if (line.isBlank()) {
        continue
      }
      val parts = splitKeyValue(line) ?: return ParseResult.Failure(
        field,
        "$field must use one pattern=allow|ask|deny entry per line.",
      )
      val pattern = parts.first.trim()
      val decision = parts.second.trim().lowercase()
      if (pattern.isBlank()) {
        return ParseResult.Failure(field, "$field patterns must not be blank.")
      }
      if (decision !in setOf("allow", "ask", "deny")) {
        return ParseResult.Failure(field, "$field values must be allow, ask, or deny.")
      }
      result[pattern] = decision
    }
    return ParseResult.Success(result)
  }

  private fun splitKeyValue(rawLine: String): Pair<String, String>? {
    val equalsIndex = rawLine.indexOf('=')
    if (equalsIndex >= 0) {
      return rawLine.substring(0, equalsIndex) to rawLine.substring(equalsIndex + 1)
    }

    val colonIndex = rawLine.indexOf(':')
    if (colonIndex >= 0) {
      return rawLine.substring(0, colonIndex) to rawLine.substring(colonIndex + 1)
    }
    return null
  }

  private fun updateDraft(
    vararg clearFields: String,
    transform: SkillDraft.() -> SkillDraft,
  ) {
    val updatedDraft = state.editor.draft.transform()
    val fieldsToClear = clearFields.toSet()
    val updatedErrors = state.editor.fieldErrors.filterKeys { it !in fieldsToClear }
    state = state.copy(
      editor = state.editor.copy(
        draft = updatedDraft,
        fieldErrors = updatedErrors,
        validationMessage = if (updatedErrors.isEmpty()) {
          DEFAULT_EDITOR_MESSAGE
        } else {
          "Update the highlighted fields and save again."
        },
      ),
    )
    publish()
  }

  private fun applyFieldError(
    field: String,
    detail: String,
  ) {
    state = state.copy(
      editor = state.editor.copy(
        fieldErrors = mapOf(field to detail),
        validationMessage = detail,
      ),
      statusMessage = "Validation blocked the save. Fix the inline feedback and try again.",
    )
    publish()
  }

  private fun selectedSkill(): ManagedSkill? = state.skills.firstOrNull { it.id == state.selectedSkillId }

  private fun importPackageFor(index: Int): InMemorySkillPackage = if (index % 2 == 0) {
    buildImportedPackage(
      source = SkillPackageSource(
        packageType = "directory-style",
        sourceReference = "directory://imports/local/directory-observer",
        packageRoot = "imports/local/directory-observer",
        manifestPath = "imports/local/directory-observer/SKILL.md",
      ),
      draft = SkillDraft(
        name = "directory-observer",
        description = "Inspects a local package directory before deeper execution.",
        metadataText = "source=directory\nsurface=skills-management\npackage-id=directory-observer",
        invocationControl = SkillInvocationControl.EXPLICIT_ONLY,
        userInvocable = true,
        executionContext = SkillExecutionContext.INLINE,
        allowedTools = "read, grep",
        toolPermissionsText = "read=allow\ngrep=allow",
      ),
      readmeSummary = "Directory-backed in-memory package fixture for Task 13 import flows.",
    )
  } else {
    buildImportedPackage(
      source = SkillPackageSource(
        packageType = "git-style",
        sourceReference = "git+https://example.invalid/opencray/community-skills.git#skills/git-review-sync",
        packageRoot = "skills/git-review-sync",
        manifestPath = "skills/git-review-sync/SKILL.md",
      ),
      draft = SkillDraft(
        name = "git-review-sync",
        description = "Pulls a remote package snapshot into the in-memory review flow.",
        metadataText = "source=git\nsurface=skills-management\nremote=demo-origin",
        invocationControl = SkillInvocationControl.EXPLICIT_AND_IMPLICIT,
        userInvocable = true,
        executionContext = SkillExecutionContext.FORK,
        subagent = "import-review-agent",
        allowedTools = "read, grep",
        toolPermissionsText = "read=allow\ngrep=ask",
        subagentPermissionsText = "import-review-agent=allow",
      ),
      readmeSummary = "Git-backed in-memory package fixture for Task 13 import flows.",
    )
  }

  private fun validateDraftForImportExport(
    draft: SkillDraft,
    action: String,
  ): ValidatedSkillDraft? {
    val preparedDraft = when (val prepared = prepareDraft(draft)) {
      is PreparedDraft.Invalid -> {
        applyImportExportFieldError(prepared.field, prepared.detail, action)
        return null
      }

      is PreparedDraft.Valid -> prepared
    }
    val validation = when (val result = SkillValidator.validate(preparedDraft.frontMatter)) {
      is SkillValidationResult.Invalid -> {
        applyImportExportFieldError(result.error.field, result.error.detail, action)
        return null
      }

      is SkillValidationResult.Valid -> result.metadata
    }
    return ValidatedSkillDraft(
      draft = preparedDraft.draft,
      metadata = validation,
      frontMatter = preparedDraft.frontMatter,
    )
  }

  private fun buildImportedPackage(
    source: SkillPackageSource,
    draft: SkillDraft,
    readmeSummary: String,
  ): InMemorySkillPackage {
    val validatedDraft = validateDraftOrThrow(
      draft = draft,
      action = "import package fixture",
    )
    return buildInMemoryPackage(
      source = source,
      draft = validatedDraft.draft,
      metadata = validatedDraft.metadata,
      frontMatter = validatedDraft.frontMatter,
      readmeSummary = readmeSummary,
      lifecycleState = SkillLifecycleState.ACTIVE,
      installState = SkillInstallState.NOT_INSTALLED,
    )
  }

  private fun buildExportPackage(
    packageType: String,
    draft: SkillDraft,
    metadata: NormalizedSkillMetadata,
  ): InMemorySkillPackage {
    val exportName = metadata.skillSpec.name
    val validatedDraft = validateDraftOrThrow(
      draft = draft,
      action = "export package snapshot",
    )
    return buildInMemoryPackage(
      source = SkillPackageSource(
        packageType = packageType,
        sourceReference = when (packageType) {
          "git-style" -> "git+memory://exports/$exportName.git#skills/$exportName"
          else -> "memory://exports/$exportName/"
        },
        packageRoot = when (packageType) {
          "git-style" -> "exports/git/$exportName"
          else -> "exports/$exportName"
        },
        manifestPath = when (packageType) {
          "git-style" -> "exports/git/$exportName/SKILL.md"
          else -> "exports/$exportName/SKILL.md"
        },
      ),
      draft = validatedDraft.draft,
      metadata = metadata,
      frontMatter = validatedDraft.frontMatter,
      readmeSummary = "In-memory export snapshot for $exportName.",
      lifecycleState = selectedSkill()?.lifecycleState ?: SkillLifecycleState.ACTIVE,
      installState = selectedSkill()?.installState ?: SkillInstallState.NOT_INSTALLED,
    )
  }

  private fun validateDraftOrThrow(
    draft: SkillDraft,
    action: String,
  ): ValidatedSkillDraft {
    val preparedDraft = when (val prepared = prepareDraft(draft)) {
      is PreparedDraft.Invalid -> error("$action is invalid: ${prepared.detail}")
      is PreparedDraft.Valid -> prepared
    }
    val validation = when (val result = SkillValidator.validate(preparedDraft.frontMatter)) {
      is SkillValidationResult.Invalid -> error("$action failed validation: ${result.error.detail}")
      is SkillValidationResult.Valid -> result.metadata
    }
    return ValidatedSkillDraft(
      draft = preparedDraft.draft,
      metadata = validation,
      frontMatter = preparedDraft.frontMatter,
    )
  }

  private fun buildInMemoryPackage(
    source: SkillPackageSource,
    draft: SkillDraft,
    metadata: NormalizedSkillMetadata,
    frontMatter: Map<String, Any?>,
    readmeSummary: String,
    lifecycleState: SkillLifecycleState,
    installState: SkillInstallState,
  ): InMemorySkillPackage {
    val manifestContent = buildPackageManifest(
      frontMatter = frontMatter,
      metadata = metadata,
      source = source,
    )
    val readmePath = "${source.packageRoot}/README.md"
    val readmeContent = buildPackageReadme(
      metadata = metadata,
      source = source,
      readmeSummary = readmeSummary,
      lifecycleState = lifecycleState,
      installState = installState,
    )
    return InMemorySkillPackage(
      source = source,
      draft = draft,
      metadata = metadata,
      frontMatter = frontMatter,
      files = listOf(
        InMemorySkillPackageFile(
          path = source.manifestPath,
          content = manifestContent,
        ),
        InMemorySkillPackageFile(
          path = readmePath,
          content = readmeContent,
        ),
      ),
      lifecycleState = lifecycleState,
      installState = installState,
    )
  }

  private fun buildPackageManifest(
    frontMatter: Map<String, Any?>,
    metadata: NormalizedSkillMetadata,
    source: SkillPackageSource,
  ): String = buildString {
    append("---\n")
    append(serializeFrontMatter(frontMatter))
    append("\n---\n\n")
    append("# ${metadata.skillSpec.name}\n\n")
    append(metadata.skillSpec.description)
    append("\n\n")
    append("Package source: ${source.packageType} (${source.sourceReference})")
  }

  private fun buildPackageReadme(
    metadata: NormalizedSkillMetadata,
    source: SkillPackageSource,
    readmeSummary: String,
    lifecycleState: SkillLifecycleState,
    installState: SkillInstallState,
  ): String = buildString {
    append("# ${metadata.skillSpec.name}\n\n")
    append(readmeSummary)
    append("\n\n")
    append("- package-type: ${source.packageType}\n")
    append("- source-reference: ${source.sourceReference}\n")
    append("- manifest-path: ${source.manifestPath}\n")
    append("- lifecycle-state: ${lifecycleState.displayName()}\n")
    append("- install-state: ${installState.displayName()}")
  }

  private fun serializeFrontMatter(frontMatter: Map<String, Any?>): String =
    frontMatter.entries.joinToString(separator = "\n") { entry ->
      serializeFrontMatterEntry(
        key = entry.key,
        value = entry.value,
      )
    }

  private fun serializeFrontMatterEntry(
    key: String,
    value: Any?,
  ): String = when (value) {
    is Boolean -> "$key: $value"

    is List<*> -> if (value.isEmpty()) {
      "$key: []"
    } else {
      buildString {
        append("$key:")
        value.forEach { item ->
          append("\n  - ")
          append(serializeYamlScalar(item))
        }
      }
    }

    is Map<*, *> -> if (value.isEmpty()) {
      "$key: {}"
    } else {
      buildString {
        append("$key:")
        value.forEach { (entryKey, entryValue) ->
          append("\n  ")
          append(serializeYamlKey(entryKey.toString()))
          append(": ")
          append(serializeYamlScalar(entryValue))
        }
      }
    }

    else -> "$key: ${serializeYamlScalar(value)}"
  }

  private fun serializeYamlKey(value: String): String =
    if (value.all { character ->
        character.isLetterOrDigit() || character == '-' || character == '_' || character == '/' || character == '.'
      }
    ) {
      value
    } else {
      serializeYamlScalar(value)
    }

  private fun serializeYamlScalar(value: Any?): String = when (value) {
    is Boolean -> value.toString()
    null -> "\"\""
    else -> "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
  }

  private fun applyImportExportFieldError(
    field: String,
    detail: String,
    action: String,
  ) {
    state = state.copy(
      editor = state.editor.copy(
        fieldErrors = mapOf(field to detail),
        validationMessage = detail,
      ),
      statusMessage = "Validation blocked the $action. Fix the inline feedback and try again.",
      importExportMessage = "$action blocked: $detail",
    )
    publish()
  }

  private fun buildPackageSummary(skillPackage: InMemorySkillPackage): String {
    val summary = mutableListOf(
      "package-type=${skillPackage.source.packageType}",
      "name=${skillPackage.metadata.skillSpec.name}",
      "invocation-control=${skillPackage.metadata.invocationControl.serializedValue()}",
      "context=${skillPackage.metadata.executionContext.serializedValue()}",
      "tool-count=${skillPackage.metadata.skillSpec.allowedTools.size}",
      "metadata-count=${skillPackage.metadata.skillSpec.metadata.size}",
    )
    if (!skillPackage.source.packageRoot.startsWith("exports/")) {
      summary += "source-ref=${skillPackage.source.sourceReference}"
      summary += "manifest=${skillPackage.source.manifestPath}"
      summary += "file-count=${skillPackage.files.size}"
    }
    return summary.joinToString(separator = "\n")
  }

  private fun replaceSkill(updatedSkill: ManagedSkill) {
    state = state.copy(
      skills = state.skills.map { existing ->
        if (existing.id == updatedSkill.id) updatedSkill else existing
      },
    )
  }

  private fun editorStateFor(
    skill: ManagedSkill,
    message: String = DEFAULT_EDITOR_MESSAGE,
  ): SkillEditorState = SkillEditorState(
    draft = skill.draft,
    validationMessage = message,
  )

  private fun allocateSkillId(): String {
    nextSkillId += 1
    return "skill-$nextSkillId"
  }

  private fun publish() {
    val snapshot = snapshot()
    listeners.toList().forEach { listener ->
      listener(snapshot)
    }
  }

  private fun snapshot(): SkillsManagementUiState = state.copy(
    skills = state.skills.toList(),
    editor = state.editor.copy(
      draft = state.editor.draft.copy(),
      fieldErrors = state.editor.fieldErrors.toMap(),
    ),
  )

  private sealed interface PreparedDraft {
    data class Valid(
      val frontMatter: Map<String, Any?>,
      val draft: SkillDraft,
    ) : PreparedDraft

    data class Invalid(
      val field: String,
      val detail: String,
    ) : PreparedDraft
  }

  private sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>

    data class Failure(
      val field: String,
      val detail: String,
    ) : ParseResult<Nothing>
  }

  private data class ValidatedSkillDraft(
    val draft: SkillDraft,
    val metadata: NormalizedSkillMetadata,
    val frontMatter: Map<String, Any?>,
  )

  private data class InMemorySkillPackageFile(
    val path: String,
    val content: String,
  )

  private data class InMemorySkillPackage(
    val source: SkillPackageSource,
    val draft: SkillDraft,
    val metadata: NormalizedSkillMetadata,
    val frontMatter: Map<String, Any?>,
    val files: List<InMemorySkillPackageFile>,
    val lifecycleState: SkillLifecycleState,
    val installState: SkillInstallState,
  )
}

internal fun SkillInvocationControl.displayName(): String = when (this) {
  SkillInvocationControl.EXPLICIT_ONLY -> "explicit-only"
  SkillInvocationControl.EXPLICIT_AND_IMPLICIT -> "explicit-and-implicit"
}

internal fun SkillExecutionContext.displayName(): String = when (this) {
  SkillExecutionContext.INLINE -> "inline"
  SkillExecutionContext.FORK -> "fork"
}

internal fun SkillLifecycleState.displayName(): String = when (this) {
  SkillLifecycleState.ACTIVE -> "active"
  SkillLifecycleState.DISABLED -> "disabled"
}

internal fun SkillInstallState.displayName(): String = when (this) {
  SkillInstallState.INSTALLED -> "installed"
  SkillInstallState.NOT_INSTALLED -> "not installed"
}

private fun SkillInvocationControl.serializedValue(): String = when (this) {
  SkillInvocationControl.EXPLICIT_ONLY -> "explicit-only"
  SkillInvocationControl.EXPLICIT_AND_IMPLICIT -> "explicit-and-implicit"
}

private fun SkillExecutionContext.serializedValue(): String = when (this) {
  SkillExecutionContext.INLINE -> "inline"
  SkillExecutionContext.FORK -> "fork"
}

// Learnings: Deterministic in-memory package flows stay concrete by serializing validated front matter into SKILL.md and README snapshots.
// Issues: Android Kotlin LSP still reports unresolved module noise here, so Gradle compile remains the authoritative verification path.
