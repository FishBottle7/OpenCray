package com.opencray.ui.skills

import android.app.AlertDialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocCardTitleText
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocSectionTitleText
import org.opencray.ui.R

class SkillsScreen(
  context: Context,
  private val viewModel: SkillEditorViewModel,
) : ScrollView(context) {
  private data class ToggleAnimationRequest(
    val skillId: String,
    val fromEnabled: Boolean,
  )

  private val shellBackground = OpenCrayUiTokens.shellBackground
  private val surface = OpenCrayUiTokens.surface
  private val surfaceMuted = OpenCrayUiTokens.surfaceMuted
  private val border = OpenCrayUiTokens.border
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val textSecondary = OpenCrayUiTokens.textSecondary
  private val accent = OpenCrayUiTokens.primary
  private val danger = OpenCrayUiTokens.danger
  private val overflowFill = Color.parseColor("#F7F7FA")
  private val installButtonFill = Color.parseColor("#EEF5FF")
  private val smoothInterpolator = PathInterpolator(0.22f, 0f, 0f, 1f)
  private val springInterpolator = OvershootInterpolator(0.65f)

  private var stopObserving: (() -> Unit)? = null
  private var isRendering: Boolean = false
  private var renderedPage: SkillsPage? = null
  private var lastExpandedSkillId: String? = null
  private var pendingToggleAnimation: ToggleAnimationRequest? = null
  private var selectedSegmentLeft: Int? = null

  private val rootContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(24))
  }

  private val eyebrowView = TextView(context).apply {
    text = context.getString(R.string.skills_eyebrow)
    setTextColor(textSecondary)
    textSize = 12f
    setTypeface(typeface, Typeface.BOLD)
  }
  private val titleView = titleText(context.getString(R.string.skills_title))
  private val subtitleView = helperText(context.getString(R.string.skills_subtitle))

  private val summaryCard = surfaceCard()
  private val summaryTitleView = cardTitleText("")
  private val summaryMetaView = bodyText()

  private val segmentedControl = segmentedContainer()
  private val segmentedIndicator = View(context)
  private val segmentedButtonsRow = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
  }
  private val manageSegment = segmentedButton(context.getString(R.string.skills_manage_tab))
  private val installSegment = segmentedButton(context.getString(R.string.skills_install_tab))

  private val managePage = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }
  private val installPage = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
  }

  private val manageHintView = helperText(context.getString(R.string.skills_manage_hint)).apply {
    setTypeface(typeface, Typeface.BOLD)
    textSize = 13f
  }
  private val manageCard = surfaceCard()

  private val installSearchInput = EditText(context).apply {
    hint = context.getString(R.string.skills_install_search_hint)
    setTextColor(textPrimary)
    setHintTextColor(Color.parseColor("#8E8E93"))
    background = roundedRect(fillColor = surface, radiusDp = 14)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    isSingleLine = true
    inputType = InputType.TYPE_CLASS_TEXT
  }
  private val installSourcesLabel = helperText(context.getString(R.string.skills_install_sources_label)).apply {
    setTypeface(typeface, Typeface.BOLD)
    textSize = 13f
  }
  private val installSourcesCard = surfaceCard()
  private val suggestedLabel = helperText(context.getString(R.string.skills_install_suggested_label)).apply {
    setTypeface(typeface, Typeface.BOLD)
    textSize = 13f
  }
  private val suggestedCard = surfaceCard()
  private val suggestedEmptyView = helperText(context.getString(R.string.skills_install_suggested_empty)).apply {
    background = roundedRect(fillColor = surface, radiusDp = 16)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  init {
    isFillViewport = true
    setBackgroundColor(shellBackground)
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
    rootContainer.addView(eyebrowView)
    rootContainer.addView(titleView, blockParams(topDp = 8))
    rootContainer.addView(subtitleView, blockParams(topDp = 8))

    summaryCard.setPadding(dp(16), dp(16), dp(16), dp(16))
    summaryCard.addView(summaryTitleView)
    summaryCard.addView(summaryMetaView, blockParams(topDp = 10))
    rootContainer.addView(summaryCard, blockParams(topDp = 18))

    segmentedIndicator.background = roundedRect(fillColor = surface, radiusDp = 999)
    segmentedControl.addView(
      segmentedIndicator,
      FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setMargins(dp(3), dp(3), dp(3), dp(3))
      },
    )
    segmentedButtonsRow.addView(
      manageSegment,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        marginEnd = dp(2)
      },
    )
    segmentedButtonsRow.addView(
      installSegment,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
        marginStart = dp(2)
      },
    )
    segmentedControl.addView(
      segmentedButtonsRow,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )
    rootContainer.addView(
      segmentedControl,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        dp(36),
      ).apply {
        topMargin = dp(12)
      },
    )

    buildManagePage()
    buildInstallPage()
    rootContainer.addView(managePage, blockParams(topDp = 16))
    rootContainer.addView(installPage, blockParams(topDp = 16))
  }

  private fun buildManagePage() {
    managePage.addView(manageHintView)
    managePage.addView(manageCard, blockParams(topDp = 12))
  }

  private fun buildInstallPage() {
    installPage.addView(installSearchInput)
    installPage.addView(installSourcesLabel, blockParams(topDp = 14))
    installPage.addView(installSourcesCard, blockParams(topDp = 10))
    installPage.addView(suggestedLabel, blockParams(topDp = 14))
    installPage.addView(suggestedCard, blockParams(topDp = 10))
    installPage.addView(suggestedEmptyView, blockParams(topDp = 10))
  }

  private fun bindInteractions() {
    manageSegment.setOnClickListener { viewModel.selectPage(SkillsPage.MANAGE) }
    installSegment.setOnClickListener { viewModel.selectPage(SkillsPage.INSTALL) }
    installSearchInput.addTextChangedListener(
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
          viewModel.updateInstallQuery(s?.toString().orEmpty())
        }
      },
    )
  }

  private fun render(state: SkillsManagementUiState) {
    isRendering = true
    val previousPage = renderedPage
    val previousExpandedSkillId = lastExpandedSkillId

    summaryTitleView.text = when (state.selectedPage) {
      SkillsPage.MANAGE -> context.getString(R.string.skills_summary_manage_title)
      SkillsPage.INSTALL -> context.getString(R.string.skills_summary_install_title)
    }
    summaryMetaView.text = when (state.selectedPage) {
      SkillsPage.MANAGE -> context.getString(
        R.string.skills_summary_manage_meta,
        state.skills.count { skill -> skill.isEnabled },
        state.skills.count { skill -> !skill.isEnabled },
      )

      SkillsPage.INSTALL -> context.getString(R.string.skills_summary_install_meta)
    }
    summaryMetaView.setTextColor(textSecondary)

    renderSegmentSelection(
      selected = state.selectedPage,
    )

    if (previousPage == null) {
      managePage.visibility = if (state.selectedPage == SkillsPage.MANAGE) View.VISIBLE else View.GONE
      installPage.visibility = if (state.selectedPage == SkillsPage.INSTALL) View.VISIBLE else View.GONE
      managePage.alpha = 1f
      installPage.alpha = 1f
      managePage.translationY = 0f
      installPage.translationY = 0f
    }

    if (installSearchInput.text?.toString() != state.installQuery) {
      installSearchInput.setText(state.installQuery)
      installSearchInput.setSelection(installSearchInput.text?.length ?: 0)
    }

    renderManageCard(state)
    renderInstallSources(state.installSources)
    renderSuggestedSkills(state.suggestedSkills)

    renderedPage = state.selectedPage
    lastExpandedSkillId = state.expandedSkillId
    isRendering = false

    if (previousPage != null && previousPage != state.selectedPage) {
      animatePageTransition(fromPage = previousPage, toPage = state.selectedPage)
      if (state.selectedPage == SkillsPage.INSTALL) {
        installPage.post { animateInstallPageEntrance() }
      }
    }

    val expandingSkillId = state.expandedSkillId
    if (expandingSkillId != null && expandingSkillId != previousExpandedSkillId) {
      manageCard.post {
        val actionRow = manageCard.findViewWithTag<View>("skill-actions-$expandingSkillId")
        actionRow?.let(::animateActionRowOpen)
      }
    }
  }

  private fun renderManageCard(state: SkillsManagementUiState) {
    manageCard.removeAllViews()

    if (state.skills.isEmpty()) {
      manageCard.addView(
        helperText(context.getString(R.string.skills_manage_empty)).apply {
          setPadding(dp(16), dp(16), dp(16), dp(16))
        },
      )
      return
    }

    state.skills.forEachIndexed { index, skill ->
      manageCard.addView(buildSkillRow(skill = skill, expanded = state.expandedSkillId == skill.id))
      if (index != state.skills.lastIndex) {
        manageCard.addView(divider())
      }
    }
  }

  private fun buildSkillRow(
    skill: WorkspaceSkillItem,
    expanded: Boolean,
  ): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL

    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = dp(64)
      setPadding(dp(16), dp(14), dp(16), dp(14))
      isClickable = true
      isFocusable = true
      setOnClickListener {
        if (expanded) {
          animateActionRowClose(skill.id)
        } else {
          viewModel.toggleSkillMenu(skill.id)
        }
      }
      setOnLongClickListener {
        if (expanded) {
          animateActionRowClose(skill.id)
        } else {
          viewModel.toggleSkillMenu(skill.id)
        }
        true
      }
    }

    val textColumn = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    textColumn.addView(cardTitleText(skill.name, textSizeSp = 16f))
    textColumn.addView(
      helperText(skill.description).apply {
        setTextColor(Color.parseColor("#8E8E93"))
        textSize = 12f
      },
      blockParams(topDp = 2),
    )
    row.addView(
      textColumn,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(12)
      },
    )

    val overflowButton = overflowButton(active = expanded).apply {
      contentDescription = context.getString(R.string.skills_more_actions_content_description, skill.name)
      setOnClickListener {
        if (expanded) {
          animateActionRowClose(skill.id)
        } else {
          viewModel.toggleSkillMenu(skill.id)
        }
      }
    }
    row.addView(
      overflowButton,
      LinearLayout.LayoutParams(dp(30), dp(30)).apply {
        marginEnd = dp(10)
      },
    )

    val toggleView = SkillToggleView(context).apply {
      val toggleAnimation = pendingToggleAnimation?.takeIf { request ->
        request.skillId == skill.id && request.fromEnabled != skill.isEnabled
      }
      if (toggleAnimation == null) {
        render(skill.isEnabled)
      } else {
        render(toggleAnimation.fromEnabled)
      }
      contentDescription = context.getString(R.string.skills_toggle_content_description, skill.name)
      setOnClickListener {
        pendingToggleAnimation = ToggleAnimationRequest(
          skillId = skill.id,
          fromEnabled = skill.isEnabled,
        )
        viewModel.toggleSkillEnabled(skill.id)
      }
      if (toggleAnimation != null) {
        post {
          animateTo(skill.isEnabled)
          pendingToggleAnimation = null
        }
      }
    }
    row.addView(toggleView)

    addView(row)
    tag = "skill-row-${skill.id}"

    if (expanded) {
      addView(
        buildInlineActionRow(skill).apply {
          tag = "skill-actions-${skill.id}"
        },
      )
    }
  }

  private fun buildInlineActionRow(skill: WorkspaceSkillItem): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.END
    setPadding(dp(16), 0, dp(16), dp(8))

    val floatingMenu = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = floatingMenuBackground()
      elevation = dp(2).toFloat()
      setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    floatingMenu.addView(
      inlineActionButton(
        label = context.getString(R.string.skills_action_upgrade),
        textColor = accent,
      ).apply {
        setOnClickListener {
          val message = viewModel.upgradeSkill(skill.id)
          showToast(message)
        }
      },
    )
    floatingMenu.addView(
      inlineActionButton(
        label = context.getString(R.string.skills_action_delete),
        textColor = if (skill.canDelete) danger else textSecondary,
      ).apply {
        isEnabled = skill.canDelete
        alpha = if (skill.canDelete) 1f else 0.45f
        setOnClickListener {
          confirmDelete(skill)
        }
      },
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        marginStart = dp(18)
      },
    )
    addView(
      floatingMenu,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        marginEnd = dp(48)
      },
    )
  }

  private fun renderInstallSources(sources: List<InstallSourceItem>) {
    installSourcesCard.removeAllViews()

    if (sources.isEmpty()) {
      installSourcesCard.addView(
        helperText(context.getString(R.string.skills_install_sources_empty)).apply {
          setPadding(dp(16), dp(16), dp(16), dp(16))
        },
      )
      return
    }

    sources.forEachIndexed { index, source ->
      installSourcesCard.addView(
        buildInstallSourceRow(source),
      )
      if (index != sources.lastIndex) {
        installSourcesCard.addView(divider())
      }
    }
  }

  private fun buildInstallSourceRow(source: InstallSourceItem): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(52)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    isClickable = true
    isFocusable = true
    setOnClickListener {
      showToast(viewModel.activateInstallSource(source.id))
    }

    addView(
      cardTitleText(source.title, textSizeSp = 16f),
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    addView(
      bodyText().apply {
        text = source.actionLabel
        setTextColor(textSecondary)
      },
    )
    addView(
      bodyText().apply {
        text = "›"
        setTextColor(Color.parseColor("#C7C7CC"))
        textSize = 16f
        setPadding(dp(10), 0, 0, 0)
      },
    )
  }

  private fun renderSuggestedSkills(suggestedSkills: List<SuggestedSkillItem>) {
    val hasSuggestions = suggestedSkills.isNotEmpty()
    suggestedLabel.visibility = if (hasSuggestions) View.VISIBLE else View.GONE
    suggestedCard.visibility = if (hasSuggestions) View.VISIBLE else View.GONE
    suggestedEmptyView.visibility = if (hasSuggestions) View.GONE else View.VISIBLE

    if (!hasSuggestions) {
      suggestedCard.removeAllViews()
      return
    }

    suggestedCard.removeAllViews()
    suggestedSkills.forEachIndexed { index, skill ->
      suggestedCard.addView(buildSuggestedSkillRow(skill))
      if (index != suggestedSkills.lastIndex) {
        suggestedCard.addView(divider())
      }
    }
  }

  private fun buildSuggestedSkillRow(skill: SuggestedSkillItem): View = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = dp(63)
    setPadding(dp(16), dp(14), dp(16), dp(14))

    val textColumn = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    textColumn.addView(cardTitleText(skill.name, textSizeSp = 16f))
    textColumn.addView(
      helperText(skill.description).apply {
        setTextColor(Color.parseColor("#8E8E93"))
        textSize = 12f
      },
      blockParams(topDp = 2),
    )
    addView(
      textColumn,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(12)
      },
    )

    addView(
      TextView(context).apply {
        text = context.getString(R.string.skills_install_action)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        gravity = Gravity.CENTER
        background = roundedRect(fillColor = installButtonFill, radiusDp = 999)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener {
          val message = viewModel.installSuggestedSkill(skill.id)
          showToast(message)
        }
      },
    )
  }

  private fun renderSegmentSelection(selected: SkillsPage) {
    applySegmentButtonStyle(button = manageSegment, selected = selected == SkillsPage.MANAGE)
    applySegmentButtonStyle(button = installSegment, selected = selected == SkillsPage.INSTALL)
    segmentedControl.post {
      animateSegmentIndicator(selected)
    }
  }

  private fun applySegmentButtonStyle(
    button: TextView,
    selected: Boolean,
  ) {
    button.background = null
    button.setTextColor(if (selected) textPrimary else textSecondary)
    button.animate()
      .cancel()
    button.animate()
      .withLayer()
      .scaleX(if (selected) 1f else 0.985f)
      .scaleY(if (selected) 1f else 0.985f)
      .alpha(if (selected) 1f else 0.82f)
      .setDuration(135)
      .setInterpolator(smoothInterpolator)
      .start()
  }

  private fun animateSegmentIndicator(selected: SkillsPage) {
    val targetButton = if (selected == SkillsPage.MANAGE) manageSegment else installSegment
    val targetWidth = targetButton.width
    if (targetWidth <= 0) {
      return
    }
    val targetLeft = targetButton.left
    val indicatorParams = segmentedIndicator.layoutParams as FrameLayout.LayoutParams
    if (indicatorParams.width != targetWidth) {
      indicatorParams.width = targetWidth
      segmentedIndicator.layoutParams = indicatorParams
    }
    val previousLeft = selectedSegmentLeft
    selectedSegmentLeft = targetLeft
    if (previousLeft == null) {
      segmentedIndicator.translationX = targetLeft.toFloat()
      return
    }
    segmentedIndicator.animate()
      .cancel()
    segmentedIndicator.animate()
      .withLayer()
      .translationX(targetLeft.toFloat())
      .setDuration(190)
      .setInterpolator(springInterpolator)
      .start()
  }

  private fun confirmDelete(skill: WorkspaceSkillItem) {
    AlertDialog.Builder(context)
      .setTitle(context.getString(R.string.skills_delete_title, skill.name))
      .setMessage(context.getString(R.string.skills_delete_message))
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton(R.string.skills_action_delete) { _, _ ->
        val message = viewModel.deleteSkill(skill.id)
        showToast(message)
      }
      .show()
  }

  private fun showToast(message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
  }

  private fun animatePageTransition(
    fromPage: SkillsPage,
    toPage: SkillsPage,
  ) {
    val outgoing = if (fromPage == SkillsPage.MANAGE) managePage else installPage
    val incoming = if (toPage == SkillsPage.MANAGE) managePage else installPage

    outgoing.animate().cancel()
    incoming.animate().cancel()

    incoming.visibility = View.VISIBLE
    incoming.alpha = 0f
    incoming.translationY = dp(12).toFloat()
    outgoing.alpha = 1f
    outgoing.translationY = 0f

    incoming.animate()
      .withLayer()
      .alpha(1f)
      .translationY(0f)
      .setDuration(160)
      .setInterpolator(smoothInterpolator)
      .start()

    outgoing.animate()
      .withLayer()
      .alpha(0f)
      .translationY((-dp(4)).toFloat())
      .setDuration(120)
      .setInterpolator(smoothInterpolator)
      .setListener(
        object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            outgoing.visibility = View.GONE
            outgoing.alpha = 1f
            outgoing.translationY = 0f
            outgoing.animate().setListener(null)
          }
        },
      )
      .start()
  }

  private fun animateInstallPageEntrance() {
    val animatedViews = mutableListOf<View>()
    animatedViews += installSearchInput
    animatedViews += installSourcesLabel
    for (index in 0 until installSourcesCard.childCount) {
      val child = installSourcesCard.getChildAt(index)
      if (child.height != dp(1)) {
        animatedViews += child
      }
    }
    if (suggestedCard.visibility == View.VISIBLE) {
      animatedViews += suggestedLabel
      for (index in 0 until suggestedCard.childCount) {
        val child = suggestedCard.getChildAt(index)
        if (child.height != dp(1)) {
          animatedViews += child
        }
      }
    } else if (suggestedEmptyView.visibility == View.VISIBLE) {
      animatedViews += suggestedEmptyView
    }

    animatedViews.forEachIndexed { index, view ->
      view.alpha = 0f
      view.translationY = dp(6).toFloat()
      view.animate()
        .withLayer()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(index * 20L)
        .setDuration(150)
        .setInterpolator(smoothInterpolator)
        .start()
    }
  }

  private fun animateActionRowOpen(actionRow: View) {
    actionRow.alpha = 0f
    actionRow.translationX = dp(8).toFloat()
    actionRow.translationY = (-dp(3)).toFloat()
    actionRow.scaleX = 0.97f
    actionRow.scaleY = 0.97f

    actionRow.animate()
      .withLayer()
      .alpha(1f)
      .translationX(0f)
      .translationY(0f)
      .scaleX(1f)
      .scaleY(1f)
      .setDuration(165)
      .setInterpolator(springInterpolator)
      .start()
  }

  private fun animateActionRowClose(skillId: String) {
    val actionRow = manageCard.findViewWithTag<View>("skill-actions-$skillId") ?: run {
      viewModel.toggleSkillMenu(skillId)
      return
    }
    actionRow.animate()
      .withLayer()
      .alpha(0f)
      .translationX(dp(6).toFloat())
      .translationY((-dp(3)).toFloat())
      .scaleX(0.985f)
      .scaleY(0.975f)
      .setDuration(90)
      .setInterpolator(smoothInterpolator)
      .setListener(
        object : AnimatorListenerAdapter() {
          override fun onAnimationEnd(animation: Animator) {
            actionRow.alpha = 1f
            actionRow.translationX = 0f
            actionRow.translationY = 0f
            actionRow.scaleX = 1f
            actionRow.scaleY = 1f
            actionRow.animate().setListener(null)
            viewModel.toggleSkillMenu(skillId)
          }
        },
      )
      .start()
  }

  private fun titleText(
    value: String,
    textSizeSp: Float = 28f,
  ): TextView = if (textSizeSp >= 24f) {
    context.ocSectionTitleText(value, textSizeSp)
  } else {
    context.ocCardTitleText(value, textSizeSp)
  }

  private fun cardTitleText(
    value: String,
    textSizeSp: Float = 17f,
  ): TextView = context.ocCardTitleText(value, textSizeSp)

  private fun bodyText(): TextView = context.ocBodyText()

  private fun helperText(value: String): TextView = context.ocMetaText(value)

  private fun surfaceCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = roundedRect(fillColor = surface, radiusDp = 16)
  }

  private fun segmentedContainer(): FrameLayout = FrameLayout(context).apply {
    background = roundedRect(fillColor = surfaceMuted, radiusDp = 999)
    clipToPadding = false
  }

  private fun segmentedButton(label: String): TextView = TextView(context).apply {
    text = label
    gravity = Gravity.CENTER
    textSize = 13f
    setTypeface(typeface, Typeface.BOLD)
    includeFontPadding = false
    minHeight = 0
    minimumHeight = 0
    setPadding(dp(10), dp(4), dp(10), dp(4))
    isClickable = true
    isFocusable = true
  }

  private fun overflowButton(active: Boolean): TextView = TextView(context).apply {
    text = "···"
    gravity = Gravity.CENTER
    textSize = 12f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(Color.parseColor("#8E8E93"))
    background = roundedRect(
      fillColor = if (active) surfaceMuted else overflowFill,
      radiusDp = 999,
    )
  }

  private fun inlineActionButton(
    label: String,
    textColor: Int,
  ): TextView = TextView(context).apply {
    text = label
    textSize = 13f
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(textColor)
    gravity = Gravity.CENTER
    isClickable = true
    isFocusable = true
    setPadding(0, dp(4), 0, dp(4))
  }

  private fun divider(): View = View(context).apply {
    setBackgroundColor(border)
  }.also { view ->
    view.layoutParams = LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      dp(1),
    )
  }

  private fun roundedRect(
    fillColor: Int,
    radiusDp: Int,
  ): GradientDrawable = GradientDrawable().apply {
    val drawable = context.ocCardBackground(
      tone = OpenCraySurfaceTone.NEUTRAL,
      radiusDp = radiusDp,
      stroked = false,
    )
    shape = drawable.shape
    cornerRadius = drawable.cornerRadius
    color = drawable.color
    setColor(fillColor)
  }

  private fun floatingMenuBackground(): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(14).toFloat()
    setColor(surface)
    setStroke(dp(1), border)
  }

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}

private class SkillToggleView(
  context: Context,
) : FrameLayout(context) {
  private val trackOn = Color.parseColor("#34C759")
  private val trackOff = Color.parseColor("#D1D1D6")
  private val colorEvaluator = ArgbEvaluator()
  private val smoothInterpolator = PathInterpolator(0.22f, 0f, 0f, 1f)
  private val springInterpolator = OvershootInterpolator(0.7f)
  private val thumb = View(context)
  private val travelDistance = dp(20).toFloat()
  private var checked: Boolean = false
  private var trackColor: Int = trackOff

  init {
    layoutParams = LayoutParams(dp(50), dp(30))
    setPadding(dp(3), dp(3), dp(3), dp(3))
    addView(
      thumb,
      LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL),
    )
    thumb.background = circle(Color.WHITE)
    render(false)
    isClickable = true
    isFocusable = true
  }

  fun render(value: Boolean) {
    checked = value
    trackColor = if (value) trackOn else trackOff
    thumb.animate().cancel()
    thumb.translationX = if (value) travelDistance else 0f
    background = pill(trackColor)
  }

  fun animateTo(value: Boolean) {
    checked = value
    val targetTranslation = if (value) travelDistance else 0f
    val targetColor = if (value) trackOn else trackOff
    ValueAnimator.ofObject(colorEvaluator, trackColor, targetColor).apply {
      duration = 180
      interpolator = smoothInterpolator
      addUpdateListener { animator ->
        trackColor = animator.animatedValue as Int
        background = pill(trackColor)
      }
      start()
    }
    thumb.animate()
      .cancel()
    thumb.animate()
      .translationX(targetTranslation)
      .setDuration(205)
      .setInterpolator(springInterpolator)
      .start()
  }

  private fun pill(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(999).toFloat()
    setColor(fillColor)
  }

  private fun circle(fillColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fillColor)
  }

  private fun dp(value: Int): Int = context.ocDp(value)
}
