package com.opencray.app

import com.opencray.ui.help.DisclosureCardState
import com.opencray.ui.help.SafetyAndLimitsScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperModeDisclosureRequired {
  @Test
  fun releaseHelpStateFailsWhenRequiredDeveloperDisclosureIsMissing() {
    val baselineState = baselineState()
    val missingDisclosureState = baselineState.copy(
      modeRiskCards = baselineState.modeRiskCards.map { card ->
        if (card.label == DEVELOPER_LABEL) {
          card.copy(
            body = "Developer mode supports local debugging and power-user workflows.",
            note = "Additional warnings appear elsewhere.",
          )
        } else {
          card
        }
      },
    )

    val failure = assertThrows(AssertionError::class.java) {
      requireDeveloperModeDisclosure(missingDisclosureState)
    }

    assertTrue(failure.message.orEmpty().contains("Release must fail"))
    assertTrue(failure.message.orEmpty().contains(REQUIRED_HIGH_RISK_DISCLOSURE))
  }

  @Test
  fun defaultReleaseHelpStateIncludesRequiredDeveloperDisclosure() {
    val developerCard = requireDeveloperModeDisclosure(baselineState())

    assertEquals(DEVELOPER_LABEL, developerCard.label)
  }

  private fun requireDeveloperModeDisclosure(
    state: SafetyAndLimitsScreenState,
  ): DisclosureCardState {
    val developerCard = state.modeRiskCards.firstOrNull { card ->
      card.label.equals(DEVELOPER_LABEL, ignoreCase = true) ||
        card.title.contains(DEVELOPER_LABEL, ignoreCase = true)
    } ?: throw AssertionError(
      "Release must fail: missing Developer mode disclosure card in SafetyAndLimitsScreenState.modeRiskCards.",
    )

    val developerDisclosure = listOf(
      developerCard.title,
      developerCard.body,
      developerCard.note,
    ).joinToString(separator = " ")

    requireDisclosurePhrase(developerDisclosure, REQUIRED_HIGH_RISK_DISCLOSURE)
    requireDisclosurePhrase(developerDisclosure, REQUIRED_HARD_DENIAL_DISCLOSURE)
    requireDisclosurePhrase(developerDisclosure, REQUIRED_HARD_POLICY_DETAIL)

    return developerCard
  }

  private fun baselineState(): SafetyAndLimitsScreenState = SafetyAndLimitsScreenState(
    title = "Safety and limits",
    subtitle = "OpenCray keeps release-critical warnings visible here so later hosts can reuse one deterministic help surface without guessing.",
    modeRisksHeading = "Mode risks",
    modeRisksIntro = "Safe, Auto, and Developer mode disclosures stay explicit here so a later host can point to one stable set of risk labels.",
    modeRiskCards = listOf(
      DisclosureCardState(
        label = "Safe",
        title = "Review-first default",
        body = "Safe mode keeps approval prompts in front of sensitive actions so OpenCray favors review over speed when the risk surface grows.",
        note = "Use this when you want the lowest-risk default path.",
      ),
      DisclosureCardState(
        label = DEVELOPER_LABEL,
        title = "Highest-risk local control surface",
        body = REQUIRED_HIGH_RISK_DISCLOSURE,
        note = "${REQUIRED_HARD_DENIAL_DISCLOSURE}. ${REQUIRED_HARD_POLICY_DETAIL}.",
      ),
    ),
    rollbackLimitsHeading = "Rollback limits",
    rollbackLimitsIntro = "Rollback wording stays intentionally narrow so OpenCray never promises recovery beyond verified local checkpoints.",
    rollbackLimitCards = listOf(
      DisclosureCardState(
        label = "Local-only",
        title = "Guaranteed only for local filesystem checkpoints",
        body = "Rollback is guaranteed only for local filesystem checkpoints created by OpenCray on this device.",
      ),
    ),
    telemetryPrivacyHeading = "Telemetry and privacy",
    telemetryPrivacyIntro = "This screen summarizes the same defaults a later host can expose with TelemetryToggles in settings.",
    telemetryPrivacyCards = listOf(
      DisclosureCardState(
        label = "Enable telemetry",
        title = "Default: Off",
        body = "Turning telemetry on allows anonymous product telemetry to leave the device. Leaving it off blocks outbound telemetry.",
      ),
    ),
    telemetryPrivacyFooter = "Defaults: Enable telemetry = Off. Enable privacy guard = On. A later host can embed TelemetryToggles here without changing these labels.",
    v1ScopeHeading = "V1 scope",
    v1ScopeIntro = "Release-facing scope stays explicit here so future ideas do not get mistaken for shipped OpenCray runtime support.",
    v1ScopeCards = listOf(
      DisclosureCardState(
        label = "Runtime",
        title = "Real Termux execution is out of scope",
        body = "V1 does not ship real Termux execution.",
      ),
    ),
  )

  private fun requireDisclosurePhrase(
    actualDisclosure: String,
    requiredPhrase: String,
  ) {
    if (!normalize(actualDisclosure).contains(normalize(requiredPhrase))) {
      throw AssertionError(
        "Release must fail: Developer mode disclosure must include \"$requiredPhrase\". Actual developer disclosure: \"$actualDisclosure\".",
      )
    }
  }

  private fun normalize(value: String): String = value
    .lowercase()
    .replace(WHITESPACE_REGEX, " ")
    .trim()

  private companion object {
    const val DEVELOPER_LABEL = "Developer"
    const val REQUIRED_HIGH_RISK_DISCLOSURE =
      "Developer mode can expose high-risk operations and reduce prompts"
    const val REQUIRED_HARD_DENIAL_DISCLOSURE =
      "Developer mode does not override hard denials"
    const val REQUIRED_HARD_POLICY_DETAIL =
      "Protected-file, path, and other hard policy denials still stop the action"
    val WHITESPACE_REGEX = Regex("\\s+")
  }
}

fun bootstrap() = Unit
