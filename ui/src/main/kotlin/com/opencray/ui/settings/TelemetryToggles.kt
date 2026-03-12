package com.opencray.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocSectionCard
import com.opencray.ui.design.ocSectionTitleText
import org.opencray.ui.R

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
  val title: String,
  val subtitle: String,
  val telemetry: TelemetryToggleState,
  val privacyGuard: TelemetryToggleState,
  val defaultsDisclosure: String,
  val localRetentionDisclosure: String,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
    require(defaultsDisclosure.isNotBlank()) { "defaultsDisclosure must not be blank." }
    require(localRetentionDisclosure.isNotBlank()) {
      "localRetentionDisclosure must not be blank."
    }
  }

  companion object {
    fun localized(context: Context): TelemetryTogglesState = TelemetryTogglesState(
      title = context.getString(R.string.telemetry_title),
      subtitle = context.getString(R.string.telemetry_subtitle),
      telemetry = TelemetryToggleState(
        title = context.getString(R.string.telemetry_toggle_title),
        switchLabel = context.getString(R.string.telemetry_switch_label),
        enabledSummary = context.getString(R.string.telemetry_toggle_enabled_summary),
        disabledSummary = context.getString(R.string.telemetry_toggle_disabled_summary),
        disclosureText = context.getString(R.string.telemetry_toggle_disclosure),
        isChecked = false,
        defaultValue = false,
      ),
      privacyGuard = TelemetryToggleState(
        title = context.getString(R.string.privacy_guard_title),
        switchLabel = context.getString(R.string.privacy_guard_switch_label),
        enabledSummary = context.getString(R.string.privacy_guard_enabled_summary),
        disabledSummary = context.getString(R.string.privacy_guard_disabled_summary),
        disclosureText = context.getString(R.string.privacy_guard_disclosure),
        isChecked = true,
        defaultValue = true,
      ),
      defaultsDisclosure = context.getString(R.string.telemetry_defaults_disclosure),
      localRetentionDisclosure = context.getString(R.string.telemetry_local_retention_disclosure),
    )
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

  private val surfaceColor = OpenCrayUiTokens.surface
  private val backgroundColor = OpenCrayUiTokens.shellBackground
  private val textPrimary = OpenCrayUiTokens.textPrimary
  private val accentColor = OpenCrayUiTokens.primary
  private val successColor = OpenCrayUiTokens.success

  private var listener: Listener? = null
  private var state: TelemetryTogglesState = TelemetryTogglesState.localized(context)
  private var isRendering: Boolean = false

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(28))
  }

  private val headerTitleView = titleText(textSizeSp = 28f)
  private val headerSubtitleView = helperText()
  private val telemetryToggleViews = buildToggleCard()
  private val privacyGuardToggleViews = buildToggleCard()
  private val disclosureCard = sectionCard()
  private val disclosureTitleView = titleText(context.getString(R.string.telemetry_disclosure_title), 18f)
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
    contentContainer.addView(telemetryToggleViews.card, blockParams(topDp = 20))
    contentContainer.addView(privacyGuardToggleViews.card, blockParams(topDp = 12))
    contentContainer.addView(disclosureCard, blockParams(topDp = 24))

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
    background = ColorDrawable(Color.TRANSPARENT)
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun setupDisclosureCard() {
    disclosureCard.background = context.ocCardBackground(OpenCraySurfaceTone.SUBTLE)
    disclosureCard.addView(disclosureTitleView)
    disclosureCard.addView(defaultsDisclosureView, blockParams(topDp = 8))
    disclosureCard.addView(localRetentionDisclosureView, blockParams(topDp = 10))
  }

  private fun buildToggleCard(): ToggleCardViews {
    val titleView = titleText(textSizeSp = 20f)
    val stateSummaryView = helperText()
    val switchView = Switch(context).apply {
      textSize = 15f
      setTextColor(textPrimary)
      minHeight = dp(52)
    }
    val disclosureView = bodyText()

    val card = sectionCard().apply {
      addView(
        LinearLayout(context).apply {
          orientation = LinearLayout.HORIZONTAL

          addView(
            titleView,
            LinearLayout.LayoutParams(
              0,
              ViewGroup.LayoutParams.WRAP_CONTENT,
              1f,
            ),
          )
          addView(switchView)
        },
      )
      addView(stateSummaryView, blockParams(topDp = 8))
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
      append(context.getString(R.string.telemetry_state_current_label))
      append(": ")
      append(onOffLabel(toggleState.isChecked))
      append(" · ")
      append(if (toggleState.isChecked) toggleState.enabledSummary else toggleState.disabledSummary)
    }
    views.switchView.text = toggleState.switchLabel
    views.switchView.contentDescription = toggleState.switchLabel
    views.switchView.isChecked = toggleState.isChecked
    views.disclosureView.text = toggleState.disclosureText
    views.card.background = context.ocCardBackground(
      tone = if (!toggleState.isChecked) {
        OpenCraySurfaceTone.NEUTRAL
      } else if (enabledStrokeColor == accentColor) {
        OpenCraySurfaceTone.INFO
      } else {
        OpenCraySurfaceTone.SUCCESS
      },
    )
  }

  private fun renderDisclosures() {
    defaultsDisclosureView.text = state.defaultsDisclosure
    localRetentionDisclosureView.text = state.localRetentionDisclosure
  }

  private fun onOffLabel(value: Boolean): String = if (value) {
    context.getString(R.string.telemetry_state_on)
  } else {
    context.getString(R.string.telemetry_state_off)
  }

  private fun sectionCard(): LinearLayout = context.ocSectionCard()

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = context.ocSectionTitleText(value, textSizeSp)

  private fun titleText(textSizeSp: Float): TextView = context.ocSectionTitleText(textSizeSp = textSizeSp)

  private fun bodyText(value: String = ""): TextView = context.ocBodyText(value)

  private fun helperText(value: String = ""): TextView = context.ocMetaText(value)

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}

// Issue: Persistence and policy enforcement intentionally stay outside this self-contained UI slice.
