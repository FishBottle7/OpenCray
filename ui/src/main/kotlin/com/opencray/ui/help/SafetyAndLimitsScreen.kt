package com.opencray.ui.help

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

private const val DEFAULT_SAFETY_AND_LIMITS_TITLE = "Safety and limits"
private const val DEFAULT_SAFETY_AND_LIMITS_SUBTITLE =
  "OpenCray keeps release-critical warnings visible here so later hosts can reuse one deterministic help surface without guessing."
private const val DEFAULT_MODE_RISKS_HEADING = "Mode risks"
private const val DEFAULT_MODE_RISKS_INTRO =
  "Safe, Auto, and Developer mode disclosures stay explicit here so a later host can point to one stable set of risk labels."
private const val DEFAULT_ROLLBACK_LIMITS_HEADING = "Rollback limits"
private const val DEFAULT_ROLLBACK_LIMITS_INTRO =
  "Rollback wording stays intentionally narrow so OpenCray never promises recovery beyond verified local checkpoints."
private const val DEFAULT_TELEMETRY_PRIVACY_HEADING = "Telemetry and privacy"
private const val DEFAULT_TELEMETRY_PRIVACY_INTRO =
  "This screen summarizes the same defaults a later host can expose with TelemetryToggles in settings."
private const val DEFAULT_TELEMETRY_PRIVACY_FOOTER =
  "Defaults: Enable telemetry = Off. Enable privacy guard = On. A later host can embed TelemetryToggles here without changing these labels."
private const val DEFAULT_V1_SCOPE_HEADING = "V1 scope"
private const val DEFAULT_V1_SCOPE_INTRO =
  "Release-facing scope stays explicit here so future ideas do not get mistaken for shipped OpenCray runtime support."

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
  val title: String = DEFAULT_SAFETY_AND_LIMITS_TITLE,
  val subtitle: String = DEFAULT_SAFETY_AND_LIMITS_SUBTITLE,
  val modeRisksHeading: String = DEFAULT_MODE_RISKS_HEADING,
  val modeRisksIntro: String = DEFAULT_MODE_RISKS_INTRO,
  val modeRiskCards: List<DisclosureCardState> = defaultModeRiskCards(),
  val rollbackLimitsHeading: String = DEFAULT_ROLLBACK_LIMITS_HEADING,
  val rollbackLimitsIntro: String = DEFAULT_ROLLBACK_LIMITS_INTRO,
  val rollbackLimitCards: List<DisclosureCardState> = defaultRollbackLimitCards(),
  val telemetryPrivacyHeading: String = DEFAULT_TELEMETRY_PRIVACY_HEADING,
  val telemetryPrivacyIntro: String = DEFAULT_TELEMETRY_PRIVACY_INTRO,
  val telemetryPrivacyCards: List<DisclosureCardState> = defaultTelemetryPrivacyCards(),
  val telemetryPrivacyFooter: String = DEFAULT_TELEMETRY_PRIVACY_FOOTER,
  val v1ScopeHeading: String = DEFAULT_V1_SCOPE_HEADING,
  val v1ScopeIntro: String = DEFAULT_V1_SCOPE_INTRO,
  val v1ScopeCards: List<DisclosureCardState> = defaultV1ScopeCards(),
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

  private val surfaceColor = Color.WHITE
  private val backgroundColor = Color.parseColor("#F4F7FB")
  private val borderColor = Color.parseColor("#D7E1ED")
  private val textPrimary = Color.parseColor("#152538")
  private val textSecondary = Color.parseColor("#5D6B7B")
  private val accentColor = Color.parseColor("#2353B6")
  private val successColor = Color.parseColor("#1F7A44")
  private val warningColor = Color.parseColor("#9A6700")
  private val dangerColor = Color.parseColor("#8E1C1C")

  private var state: SafetyAndLimitsScreenState = SafetyAndLimitsScreenState()

  private val contentContainer = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(16), dp(16), dp(16), dp(24))
  }

  private val headerTitleView = titleText(textSizeSp = 20f)
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
    contentContainer.addView(modeRisksSection.card, blockParams(topDp = 16))
    contentContainer.addView(rollbackLimitsSection.card, blockParams(topDp = 16))
    contentContainer.addView(telemetryPrivacySection.card, blockParams(topDp = 16))
    contentContainer.addView(v1ScopeSection.card, blockParams(topDp = 16))

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
    addView(headerTitleView)
    addView(headerSubtitleView, blockParams(topDp = 6))
  }

  private fun buildDisclosureSection(): DisclosureSectionViews {
    val titleView = titleText(textSizeSp = 18f)
    val introView = helperText()
    val itemsContainer = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    val footerView = helperText().apply {
      visibility = View.GONE
    }

    val card = sectionCard().apply {
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

  private fun labelText(
    value: String,
    color: Int,
  ): TextView = TextView(context).apply {
    text = value
    textSize = 12f
    setTextColor(color)
    setTypeface(typeface, Typeface.BOLD)
  }

  private fun sectionBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(18).toFloat()
    setColor(surfaceColor)
    setStroke(dp(1), strokeColor)
  }

  private fun detailBackground(strokeColor: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(16).toFloat()
    setColor(Color.parseColor("#F8FAFC"))
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

private fun defaultModeRiskCards(): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = "Safe",
    title = "Review-first default",
    body =
      "Safe mode keeps approval prompts in front of sensitive actions so OpenCray favors review over speed when the risk surface grows.",
    note = "Use this when you want the lowest-risk default path.",
    tone = DisclosureTone.SUCCESS,
  ),
  DisclosureCardState(
    label = "Auto",
    title = "Fewer interruptions means more risk",
    body =
      "Auto mode reduces interruptions and can continue through more approved flows automatically, so mistakes can travel farther before someone notices.",
    note = "Auto mode can reduce prompts, but hard denials still stop protected or path-blocked actions.",
    tone = DisclosureTone.WARNING,
  ),
  DisclosureCardState(
    label = "Developer",
    title = "Highest-risk local control surface",
    body = "Developer mode can expose high-risk operations and reduce prompts for debugging or power use.",
    note =
      "Developer mode does not override hard denials. Protected-file, path, and other hard policy denials still stop the action.",
    tone = DisclosureTone.DANGER,
  ),
)

private fun defaultRollbackLimitCards(): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = "Local-only",
    title = "Guaranteed only for local filesystem checkpoints",
    body = "Rollback is guaranteed only for local filesystem checkpoints created by OpenCray on this device.",
    note = "Use rollback as a local safety net, not as a universal undo system.",
    tone = DisclosureTone.WARNING,
  ),
  DisclosureCardState(
    label = "Not covered",
    title = "Remote and external effects are not guaranteed",
    body =
      "Rollback is local-only, not guaranteed for remote/external side effects such as shell commands, MCP actions, network requests, cloud changes, or any other external system change.",
    note = "If something leaves the device or touches another system, rollback may not undo it.",
    tone = DisclosureTone.DANGER,
  ),
)

private fun defaultTelemetryPrivacyCards(): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = "Enable telemetry",
    title = "Default: Off",
    body =
      "Turning telemetry on allows anonymous product telemetry to leave the device. Leaving it off blocks outbound telemetry.",
    note =
      "This setting persists on this device and can be changed later in TelemetryToggles or Settings > Telemetry and privacy.",
    tone = DisclosureTone.INFO,
  ),
  DisclosureCardState(
    label = "Enable privacy guard",
    title = "Default: On",
    body =
      "Privacy guard keeps eligible analytics and audit details locally redacted. Turning it off allows full local detail for eligible analytics and audit data.",
    note =
      "This setting persists on this device and can be changed later in TelemetryToggles or Settings > Telemetry and privacy.",
    tone = DisclosureTone.SUCCESS,
  ),
  DisclosureCardState(
    label = "Local retention",
    title = "Core records still stay local",
    body =
      "Even with telemetry off, OpenCray still keeps local settings, workspace access state, consent choices, and recent audit history required for core app function on this device until a later clear-data flow removes them.",
    note = "Telemetry off does not mean zero local retention.",
    tone = DisclosureTone.WARNING,
  ),
)

private fun defaultV1ScopeCards(): List<DisclosureCardState> = listOf(
  DisclosureCardState(
    label = "Runtime",
    title = "Real Termux execution is out of scope",
    body = "V1 does not ship real Termux execution.",
    note = "Any V1 runtime path must stay independent from real Termux execution.",
    tone = DisclosureTone.DANGER,
  ),
  DisclosureCardState(
    label = "Also out of scope",
    title = "Not shipping in V1",
    body =
      "Multi-agent parallel execution, iOS client support, cloud collaboration sync, and a public marketplace review system are out of scope for V1.",
    note = "Treat these as future areas, not near-shipping V1 features.",
    tone = DisclosureTone.WARNING,
  ),
)
