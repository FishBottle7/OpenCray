package com.opencray.app

import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipInterpretation
import com.opencray.runtime.memory.MemoryStewardshipRequest
import com.opencray.runtime.memory.SoulMemoryIntentInterpretation
import com.opencray.runtime.memory.SoulMemoryIntentRequest
import com.opencray.runtime.memory.StewardableMemoryCandidate
import com.opencray.runtime.memory.StewardableMemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveLlmInterpreterSmokeTest {
  @Test
  fun soulMemoryInterpreterExtractsDurablePreferredName() {
    val config = requireConfigOrSkip()
    val interpreter = LiteLlmSoulMemoryIntentInterpreter(
      llmSettingsProvider = { config.toSettingsState() },
      providerClient = OpenAiCompatibleLiteLlmProviderClient(),
    )

    val result = interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "live-soul-memory-intent",
        userInput = "以后别叫我阿澄了，叫我阿青。",
      ),
    )

    val success = result as? SoulMemoryIntentInterpretation.Success
    assertNotNull(
      "Expected live soul memory interpreter success but got $result",
      success,
    )
    val preferredNameIntent = success!!.intents.firstOrNull { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    assertNotNull("Expected a user_preferred_name intent from live model output.", preferredNameIntent)
    assertEquals(MemoryScope.USER, preferredNameIntent!!.scope)
    assertEquals("阿青", preferredNameIntent.preferenceValue)
  }

  @Test
  fun memoryStewardshipInterpreterSupersedesOldPreferredNameWhenReplacementIsExplicit() {
    val config = requireConfigOrSkip()
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
      llmSettingsProvider = { config.toSettingsState() },
      providerClient = OpenAiCompatibleLiteLlmProviderClient(),
    )

    val result = interpreter.interpret(
      MemoryStewardshipRequest(
        sessionId = "live-memory-stewardship",
        userInput = "别再叫我阿澄了，以后叫我阿青。",
        activeRecords = listOf(
          StewardableMemoryRecord(
            id = "pref-old-name",
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            source = MemoryEvidenceSource.USER_INPUT,
            preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
            preferenceValue = "阿澄",
            content = "User preferred name is 阿澄.",
          ),
        ),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "live-memory-stewardship",
            preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
            preferenceValue = "阿青",
            content = "User preferred name is 阿青.",
          ),
        ),
      ),
    )

    val success = result as? MemoryStewardshipInterpretation.Success
    assertNotNull(
      "Expected live memory stewardship interpreter success but got $result",
      success,
    )
    assertTrue(
      "Expected a supersede decision for the old preferred-name record, got ${success!!.decisions}",
      success.decisions.any { decision ->
        decision.action == MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE &&
          decision.recordId == "pref-old-name" &&
          decision.candidateIndex == 0
      },
    )
  }

  private fun requireConfigOrSkip(): LocalLiveLlmTestConfig {
    val config = LocalLiveLlmTestConfig.load()
    assumeTrue(
      "Missing local live LLM test config. Create ${LocalLiveLlmTestConfig.defaultConfigPath()} first.",
      config != null,
    )
    return config!!
  }
}
