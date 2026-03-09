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
import com.opencray.skills.SkillExecutionContext
import com.opencray.skills.SkillInvocationControl

class SkillsScreen(
  context: Context,
  private val viewModel: SkillEditorViewModel,
) : ScrollView(context) {
  private val backgroundColor = Color.parseColor("#F5F7FB")
  private val cardColor = Color.WHITE
  private val selectedCardColor = Color.parseColor("#EAF2FF")
  private val borderColor = Color.parseColor("#D7E1ED")
  private val textPrimary = Color.parseColor("#152538")
  private val textSecondary = Color.parseColor("#5D6B7B")
  private val accentColor = Color.parseColor("#2353B6")
  private val errorColor = Color.parseColor("#B3261E")
  private val dangerColor = Color.parseColor("#8E1C1C")

  private var stopObserving: (() -> Unit)? = null
  private var isRendering: Boolean = true

  private val rootContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(20), dp(20), dp(28))
  }

  private val statusText = bodyText()
  private val skillListContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val editorStatusText = bodyText()
  private val selectedSkillText = bodyText()
  private val importExportStatusText = bodyText()

  private val createDraftButton = secondaryButton("Create new draft")
  private val saveDraftButton = primaryButton("Save draft")
  private val toggleLifecycleButton = secondaryButton("Disable selected")
  private val toggleInstallButton = secondaryButton("Install selected")
  private val deleteSkillButton = dangerButton("Delete selected")
  private val importButton = secondaryButton("Import package")
  private val exportButton = secondaryButton("Export package")

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
    rootContainer.addView(titleText("Skills Management"))
    rootContainer.addView(
      helperText(
        "In-memory skill list with create/edit, lifecycle controls, install/uninstall, and package import/export.",
      ),
      blockParams(topDp = 8, bottomDp = 16),
    )

    val statusSection = section(
      title = "Current state",
      subtitle = "This surface stays in memory for now. Save still validates against Task 10 metadata semantics.",
    )
    statusSection.addView(statusText, blockParams(topDp = 8))

    val listSection = section(
      title = "Skill list",
      subtitle = "Select a skill to edit it, or start a fresh draft.",
    )
    listSection.addView(createDraftButton, blockParams(topDp = 8))
    listSection.addView(skillListContainer, blockParams(topDp = 12))

    val editorSection = section(
      title = "Create / edit form",
      subtitle = "Invocation control, permissions, and validation feedback are kept inline in the screen state.",
    )
    editorSection.addView(editorStatusText, blockParams(topDp = 8))
    addField(editorSection, "name", "Lowercase alphanumeric-hyphen only.", nameInput, nameError)
    addField(editorSection, "description", "Required and kept inline for validation feedback.", descriptionInput, descriptionError)
    addField(editorSection, "license", "Optional contract metadata.", licenseInput, licenseError)
    addField(editorSection, "compatibility", "One entry per line or comma-separated.", compatibilityInput, compatibilityError)
    addField(editorSection, "metadata", "String-to-string metadata using key=value lines.", metadataInput, metadataError)

    invocationGroup.addView(explicitOnlyOption)
    invocationGroup.addView(explicitAndImplicitOption)
    addControlBlock(
      editorSection,
      label = "invocation-control",
      helper = "Reuses Task 10 values exactly: explicit-only or explicit-and-implicit.",
      control = invocationGroup,
      error = invocationControlError,
    )

    addControlBlock(
      editorSection,
      label = "user-invocable",
      helper = "Task 10 rejects user-invocable=false together with explicit-only.",
      control = userInvocableCheckbox,
      error = userInvocableError,
    )

    contextGroup.addView(inlineContextOption)
    contextGroup.addView(forkContextOption)
    addControlBlock(
      editorSection,
      label = "context",
      helper = "Reuses Task 10 values exactly: inline or fork.",
      control = contextGroup,
      error = executionContextError,
    )

    addField(editorSection, "agent", "Used only when context=fork.", subagentInput, subagentError)
    addField(editorSection, "allowed-tools", "Comma-separated or one per line.", allowedToolsInput, allowedToolsError)
    addField(editorSection, "tool-permissions", "pattern=allow|ask|deny per line.", toolPermissionsInput, toolPermissionsError)
    addField(editorSection, "subagent-permissions", "pattern=allow|ask|deny per line, fork-only.", subagentPermissionsInput, subagentPermissionsError)
    editorSection.addView(saveDraftButton, blockParams(topDp = 8))

    val lifecycleSection = section(
      title = "Lifecycle + package actions",
      subtitle = "Disable, delete, install, uninstall, import, and export all update in-memory state only.",
    )
    lifecycleSection.addView(selectedSkillText, blockParams(topDp = 8))
    lifecycleSection.addView(buttonRow(toggleLifecycleButton, toggleInstallButton), blockParams(topDp = 12))
    lifecycleSection.addView(buttonRow(deleteSkillButton, importButton), blockParams(topDp = 12))
    lifecycleSection.addView(exportButton, blockParams(topDp = 12))
    lifecycleSection.addView(importExportStatusText, blockParams(topDp = 12))
  }

  private fun bindInteractions() {
    createDraftButton.setOnClickListener { viewModel.createNewSkillDraft() }
    saveDraftButton.setOnClickListener { viewModel.saveDraft() }
    toggleLifecycleButton.setOnClickListener { viewModel.toggleSelectedLifecycle() }
    toggleInstallButton.setOnClickListener { viewModel.toggleSelectedInstallState() }
    deleteSkillButton.setOnClickListener { viewModel.deleteSelectedSkill() }
    importButton.setOnClickListener { viewModel.triggerImportPlaceholder() }
    exportButton.setOnClickListener { viewModel.triggerExportPlaceholder() }

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

    statusText.text = state.statusMessage
    importExportStatusText.text = state.importExportMessage
    editorStatusText.text = state.editor.validationMessage
    editorStatusText.setTextColor(
      if (state.editor.fieldErrors.isEmpty()) textSecondary else errorColor,
    )

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

    renderSkillList(state)

    val selectedSkill = state.skills.firstOrNull { it.id == state.selectedSkillId }
    selectedSkillText.text = if (selectedSkill == null) {
      "No saved skill selected. Save the draft to disable, delete, install, or uninstall it. Import works in memory. Export unlocks when the draft has a name."
    } else {
      buildString {
        append("Selected: ")
        append(selectedSkill.metadata.skillSpec.name)
        append("\nLifecycle: ")
        append(selectedSkill.lifecycleState.displayName())
        append(" • Install: ")
        append(selectedSkill.installState.displayName())
        append("\nInvocation: ")
        append(selectedSkill.metadata.invocationControl.displayName())
        append(" • Context: ")
        append(selectedSkill.metadata.executionContext.displayName())
        append(" • user-invocable=")
        append(selectedSkill.metadata.userInvocable)
      }
    }

    val hasSelectedSkill = selectedSkill != null
    toggleLifecycleButton.isEnabled = hasSelectedSkill
    toggleInstallButton.isEnabled = hasSelectedSkill
    deleteSkillButton.isEnabled = hasSelectedSkill
    exportButton.isEnabled = hasSelectedSkill || state.editor.draft.name.isNotBlank()
    toggleLifecycleButton.text = if (selectedSkill?.lifecycleState == SkillLifecycleState.DISABLED) {
      "Enable selected"
    } else {
      "Disable selected"
    }
    toggleInstallButton.text = if (selectedSkill?.installState == SkillInstallState.INSTALLED) {
      "Uninstall selected"
    } else {
      "Install selected"
    }

    isRendering = false
  }

  private fun renderSkillList(state: SkillsManagementUiState) {
    skillListContainer.removeAllViews()

    if (state.skills.isEmpty()) {
      val emptyText = helperText("No saved skills yet. Use the form below to create the first one.")
      skillListContainer.addView(emptyText)
      return
    }

    state.skills.forEachIndexed { index, skill ->
      val selected = skill.id == state.selectedSkillId
      val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(if (selected) selectedCardColor else cardColor)
        setPadding(dp(14), dp(14), dp(14), dp(14))
      }

      val name = titleText(skill.metadata.skillSpec.name, textSizeSp = 18f)
      val description = bodyText().apply {
        text = skill.metadata.skillSpec.description
        setTextColor(textSecondary)
      }
      val summary = helperText(
        buildString {
          append(skill.lifecycleState.displayName())
          append(" • ")
          append(skill.installState.displayName())
          append(" • ")
          append(skill.metadata.invocationControl.displayName())
          append(" • ")
          append(skill.metadata.executionContext.displayName())
        },
      )
      val permissions = helperText(
        "tool permissions=${skill.metadata.toolPermissions.size} • subagent permissions=${skill.metadata.subagentPermissions.size}",
      )

      val editButton = secondaryButton(if (selected) "Editing" else "Open in editor")
      editButton.isEnabled = !selected
      editButton.setOnClickListener { viewModel.selectSkill(skill.id) }

      card.addView(name)
      card.addView(description, blockParams(topDp = 6))
      card.addView(summary, blockParams(topDp = 8))
      card.addView(permissions, blockParams(topDp = 4))
      card.addView(editButton, blockParams(topDp = 12))

      skillListContainer.addView(card, blockParams(bottomDp = if (index == state.skills.lastIndex) 0 else 12))
    }
  }

  private fun addField(
    parent: LinearLayout,
    label: String,
    helper: String,
    input: EditText,
    error: TextView,
  ) {
    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    container.addView(titleText(label, textSizeSp = 15f))
    container.addView(helperText(helper), blockParams(topDp = 4))
    container.addView(input, blockParams(topDp = 8))
    container.addView(error, blockParams(topDp = 6))
    parent.addView(container, blockParams(topDp = 12))
  }

  private fun addControlBlock(
    parent: LinearLayout,
    label: String,
    helper: String,
    control: View,
    error: TextView,
  ) {
    val container = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    container.addView(titleText(label, textSizeSp = 15f))
    container.addView(helperText(helper), blockParams(topDp = 4))
    container.addView(control, blockParams(topDp = 8))
    container.addView(error, blockParams(topDp = 6))
    parent.addView(container, blockParams(topDp = 12))
  }

  private fun section(
    title: String,
    subtitle: String,
  ): LinearLayout {
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = cardBackground(cardColor)
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    card.addView(titleText(title, textSizeSp = 20f))
    card.addView(helperText(subtitle), blockParams(topDp = 6))

    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    card.addView(content, blockParams(topDp = 12))
    rootContainer.addView(card, blockParams(bottomDp = 16))
    return content
  }

  private fun titleText(
    value: String,
    textSizeSp: Float = 24f,
  ): TextView = TextView(context).apply {
    text = value
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun bodyText(): TextView = TextView(context).apply {
    textSize = 14f
    setTextColor(textPrimary)
  }

  private fun helperText(value: String): TextView = TextView(context).apply {
    text = value
    textSize = 13f
    setTextColor(textSecondary)
  }

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
    background = fieldBackground()
    setPadding(dp(12), dp(10), dp(12), dp(10))
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
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    background = buttonBackground(accentColor)
  }

  private fun secondaryButton(label: String): Button = Button(context).apply {
    text = label
    isAllCaps = false
    setTextColor(accentColor)
    background = outlineButtonBackground(accentColor)
  }

  private fun dangerButton(label: String): Button = Button(context).apply {
    text = label
    isAllCaps = false
    setTextColor(Color.WHITE)
    background = buttonBackground(dangerColor)
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

  private fun cardBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(fillColor)
    setStroke(dp(1), borderColor)
  }

  private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(Color.WHITE)
    setStroke(dp(1), borderColor)
  }

  private fun buttonBackground(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(fillColor)
  }

  private fun outlineButtonBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(Color.WHITE)
    setStroke(dp(1), strokeColor)
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
  ).apply {
    topMargin = dp(topDp)
    bottomMargin = dp(bottomDp)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
