package com.opencray.ui.help

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.opencray.ui.design.OpenCraySurfaceTone
import com.opencray.ui.design.OpenCrayUiTokens
import com.opencray.ui.design.ocBodyText
import com.opencray.ui.design.ocCardBackground
import com.opencray.ui.design.ocDp
import com.opencray.ui.design.ocLabelText
import com.opencray.ui.design.ocLinearBlockParams
import com.opencray.ui.design.ocMetaText
import com.opencray.ui.design.ocSectionCard
import com.opencray.ui.design.ocSectionTitleText
import org.opencray.ui.R

enum class DisclosureTone {
  INFO,
  SUCCESS,
  WARNING,
  DANGER,
}

data class DisclosureCardState(
  val label: String,
  val title: String,
  val body: String,
  val note: String = "",
  val tone: DisclosureTone = DisclosureTone.INFO,
) {
  init {
    require(label.isNotBlank()) { "label must not be blank." }
    require(title.isNotBlank()) { "title must not be blank." }
    require(body.isNotBlank()) { "body must not be blank." }
  }
}

data class SafetyAndLimitsScreenState(
  val title: String,
  val subtitle: String,
  val modeRisksHeading: String,
  val modeRisksIntro: String,
  val modeRiskCards: List<DisclosureCardState>,
  val rollbackLimitsHeading: String,
  val rollbackLimitsIntro: String,
  val rollbackLimitCards: List<DisclosureCardState>,
  val telemetryPrivacyHeading: String,
  val telemetryPrivacyIntro: String,
  val telemetryPrivacyCards: List<DisclosureCardState>,
  val telemetryPrivacyFooter: String,
  val v1ScopeHeading: String,
  val v1ScopeIntro: String,
  val v1ScopeCards: List<DisclosureCardState>,
) {
  init {
    require(title.isNotBlank()) { "title must not be blank." }
    require(subtitle.isNotBlank()) { "subtitle must not be blank." }
    require(modeRisksHeading.isNotBlank()) { "modeRisksHeading must not be blank." }
    require(modeRisksIntro.isNotBlank()) { "modeRisksIntro must not be blank." }
    require(rollbackLimitsHeading.isNotBlank()) { "rollbackLimitsHeading must not be blank." }
    require(rollbackLimitsIntro.isNotBlank()) { "rollbackLimitsIntro must not be blank." }
    require(telemetryPrivacyHeading.isNotBlank()) { "telemetryPrivacyHeading must not be blank." }
    require(telemetryPrivacyIntro.isNotBlank()) { "telemetryPrivacyIntro must not be blank." }
    require(telemetryPrivacyFooter.isNotBlank()) { "telemetryPrivacyFooter must not be blank." }
    require(v1ScopeHeading.isNotBlank()) { "v1ScopeHeading must not be blank." }
    require(v1ScopeIntro.isNotBlank()) { "v1ScopeIntro must not be blank." }
    require(modeRiskCards.isNotEmpty()) { "modeRiskCards must not be empty." }
    require(rollbackLimitCards.isNotEmpty()) { "rollbackLimitCards must not be empty." }
    require(telemetryPrivacyCards.isNotEmpty()) { "telemetryPrivacyCards must not be empty." }
    require(v1ScopeCards.isNotEmpty()) { "v1ScopeCards must not be empty." }
  }

  companion object {
    fun localized(context: Context): SafetyAndLimitsScreenState = SafetyAndLimitsScreenState(
      title = context.getString(R.string.safety_limits_title),
      subtitle = context.getString(R.string.safety_limits_subtitle),
      modeRisksHeading = context.getString(R.string.mode_risks_heading),
      modeRisksIntro = context.getString(R.string.mode_risks_intro),
      modeRiskCards = defaultModeRiskCards(context),
      rollbackLimitsHeading = context.getString(R.string.rollback_limits_heading),
      rollbackLimitsIntro = context.getString(R.string.rollback_limits_intro),
      rollbackLimitCards = defaultRollbackLimitCards(context),
      telemetryPrivacyHeading = context.getString(R.string.telemetry_privacy_heading),
      telemetryPrivacyIntro = context.getString(R.string.telemetry_privacy_intro),
      telemetryPrivacyCards = defaultTelemetryPrivacyCards(context),
      telemetryPrivacyFooter = context.getString(R.string.telemetry_privacy_footer),
      v1ScopeHeading = context.getString(R.string.v1_scope_heading),
      v1ScopeIntro = context.getString(R.string.v1_scope_intro),
      v1ScopeCards = defaultV1ScopeCards(context),
    )
  }
}

class SafetyAndLimitsScreen @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
  private data class DisclosureSectionViews(
    val card: LinearLayout,
    val titleView: TextView,
    val introView: TextView,
    val itemsContainer: LinearLayout,
    val footerView: TextView,
  )

  private val surfaceColor = OpenCrayUiTokens.surface
  private val backgroundColor = OpenCrayUiTokens.shellBackground
  private val accentColor = OpenCrayUiTokens.primary
  private val successColor = OpenCrayUiTokens.success
  private val warningColor = OpenCrayUiTokens.warning
  private val dangerColor = OpenCrayUiTokens.danger

  private var state: SafetyAndLimitsScreenState = SafetyAndLimitsScreenState.localized(context)

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(20), dp(12), dp(20), dp(28))
  }

  private val headerTitleView = titleText(textSizeSp = 28f)
  private val headerSubtitleView = helperText()
  private val modeRisksSection = buildDisclosureSection()
  private val rollbackLimitsSection = buildDisclosureSection()
  private val telemetryPrivacySection = buildDisclosureSection()
  private val v1ScopeSection = buildDisclosureSection()

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
    contentContainer.addView(modeRisksSection.card, blockParams(topDp = 20))
    contentContainer.addView(rollbackLimitsSection.card, blockParams(topDp = 12))
    contentContainer.addView(telemetryPrivacySection.card, blockParams(topDp = 12))
    contentContainer.addView(v1ScopeSection.card, blockParams(topDp = 12))

    submitState(state)
  }

  fun submitState(newState: SafetyAndLimitsScreenState) {
    state = newState.copy(
      modeRiskCards = newState.modeRiskCards.toList(),
      rollbackLimitCards = newState.rollbackLimitCards.toList(),
      telemetryPrivacyCards = newState.telemetryPrivacyCards.toList(),
      v1ScopeCards = newState.v1ScopeCards.toList(),
    )

    renderHeader()
    renderSection(
      views = modeRisksSection,
      heading = state.modeRisksHeading,
      intro = state.modeRisksIntro,
      cards = state.modeRiskCards,
    )
    renderSection(
      views = rollbackLimitsSection,
      heading = state.rollbackLimitsHeading,
      intro = state.rollbackLimitsIntro,
      cards = state.rollbackLimitCards,
    )
    renderSection(
      views = telemetryPrivacySection,
      heading = state.telemetryPrivacyHeading,
      intro = state.telemetryPrivacyIntro,
      cards = state.telemetryPrivacyCards,
      footer = state.telemetryPrivacyFooter,
    )
    renderSection(
      views = v1ScopeSection,
      heading = state.v1ScopeHeading,
      intro = state.v1ScopeIntro,
      cards = state.v1ScopeCards,
    )
  }

  fun snapshotState(): SafetyAndLimitsScreenState = state.copy(
    modeRiskCards = state.modeRiskCards.toList(),
    rollbackLimitCards = state.rollbackLimitCards.toList(),
    telemetryPrivacyCards = state.telemetryPrivacyCards.toList(),
    v1ScopeCards = state.v1ScopeCards.toList(),
  )

  private fun buildHeaderCard(): LinearLayout = sectionCard().apply {
    background = ColorDrawable(Color.TRANSPARENT)
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun buildDisclosureSection(): DisclosureSectionViews {
    val titleView = titleText(textSizeSp = 20f)
    val introView = helperText()
    val itemsContainer = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    val footerView = helperText().apply {
      visibility = View.GONE
    }

    val card = sectionCard().apply {
      background = context.ocCardBackground(OpenCraySurfaceTone.NEUTRAL)
      addView(titleView)
      addView(introView, blockParams(topDp = 6))
      addView(itemsContainer, blockParams(topDp = 12))
      addView(footerView, blockParams(topDp = 12))
    }

    return DisclosureSectionViews(
      card = card,
      titleView = titleView,
      introView = introView,
      itemsContainer = itemsContainer,
      footerView = footerView,
    )
  }

  private fun renderHeader() {
    headerTitleView.text = state.title
    headerSubtitleView.text = state.subtitle
  }

  private fun renderSection(
    views: DisclosureSectionViews,
    heading: String,
    intro: String,
    cards: List<DisclosureCardState>,
    footer: String = "",
  ) {
    views.titleView.text = heading
    views.introView.text = intro
    views.itemsContainer.removeAllViews()

    cards.forEachIndexed { index, cardState ->
      views.itemsContainer.addView(
        disclosureCard(cardState),
        blockParams(topDp = if (index == 0) 0 else 12),
      )
    }

    views.footerView.text = footer
    views.footerView.visibility = if (footer.isBlank()) View.GONE else View.VISIBLE
  }

  private fun disclosureCard(cardState: DisclosureCardState): LinearLayout {
    val accent = accentColor(cardState.tone)

    return LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      background = detailBackground(accent)
      setPadding(dp(14), dp(14), dp(14), dp(14))

      addView(labelText(cardState.label, accent))
      addView(titleText(cardState.title, 16f), blockParams(topDp = 8))
      addView(bodyText(cardState.body), blockParams(topDp = 6))

      if (cardState.note.isNotBlank()) {
        addView(helperText(cardState.note), blockParams(topDp = 10))
      }
    }
  }

  private fun accentColor(tone: DisclosureTone): Int = when (tone) {
    DisclosureTone.INFO -> accentColor
    DisclosureTone.SUCCESS -> successColor
    DisclosureTone.WARNING -> warningColor
    DisclosureTone.DANGER -> dangerColor
  }

  private fun sectionCard(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    background = context.ocCardBackground(OpenCraySurfaceTone.NEUTRAL)
    setPadding(dp(16), dp(16), dp(16), dp(16))
  }

  private fun titleText(
    value: String,
    textSizeSp: Float,
  ): TextView = context.ocSectionTitleText(value, textSizeSp)

  private fun titleText(textSizeSp: Float): TextView = context.ocSectionTitleText(textSizeSp = textSizeSp)

  private fun bodyText(value: String = ""): TextView = context.ocBodyText(value)

  private fun helperText(value: String = ""): TextView = context.ocMetaText(value)

  private fun labelText(
    value: String,
    color: Int,
  ): TextView = context.ocLabelText(value, color)

  private fun detailBackground(accentColor: Int) = context.ocCardBackground(
    tone = when (accentColor) {
      successColor -> OpenCraySurfaceTone.SUCCESS
      warningColor -> OpenCraySurfaceTone.WARNING
      dangerColor -> OpenCraySurfaceTone.DANGER
      else -> OpenCraySurfaceTone.INFO
    },
    radiusDp = OpenCrayUiTokens.radiusCard,
  )

  private fun blockParams(
    topDp: Int = 0,
    bottomDp: Int = 0,
  ): LinearLayout.LayoutParams = context.ocLinearBlockParams(topDp = topDp, bottomDp = bottomDp)

  private fun dp(value: Int): Int = context.ocDp(value)
}

private fun defaultModeRiskCards(context: Context): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = context.getString(R.string.mode_risk_safe_label),
    title = context.getString(R.string.mode_risk_safe_title),
    body = context.getString(R.string.mode_risk_safe_body),
    note = context.getString(R.string.mode_risk_safe_note),
    tone = DisclosureTone.SUCCESS,
  ),
  DisclosureCardState(
    label = context.getString(R.string.mode_risk_auto_label),
    title = context.getString(R.string.mode_risk_auto_title),
    body = context.getString(R.string.mode_risk_auto_body),
    note = context.getString(R.string.mode_risk_auto_note),
    tone = DisclosureTone.WARNING,
  ),
  DisclosureCardState(
    label = context.getString(R.string.mode_risk_developer_label),
    title = context.getString(R.string.mode_risk_developer_title),
    body = context.getString(R.string.mode_risk_developer_body),
    note = context.getString(R.string.mode_risk_developer_note),
    tone = DisclosureTone.DANGER,
  ),
)

private fun defaultRollbackLimitCards(context: Context): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = context.getString(R.string.rollback_local_only_label),
    title = context.getString(R.string.rollback_local_only_title),
    body = context.getString(R.string.rollback_local_only_body),
    note = context.getString(R.string.rollback_local_only_note),
    tone = DisclosureTone.WARNING,
  ),
  DisclosureCardState(
    label = context.getString(R.string.rollback_not_covered_label),
    title = context.getString(R.string.rollback_not_covered_title),
    body = context.getString(R.string.rollback_not_covered_body),
    note = context.getString(R.string.rollback_not_covered_note),
    tone = DisclosureTone.DANGER,
  ),
)

private fun defaultTelemetryPrivacyCards(context: Context): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = context.getString(R.string.privacy_enable_telemetry_label),
    title = context.getString(R.string.privacy_enable_telemetry_title),
    body = context.getString(R.string.privacy_enable_telemetry_body),
    note = context.getString(R.string.privacy_enable_telemetry_note),
    tone = DisclosureTone.INFO,
  ),
  DisclosureCardState(
    label = context.getString(R.string.privacy_guard_label),
    title = context.getString(R.string.privacy_guard_title_card),
    body = context.getString(R.string.privacy_guard_body),
    note = context.getString(R.string.privacy_guard_note_card),
    tone = DisclosureTone.SUCCESS,
  ),
  DisclosureCardState(
    label = context.getString(R.string.telemetry_disclosure_title),
    title = context.getString(R.string.telemetry_disclosure_title),
    body = context.getString(R.string.telemetry_local_retention_disclosure),
    note = context.getString(R.string.telemetry_defaults_disclosure),
    tone = DisclosureTone.WARNING,
  ),
)

private fun defaultV1ScopeCards(context: Context): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = context.getString(R.string.v1_scope_termux_label),
    title = context.getString(R.string.v1_scope_termux_title),
    body = context.getString(R.string.v1_scope_termux_body),
    note = context.getString(R.string.v1_scope_termux_note),
    tone = DisclosureTone.DANGER,
  ),
  DisclosureCardState(
    label = context.getString(R.string.v1_scope_future_label),
    title = context.getString(R.string.v1_scope_future_title),
    body = context.getString(R.string.v1_scope_future_body),
    note = context.getString(R.string.v1_scope_future_note),
    tone = DisclosureTone.WARNING,
  ),
)
