package com.opencray.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

private const val DEFAULT_TELEMETRY_TITLE = "Telemetry and privacy"
private const val DEFAULT_TELEMETRY_SUBTITLE =
  "Explicit defaults stay visible here so later settings wiring can reuse stable labels and disclosures without guessing."
private const val DEFAULT_DEFAULTS_DISCLOSURE =
  "Defaults: Enable telemetry = Off. Enable privacy guard = On."
private const val DEFAULT_LOCAL_RETENTION_DISCLOSURE =
  "Even with telemetry off, OpenCray still keeps local settings, workspace access state, consent choices, and recent audit history on this device until a later clear-data flow removes them."
private const val TELEMETRY_SWITCH_LABEL = "Enable telemetry"
private const val PRIVACY_GUARD_SWITCH_LABEL = "Enable privacy guard"

data class TelemetryToggleState(
  val title: String,
  val switchLabel: String,
  val enabledSummary: String,
  val disabledSummary: String,
  val disclosureText: String,
  val isChecked: Boolean,
  val defaultValue: Boolean,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(switchLabel.isNotBlank()) { "switchLabel must not be blank." }
    require(enabledSummary.isNotBlank()) { "enabledSummary must not be blank." }
    require(disabledSummary.isNotBlank()) { "disabledSummary must not be blank." }
    require(disclosureText.isNotBlank()) { "disclosureText must not be blank." }
  }
}

data class TelemetryTogglesState(
  val title: String = DEFAULT_TELEMETRY_TITLE,
  val subtitle: String = DEFAULT_TELEMETRY_SUBTITLE,
  val telemetry: TelemetryToggleState = TelemetryToggleState(
    title = "Telemetry collection",
    switchLabel = TELEMETRY_SWITCH_LABEL,
    enabledSummary = "Anonymous product telemetry is allowed.",
    disabledSummary = "No telemetry leaves the device.",
    disclosureText =
      "Default: Off. Turning telemetry off blocks outbound telemetry, but local settings, approvals, and recent audit entries remain stored on this device.",
    isChecked = false,
    defaultValue = false,
  ),
  val privacyGuard: TelemetryToggleState = TelemetryToggleState(
    title = "Privacy guard",
    switchLabel = PRIVACY_GUARD_SWITCH_LABEL,
    enabledSummary = "Local redaction stays enabled for eligible analytics and audit details.",
    disabledSummary = "Eligible analytics and audit details can use full local detail.",
    disclosureText =
      "Default: On. Privacy guard changes what later telemetry may include, while local retention still keeps the current setting and recent audit history on-device.",
    isChecked = true,
    defaultValue = true,
  ),
  val defaultsDisclosure: String = DEFAULT_DEFAULTS_DISCLOSURE,
  val localRetentionDisclosure: String = DEFAULT_LOCAL_RETENTION_DISCLOSURE,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
    require(defaultsDisclosure.isNotBlank()) { "defaultsDisclosure must not be blank." }
    require(localRetentionDisclosure.isNotBlank()) {
      "localRetentionDisclosure must not be blank."
    }
  }
}

class TelemetryToggles @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
  interface Listener {
    fun onStateChanged(state: TelemetryTogglesState)
  }

  private data class ToggleCardViews(
    val card: LinearLayout,
    val titleView: TextView,
    val stateSummaryView: TextView,
    val switchView: Switch,
    val disclosureView: TextView,
  )

  private val surfaceColor = Color.WHITE
  private val backgroundColor = Color.parseColor("#F4F7FB")
  private val borderColor = Color.parseColor("#D7E1ED")
  private val textPrimary = Color.parseColor("#152538")
  private val textSecondary = Color.parseColor("#5D6B7B")
  private val accentColor = Color.parseColor("#2353B6")
  private val successColor = Color.parseColor("#1F7A44")

  private var listener: Listener? = null
  private var state: TelemetryTogglesState = TelemetryTogglesState()
  private var isRendering: Boolean = false

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
  }

  private val headerTitleView = titleText(textSizeSp = 20f)
  private val headerSubtitleView = helperText()
  private val telemetryToggleViews = buildToggleCard()
  private val privacyGuardToggleViews = buildToggleCard()
  private val disclosureCard = sectionCard()
  private val disclosureTitleView = titleText("Defaults and local retention", 18f)
  private val defaultsDisclosureView = helperText()
  private val localRetentionDisclosureView = bodyText()

  init {
    isFillViewport = true
    setBackgroundColor(backgroundColor)

    addView(
      contentContainer,
      LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    contentContainer.addView(buildHeaderCard())
    contentContainer.addView(telemetryToggleViews.card, blockParams(topDp = 16))
    contentContainer.addView(privacyGuardToggleViews.card, blockParams(topDp = 16))
    contentContainer.addView(disclosureCard, blockParams(topDp = 16))

    setupDisclosureCard()
    bindToggleListeners()
    submitState(state)
  }

  fun setListener(listener: Listener?) {
    this.listener = listener
  }

  fun submitState(newState: TelemetryTogglesState) {
    state = newState
    isRendering = true
    renderHeader()
    renderToggleCard(telemetryToggleViews, state.telemetry, enabledStrokeColor = accentColor)
    renderToggleCard(privacyGuardToggleViews, state.privacyGuard, enabledStrokeColor = successColor)
    renderDisclosures()
    isRendering = false
  }

  fun snapshotState(): TelemetryTogglesState = state

  private fun buildHeaderCard(): LinearLayout = sectionCard().apply {
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun setupDisclosureCard() {
    disclosureCard.addView(disclosureTitleView)
    disclosureCard.addView(defaultsDisclosureView, blockParams(topDp = 8))
    disclosureCard.addView(localRetentionDisclosureView, blockParams(topDp = 10))
  }

  private fun buildToggleCard(): ToggleCardViews {
    val titleView = titleText(textSizeSp = 18f)
    val stateSummaryView = helperText()
    val switchView = Switch(context).apply {
      textSize = 15f
      setTextColor(textPrimary)
      minHeight = dp(48)
    }
    val disclosureView = bodyText()

    val card = sectionCard().apply {
      addView(titleView)
      addView(stateSummaryView, blockParams(topDp = 6))
      addView(switchView, blockParams(topDp = 12))
      addView(disclosureView, blockParams(topDp = 10))
    }

    return ToggleCardViews(
      card = card,
      titleView = titleView,
      stateSummaryView = stateSummaryView,
      switchView = switchView,
      disclosureView = disclosureView,
    )
  }

  private fun bindToggleListeners() {
    telemetryToggleViews.switchView.setOnCheckedChangeListener { _, isChecked ->
      if (isRendering || state.telemetry.isChecked == isChecked) {
        return@setOnCheckedChangeListener
      }

      val updatedState = state.copy(
        telemetry = state.telemetry.copy(isChecked = isChecked),
      )
      submitState(updatedState)
      listener?.onStateChanged(updatedState)
    }

    privacyGuardToggleViews.switchView.setOnCheckedChangeListener { _, isChecked ->
      if (isRendering || state.privacyGuard.isChecked == isChecked) {
        return@setOnCheckedChangeListener
      }

      val updatedState = state.copy(
        privacyGuard = state.privacyGuard.copy(isChecked = isChecked),
      )
      submitState(updatedState)
      listener?.onStateChanged(updatedState)
    }
  }

  private fun renderHeader() {
    headerTitleView.text = state.title
    headerSubtitleView.text = state.subtitle
  }

  private fun renderToggleCard(
    views: ToggleCardViews,
    toggleState: TelemetryToggleState,
    enabledStrokeColor: Int,
  ) {
    views.titleView.text = toggleState.title
    views.stateSummaryView.text = buildString {
      append("Current: ")
      append(onOffLabel(toggleState.isChecked))
      append(" • Default: ")
      append(onOffLabel(toggleState.defaultValue))
      append(" • ")
      append(if (toggleState.isChecked) toggleState.enabledSummary else toggleState.disabledSummary)
    }
    views.switchView.text = toggleState.switchLabel
    views.switchView.contentDescription = toggleState.switchLabel
    views.switchView.isChecked = toggleState.isChecked
    views.disclosureView.text = toggleState.disclosureText
    views.card.background = sectionBackground(
      if (toggleState.isChecked) enabledStrokeColor else borderColor,
    )
  }

  private fun renderDisclosures() {
    defaultsDisclosureView.text = state.defaultsDisclosure
    localRetentionDisclosureView.text = state.localRetentionDisclosure
  }

  private fun onOffLabel(value: Boolean): String = if (value) "On" else "Off"

  private fun sectionCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = sectionBackground(borderColor)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = TextView(context).apply {
    text = value
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun titleText(textSizeSp: Float): TextView = TextView(context).apply {
    textSize = textSizeSp
    setTextColor(textPrimary)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun bodyText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 14f
    setTextColor(textPrimary)
    setLineSpacing(0f, 1.12f)
  }

  private fun helperText(value: String = ""): TextView = TextView(context).apply {
    text = value
    textSize = 13f
    setTextColor(textSecondary)
    setLineSpacing(0f, 1.1f)
  }

  private fun sectionBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(surfaceColor)
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

  private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

// Issue: Persistence and policy enforcement intentionally stay outside this self-contained UI slice.
