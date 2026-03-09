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
    val baselineState = SafetyAndLimitsScreenState()
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
    val developerCard = requireDeveloperModeDisclosure(SafetyAndLimitsScreenState())

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
