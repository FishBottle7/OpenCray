package com.opencray.ui.skills

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.opencray.ui.design.OpenCrayButtonTone
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocButton
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocCardTitleText
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocInputBackground
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocPillBackground
import com.opencray.ui.design.ocSectionCard
import com.opencray.ui.design.ocSectionTitleText
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl
import org.opencray.ui.R

private enum class SkillsPageMode {
  LIST,
  EDITOR,
}

private data class CollapsibleSection(
  val card: LinearLayout,
  val body: LinearLayout,
  val toggleButton: Button,
)

class SkillsScreen(
  context: Context,
  private val viewModel: SkillEditorViewModel,
) : ScrollView(context) {
  private val backgroundColor = OpenCrayUiTokens.shellBackground
  private val surfaceColor = OpenCrayUiTokens.surface
  private val mutedSurfaceColor = OpenCrayUiTokens.surfaceMuted
  private val selectedSurfaceColor = OpenCrayUiTokens.surfaceInfo
  private val chipColor = OpenCrayUiTokens.surfaceMuted
  private val chipStrongColor = OpenCrayUiTokens.textPrimary
  private val accentColor = OpenCrayUiTokens.primary
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val errorColor = OpenCrayUiTokens.danger
  private val dangerColor = OpenCrayUiTokens.danger

  private var stopObserving: (() -> Unit)? = null
  private var isRendering: Boolean = true
  private var pageMode: SkillsPageMode = SkillsPageMode.LIST
  private var packageSectionExpanded: Boolean = false
  private var permissionsSectionExpanded: Boolean = false

  private val rootContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(32))
  }

  private val listPage = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val editorPage = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  private val listStatusText = helperText()
  private val installedSkillsContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val savedSkillsContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  private val editorTitleView = titleText("")
  private val editorSubtitleView = helperText()
  private val editorStatusText = helperText()
  private val selectedSkillText = bodyText()
  private val importExportStatusText = helperText()

  private val createDraftButton = primaryButton(context.getString(R.string.skills_button_create_new_draft))
  private val backToListButton = secondaryButton(context.getString(R.string.skills_editor_back))
  private val saveDraftButton = primaryButton(context.getString(R.string.skills_button_save_draft))
  private val importButton = secondaryButton(context.getString(R.string.skills_button_import_package))
  private val exportButton = secondaryButton(context.getString(R.string.skills_button_export_package))
  private val toggleLifecycleButton = secondaryButton(context.getString(R.string.skills_button_disable_selected))
  private val toggleInstallButton = secondaryButton(context.getString(R.string.skills_button_install_selected))
  private val deleteSkillButton = dangerButton(context.getString(R.string.skills_button_delete_selected))

  private val nameInput = inputField(singleLine = true, hint = "valid-skill-name")
  private val nameError = errorText()
  private val descriptionInput = inputField(singleLine = false, hint = "Short summary of what the skill does.")
  private val descriptionError = errorText()
  private val licenseInput = inputField(singleLine = true, hint = "Optional license")
  private val licenseError = errorText()
  private val compatibilityInput = inputField(singleLine = false, hint = "One compatibility target per line")
  private val compatibilityError = errorText()
  private val metadataInput = inputField(singleLine = false, hint = "key=value per line")
  private val metadataError = errorText()
  private val subagentInput = inputField(singleLine = true, hint = "review-agent")
  private val subagentError = errorText()
  private val allowedToolsInput = inputField(singleLine = false, hint = "read, grep")
  private val allowedToolsError = errorText()
  private val toolPermissionsInput = inputField(singleLine = false, hint = "read=allow\ngrep=ask")
  private val toolPermissionsError = errorText()
  private val subagentPermissionsInput = inputField(singleLine = false, hint = "review-agent=allow")
  private val subagentPermissionsError = errorText()
  private val invocationControlError = errorText()
  private val userInvocableError = errorText()
  private val executionContextError = errorText()

  private val invocationGroup = RadioGroup(context).apply {
    orientation = RadioGroup.VERTICAL
  }
  private val explicitOnlyOption = radioButton("explicit-only")
  private val explicitAndImplicitOption = radioButton("explicit-and-implicit")

  private val contextGroup = RadioGroup(context).apply {
    orientation = RadioGroup.VERTICAL
  }
  private val inlineContextOption = radioButton("inline")
  private val forkContextOption = radioButton("fork")

  private val userInvocableCheckbox = CheckBox(context).apply {
    text = "user-invocable"
    setTextColor(textPrimary)
  }

  private lateinit var packageSection: CollapsibleSection
  private lateinit var permissionsSection: CollapsibleSection

  init {
    isFillViewport = true
    setBackgroundColor(backgroundColor)

    addView(
      rootContainer,
      LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
      ),
    )

    buildScreen()
    bindInteractions()
    renderPageMode()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (stopObserving == null) {
      stopObserving = viewModel.observe(::render)
    }
  }

  override fun onDetachedFromWindow() {
    stopObserving?.invoke()
    stopObserving = null
    super.onDetachedFromWindow()
  }

  private fun buildScreen() {
    rootContainer.addView(listPage)
    rootContainer.addView(editorPage)

    buildListPage()
    buildEditorPage()
  }

  private fun buildListPage() {
    listPage.addView(titleText(context.getString(R.string.skills_title)))
    listPage.addView(helperText(context.getString(R.string.skills_subtitle)), blockParams(topDp = 8))
    listPage.addView(createDraftButton, blockParams(topDp = 16))
    listPage.addView(listStatusText, blockParams(topDp = 10, bottomDp = 20))

    listPage.addView(sectionHeading(
      title = context.getString(R.string.skills_installed_title),
      subtitle = context.getString(R.string.skills_installed_subtitle),
    ))
    listPage.addView(installedSkillsContainer, blockParams(topDp = 12, bottomDp = 20))

    listPage.addView(sectionHeading(
      title = context.getString(R.string.skills_saved_title),
      subtitle = context.getString(R.string.skills_saved_subtitle),
    ))
    listPage.addView(savedSkillsContainer, blockParams(topDp = 12))
  }

  private fun buildEditorPage() {
    editorPage.addView(backToListButton)
    editorPage.addView(editorTitleView, blockParams(topDp = 18))
    editorPage.addView(editorSubtitleView, blockParams(topDp = 8))
    editorPage.addView(editorStatusText, blockParams(topDp = 10))
    editorPage.addView(
      buttonRow(saveDraftButton, importButton),
      blockParams(topDp = 16),
    )
    editorPage.addView(exportButton, blockParams(topDp = 10))

    val basicsSection = contentSection(
      title = context.getString(R.string.skills_editor_basics_title),
      subtitle = context.getString(R.string.skills_editor_basics_subtitle),
    )
    addField(basicsSection, "name", nameInput, nameError)
    addField(basicsSection, "description", descriptionInput, descriptionError)

    val behaviorSection = contentSection(
      title = context.getString(R.string.skills_editor_behavior_title),
      subtitle = context.getString(R.string.skills_editor_behavior_subtitle),
    )
    invocationGroup.addView(explicitOnlyOption)
    invocationGroup.addView(explicitAndImplicitOption)
    addControlBlock(behaviorSection, "invocation-control", invocationGroup, invocationControlError)
    addControlBlock(behaviorSection, "user-invocable", userInvocableCheckbox, userInvocableError)
    contextGroup.addView(inlineContextOption)
    contextGroup.addView(forkContextOption)
    addControlBlock(behaviorSection, "context", contextGroup, executionContextError)

    packageSection = collapsibleContentSection(
      title = context.getString(R.string.skills_editor_package_title),
      subtitle = context.getString(R.string.skills_editor_package_subtitle),
    )
    addField(packageSection.body, "license", licenseInput, licenseError)
    addField(packageSection.body, "compatibility", compatibilityInput, compatibilityError)
    addField(packageSection.body, "metadata", metadataInput, metadataError)

    permissionsSection = collapsibleContentSection(
      title = context.getString(R.string.skills_editor_permissions_title),
      subtitle = context.getString(R.string.skills_editor_permissions_subtitle),
    )
    addField(permissionsSection.body, "allowed-tools", allowedToolsInput, allowedToolsError)
    addField(permissionsSection.body, "tool-permissions", toolPermissionsInput, toolPermissionsError)
    addField(permissionsSection.body, "agent", subagentInput, subagentError)
    addField(permissionsSection.body, "subagent-permissions", subagentPermissionsInput, subagentPermissionsError)

    val actionsSection = contentSection(
      title = context.getString(R.string.skills_editor_actions_title),
      subtitle = context.getString(R.string.skills_editor_actions_subtitle),
    )
    actionsSection.addView(selectedSkillText)
    actionsSection.addView(buttonRow(toggleLifecycleButton, toggleInstallButton), blockParams(topDp = 14))
    actionsSection.addView(deleteSkillButton, blockParams(topDp = 10))
    actionsSection.addView(importExportStatusText, blockParams(topDp = 12))
  }

  private fun bindInteractions() {
    createDraftButton.setOnClickListener {
      viewModel.createNewSkillDraft()
      packageSectionExpanded = false
      permissionsSectionExpanded = false
      pageMode = SkillsPageMode.EDITOR
      renderCollapsibleSections()
      renderPageMode()
    }
    backToListButton.setOnClickListener {
      pageMode = SkillsPageMode.LIST
      renderPageMode()
    }
    packageSection.toggleButton.setOnClickListener {
      packageSectionExpanded = !packageSectionExpanded
      renderCollapsibleSections()
    }
    permissionsSection.toggleButton.setOnClickListener {
      permissionsSectionExpanded = !permissionsSectionExpanded
      renderCollapsibleSections()
    }
    saveDraftButton.setOnClickListener { viewModel.saveDraft() }
    importButton.setOnClickListener { viewModel.triggerImportPlaceholder() }
    exportButton.setOnClickListener { viewModel.triggerExportPlaceholder() }
    toggleLifecycleButton.setOnClickListener { viewModel.toggleSelectedLifecycle() }
    toggleInstallButton.setOnClickListener { viewModel.toggleSelectedInstallState() }
    deleteSkillButton.setOnClickListener {
      viewModel.deleteSelectedSkill()
      pageMode = SkillsPageMode.LIST
      renderPageMode()
    }

    bindText(nameInput) { value -> viewModel.updateName(value) }
    bindText(descriptionInput) { value -> viewModel.updateDescription(value) }
    bindText(licenseInput) { value -> viewModel.updateLicense(value) }
    bindText(compatibilityInput) { value -> viewModel.updateCompatibility(value) }
    bindText(metadataInput) { value -> viewModel.updateMetadataText(value) }
    bindText(subagentInput) { value -> viewModel.updateSubagent(value) }
    bindText(allowedToolsInput) { value -> viewModel.updateAllowedTools(value) }
    bindText(toolPermissionsInput) { value -> viewModel.updateToolPermissionsText(value) }
    bindText(subagentPermissionsInput) { value -> viewModel.updateSubagentPermissionsText(value) }

    invocationGroup.setOnCheckedChangeListener { _, checkedId ->
      if (isRendering) {
        return@setOnCheckedChangeListener
      }
      val value = when (checkedId) {
        explicitOnlyOption.id -> SkillInvocationControl.EXPLICIT_ONLY
        else -> SkillInvocationControl.EXPLICIT_AND_IMPLICIT
      }
      viewModel.updateInvocationControl(value)
    }

    contextGroup.setOnCheckedChangeListener { _, checkedId ->
      if (isRendering) {
        return@setOnCheckedChangeListener
      }
      val value = when (checkedId) {
        forkContextOption.id -> SkillExecutionContext.FORK
        else -> SkillExecutionContext.INLINE
      }
      viewModel.updateExecutionContext(value)
    }

    userInvocableCheckbox.setOnCheckedChangeListener { _, isChecked ->
      if (isRendering) {
        return@setOnCheckedChangeListener
      }
      viewModel.updateUserInvocable(isChecked)
    }
  }

  private fun render(state: SkillsManagementUiState) {
    isRendering = true

    setTextIfChanged(nameInput, state.editor.draft.name)
    setTextIfChanged(descriptionInput, state.editor.draft.description)
    setTextIfChanged(licenseInput, state.editor.draft.license)
    setTextIfChanged(compatibilityInput, state.editor.draft.compatibility)
    setTextIfChanged(metadataInput, state.editor.draft.metadataText)
    setTextIfChanged(subagentInput, state.editor.draft.subagent)
    setTextIfChanged(allowedToolsInput, state.editor.draft.allowedTools)
    setTextIfChanged(toolPermissionsInput, state.editor.draft.toolPermissionsText)
    setTextIfChanged(subagentPermissionsInput, state.editor.draft.subagentPermissionsText)

    invocationGroup.check(
      when (state.editor.draft.invocationControl) {
        SkillInvocationControl.EXPLICIT_ONLY -> explicitOnlyOption.id
        SkillInvocationControl.EXPLICIT_AND_IMPLICIT -> explicitAndImplicitOption.id
      },
    )
    contextGroup.check(
      when (state.editor.draft.executionContext) {
        SkillExecutionContext.INLINE -> inlineContextOption.id
        SkillExecutionContext.FORK -> forkContextOption.id
      },
    )
    userInvocableCheckbox.isChecked = state.editor.draft.userInvocable

    val forkControlsEnabled = state.editor.draft.executionContext == SkillExecutionContext.FORK
    subagentInput.isEnabled = forkControlsEnabled
    subagentPermissionsInput.isEnabled = forkControlsEnabled
    subagentInput.alpha = if (forkControlsEnabled) 1f else 0.55f
    subagentPermissionsInput.alpha = if (forkControlsEnabled) 1f else 0.55f

    renderError(nameError, state.editor.fieldErrors[FIELD_NAME])
    renderError(descriptionError, state.editor.fieldErrors[FIELD_DESCRIPTION])
    renderError(licenseError, state.editor.fieldErrors[FIELD_LICENSE])
    renderError(compatibilityError, state.editor.fieldErrors[FIELD_COMPATIBILITY])
    renderError(metadataError, state.editor.fieldErrors[FIELD_METADATA])
    renderError(invocationControlError, state.editor.fieldErrors[FIELD_INVOCATION_CONTROL])
    renderError(userInvocableError, state.editor.fieldErrors[FIELD_USER_INVOCABLE])
    renderError(executionContextError, state.editor.fieldErrors[FIELD_CONTEXT])
    renderError(subagentError, state.editor.fieldErrors[FIELD_AGENT])
    renderError(allowedToolsError, state.editor.fieldErrors[FIELD_ALLOWED_TOOLS])
    renderError(toolPermissionsError, state.editor.fieldErrors[FIELD_TOOL_PERMISSIONS])
    renderError(subagentPermissionsError, state.editor.fieldErrors[FIELD_SUBAGENT_PERMISSIONS])

    renderListPage(state)
    renderEditorPage(state)
    renderCollapsibleSections()

    isRendering = false
  }

  private fun renderListPage(state: SkillsManagementUiState) {
    val installedSkills = state.skills.filter { it.installState == SkillInstallState.INSTALLED }
    val savedSkills = state.skills.filter { it.installState != SkillInstallState.INSTALLED }
    listStatusText.text = if (state.skills.isEmpty()) {
      context.getString(R.string.skills_list_summary_empty)
    } else {
      context.getString(
        R.string.skills_list_summary,
        installedSkills.size,
        state.skills.size,
      )
    }

    renderSkillGroup(
      container = installedSkillsContainer,
      skills = installedSkills,
      emptyMessage = context.getString(R.string.skills_empty_installed),
      selectedSkillId = state.selectedSkillId,
    )
    renderSkillGroup(
      container = savedSkillsContainer,
      skills = savedSkills,
      emptyMessage = context.getString(R.string.skills_empty_saved),
      selectedSkillId = state.selectedSkillId,
    )
  }

  private fun renderEditorPage(state: SkillsManagementUiState) {
    val selectedSkill = state.skills.firstOrNull { it.id == state.selectedSkillId }

    editorTitleView.text = if (selectedSkill == null) {
      context.getString(R.string.skills_editor_create_title)
    } else {
      context.getString(R.string.skills_editor_edit_title)
    }
    editorSubtitleView.text = if (selectedSkill == null) {
      context.getString(R.string.skills_editor_create_subtitle)
    } else {
      context.getString(R.string.skills_editor_edit_subtitle)
    }

    editorStatusText.text = if (state.editor.fieldErrors.isEmpty()) {
      if (selectedSkill == null) {
        context.getString(R.string.skills_editor_status_create)
      } else {
        context.getString(R.string.skills_editor_status_edit, selectedSkill.metadata.skillSpec.name)
      }
    } else {
      state.editor.validationMessage
    }
    editorStatusText.setTextColor(if (state.editor.fieldErrors.isEmpty()) textSecondary else errorColor)

    selectedSkillText.text = if (selectedSkill == null) {
      context.getString(R.string.skills_editor_selection_none)
    } else {
      buildString {
        append(selectedSkill.metadata.skillSpec.name)
        append("\n")
        append(selectedSkill.lifecycleState.displayName())
        append(" • ")
        append(selectedSkill.installState.displayName())
        append(" • ")
        append(selectedSkill.metadata.invocationControl.displayName())
        append(" • ")
        append(selectedSkill.metadata.executionContext.displayName())
      }
    }
    importExportStatusText.text = state.importExportMessage

    val hasSelectedSkill = selectedSkill != null
    toggleLifecycleButton.isEnabled = hasSelectedSkill
    toggleInstallButton.isEnabled = hasSelectedSkill
    deleteSkillButton.isEnabled = hasSelectedSkill
    exportButton.isEnabled = hasSelectedSkill || state.editor.draft.name.isNotBlank()
    toggleLifecycleButton.text = if (selectedSkill?.lifecycleState == SkillLifecycleState.DISABLED) {
      context.getString(R.string.skills_button_enable_selected)
    } else {
      context.getString(R.string.skills_button_disable_selected)
    }
    toggleInstallButton.text = if (selectedSkill?.installState == SkillInstallState.INSTALLED) {
      context.getString(R.string.skills_button_uninstall_selected)
    } else {
      context.getString(R.string.skills_button_install_selected)
    }
  }

  private fun renderCollapsibleSections() {
    packageSection.body.visibility = if (packageSectionExpanded) View.VISIBLE else View.GONE
    permissionsSection.body.visibility = if (permissionsSectionExpanded) View.VISIBLE else View.GONE
    packageSection.toggleButton.text = context.getString(
      if (packageSectionExpanded) {
        R.string.skills_section_hide
      } else {
        R.string.skills_section_show
      },
    )
    permissionsSection.toggleButton.text = context.getString(
      if (permissionsSectionExpanded) {
        R.string.skills_section_hide
      } else {
        R.string.skills_section_show
      },
    )
  }

  private fun renderSkillGroup(
    container: LinearLayout,
    skills: List<ManagedSkill>,
    emptyMessage: String,
    selectedSkillId: String?,
  ) {
    container.removeAllViews()

    if (skills.isEmpty()) {
      container.addView(emptyStateLabel(emptyMessage))
      return
    }

    skills.forEachIndexed { index, skill ->
      val selected = skill.id == selectedSkillId
      val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = skillCardBackground(selected)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        isClickable = true
        isFocusable = true
        setOnClickListener {
          viewModel.selectSkill(skill.id)
          packageSectionExpanded = false
          permissionsSectionExpanded = false
          pageMode = SkillsPageMode.EDITOR
          renderCollapsibleSections()
          renderPageMode()
        }
      }

      val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
      titleRow.addView(
        titleText(skill.metadata.skillSpec.name, textSizeSp = 18f),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
          marginEnd = dp(10)
        },
      )
      titleRow.addView(skillChip(skill.installState.displayName(), emphasized = skill.installState == SkillInstallState.INSTALLED))
      if (skill.lifecycleState == SkillLifecycleState.DISABLED) {
        titleRow.addView(skillChip(skill.lifecycleState.displayName(), emphasized = false), chipLayoutParams())
      }
      if (selected) {
        titleRow.addView(skillChip(context.getString(R.string.skills_card_selected), emphasized = true), chipLayoutParams())
      }

      val descriptionView = bodyText().apply {
        text = skill.metadata.skillSpec.description
        setTextColor(textSecondary)
      }
      val summaryView = helperText(
        buildString {
          append(skill.metadata.invocationControl.displayName())
          append(" • ")
          append(skill.metadata.executionContext.displayName())
          append(" • ")
          append(context.getString(R.string.skills_card_tap_to_edit))
        },
      )

      card.addView(titleRow)
      card.addView(descriptionView, blockParams(topDp = 8))
      card.addView(summaryView, blockParams(topDp = 10))

      container.addView(card, blockParams(bottomDp = if (index == skills.lastIndex) 0 else 10))
    }
  }

  private fun renderPageMode() {
    listPage.visibility = if (pageMode == SkillsPageMode.LIST) View.VISIBLE else View.GONE
    editorPage.visibility = if (pageMode == SkillsPageMode.EDITOR) View.VISIBLE else View.GONE
  }

  private fun sectionHeading(
    title: String,
    subtitle: String,
  ): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    addView(titleText(title, textSizeSp = 20f))
    addView(helperText(subtitle), blockParams(topDp = 6))
  }

  private fun contentSection(
    title: String,
    subtitle: String,
  ): LinearLayout {
    val section = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = sectionBackground()
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    section.addView(titleText(title, textSizeSp = 18f))
    section.addView(helperText(subtitle), blockParams(topDp = 6))
    editorPage.addView(section, blockParams(topDp = 18))
    return section
  }

  private fun collapsibleContentSection(
    title: String,
    subtitle: String,
  ): CollapsibleSection {
    val section = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = sectionBackground()
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    val headerRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val headerTexts = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    headerTexts.addView(titleText(title, textSizeSp = 18f))
    headerTexts.addView(helperText(subtitle), blockParams(topDp = 6))
    val toggleButton = tertiaryButton("")
    headerRow.addView(
      headerTexts,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(12)
      },
    )
    headerRow.addView(toggleButton)
    section.addView(headerRow)

    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    section.addView(body, blockParams(topDp = 14))
    editorPage.addView(section, blockParams(topDp = 18))
    return CollapsibleSection(
      card = section,
      body = body,
      toggleButton = toggleButton,
    )
  }

  private fun addField(
    parent: LinearLayout,
    label: String,
    input: EditText,
    error: TextView,
  ) {
    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    container.addView(titleText(label, textSizeSp = 14f))
    container.addView(input, blockParams(topDp = 8))
    container.addView(error, blockParams(topDp = 6))
    parent.addView(container, blockParams(topDp = 12))
  }

  private fun addControlBlock(
    parent: LinearLayout,
    label: String,
    control: View,
    error: TextView,
  ) {
    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    container.addView(titleText(label, textSizeSp = 14f))
    container.addView(control, blockParams(topDp = 8))
    container.addView(error, blockParams(topDp = 6))
    parent.addView(container, blockParams(topDp = 12))
  }

  private fun emptyStateLabel(value: String): TextView = helperText(value).apply {
    background = emptyStateBackground()
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float = 28f,
  ): TextView = if (textSizeSp >= 24f) {
    context.ocSectionTitleText(value, textSizeSp)
  } else {
    context.ocCardTitleText(value, textSizeSp)
  }

  private fun bodyText(): TextView = context.ocBodyText()

  private fun helperText(value: String = ""): TextView = context.ocMetaText(value)

  private fun errorText(): TextView = TextView(context).apply {
    textSize = 13f
    setTextColor(errorColor)
    visibility = View.GONE
  }

  private fun inputField(
    singleLine: Boolean,
    hint: String,
  ): EditText = EditText(context).apply {
    this.hint = hint
    setTextColor(textPrimary)
    setHintTextColor(textSecondary)
    background = context.ocInputBackground(fillColor = OpenCrayUiTokens.surface)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    isSingleLine = singleLine
    if (singleLine) {
      inputType = InputType.TYPE_CLASS_TEXT
    } else {
      minLines = 3
      gravity = Gravity.TOP or Gravity.START
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    }
  }

  private fun radioButton(label: String): RadioButton = RadioButton(context).apply {
    id = View.generateViewId()
    text = label
    setTextColor(textPrimary)
  }

  private fun primaryButton(label: String): Button = Button(context).apply {
    val styled = context.ocButton(label, OpenCrayButtonTone.PRIMARY)
    text = styled.text
    isAllCaps = styled.isAllCaps
    setTextColor(styled.currentTextColor)
    background = styled.background
    minHeight = styled.minHeight
    minimumHeight = styled.minimumHeight
    stateListAnimator = null
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun secondaryButton(label: String): Button = Button(context).apply {
    val styled = context.ocButton(label, OpenCrayButtonTone.SECONDARY)
    text = styled.text
    isAllCaps = styled.isAllCaps
    setTextColor(styled.currentTextColor)
    background = styled.background
    minHeight = styled.minHeight
    minimumHeight = styled.minimumHeight
    stateListAnimator = null
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun tertiaryButton(label: String): Button = Button(context).apply {
    text = label
    isAllCaps = false
    setTextColor(textSecondary)
    background = filledButtonBackground(OpenCrayUiTokens.surface)
    stateListAnimator = null
    setPadding(dp(12), dp(10), dp(12), dp(10))
  }

  private fun dangerButton(label: String): Button = Button(context).apply {
    val styled = context.ocButton(label, OpenCrayButtonTone.DANGER)
    text = styled.text
    isAllCaps = styled.isAllCaps
    setTextColor(styled.currentTextColor)
    background = styled.background
    minHeight = styled.minHeight
    minimumHeight = styled.minimumHeight
    stateListAnimator = null
    setPadding(dp(20), dp(12), dp(20), dp(12))
  }

  private fun buttonRow(
    leftButton: Button,
    rightButton: Button,
  ): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    addView(
      leftButton,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
      },
    )
    addView(
      rightButton,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
  }

  private fun bindText(
    input: EditText,
    onChange: (String) -> Unit,
  ) {
    input.addTextChangedListener(
      object : TextWatcher {
        override fun beforeTextChanged(
          s: CharSequence?,
          start: Int,
          count: Int,
          after: Int,
        ) = Unit

        override fun onTextChanged(
          s: CharSequence?,
          start: Int,
          before: Int,
          count: Int,
        ) = Unit

        override fun afterTextChanged(s: Editable?) {
          if (isRendering) {
            return
          }
          onChange(s?.toString().orEmpty())
        }
      },
    )
  }

  private fun setTextIfChanged(
    input: EditText,
    value: String,
  ) {
    if (input.text?.toString() != value) {
      input.setText(value)
      input.setSelection(input.text?.length ?: 0)
    }
  }

  private fun renderError(
    textView: TextView,
    message: String?,
  ) {
    if (message.isNullOrBlank()) {
      textView.text = ""
      textView.visibility = View.GONE
    } else {
      textView.text = message
      textView.visibility = View.VISIBLE
    }
  }

  private fun skillCardBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(
      tone = if (selected) OpenCraySurfaceTone.INFO else OpenCraySurfaceTone.NEUTRAL,
      stroked = true,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun sectionBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(OpenCraySurfaceTone.NEUTRAL, stroked = true)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun emptyStateBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(OpenCraySurfaceTone.SUBTLE, radiusDp = 14, stroked = true)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocInputBackground(fillColor = OpenCrayUiTokens.surface)
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setStroke(dp(1), OpenCrayUiTokens.border)
  }

  private fun filledButtonBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(
      tone = when (fillColor) {
        accentColor -> OpenCraySurfaceTone.ACCENT
        dangerColor -> OpenCraySurfaceTone.DANGER
        else -> OpenCraySurfaceTone.SUBTLE
      },
      radiusDp = OpenCrayUiTokens.radiusButton,
      stroked = fillColor != accentColor && fillColor != dangerColor,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun chipBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocPillBackground(
      fillColor = fillColor,
      strokeColor = if (fillColor == chipStrongColor) chipStrongColor else OpenCrayUiTokens.border,
      strokeWidthDp = 1,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
  }

  private fun skillChip(
    label: String,
    emphasized: Boolean,
  ): TextView = TextView(context).apply {
    text = label
    textSize = 12f
    setTextColor(if (emphasized) Color.WHITE else textPrimary)
    setTypeface(typeface, Typeface.BOLD)
    background = chipBackground(if (emphasized) chipStrongColor else chipColor)
    setPadding(dp(10), dp(6), dp(10), dp(6))
  }

  private fun chipLayoutParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    marginStart = dp(6)
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}
