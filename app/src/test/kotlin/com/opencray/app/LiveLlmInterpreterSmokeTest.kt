package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
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
    val providerClient = RecordingProviderClient(
      delegate = OpenAiCompatibleLiteLlmProviderClient(),
    )
    val interpreter = LiteLlmSoulMemoryIntentInterpreter(
      llmSettingsProvider = { config.toSettingsState() },
      providerClient = providerClient,
    )

    val result = interpreter.interpret(
      SoulMemoryIntentRequest(
        sessionId = "live-soul-memory-intent",
        userInput = "以后别叫我阿澄了，叫我阿青。",
      ),
    )

    val success = result as? SoulMemoryIntentInterpretation.Success
    assertNotNull(
      """
      Expected live soul memory interpreter success but got:
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      success,
    )
    val preferredNameIntent = success!!.intents.firstOrNull { intent ->
      intent.preferenceKey == MemoryPreferenceKeys.USER_PREFERRED_NAME
    }
    assertNotNull(
      """
      Expected a user_preferred_name intent from live model output.
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      preferredNameIntent,
    )
    assertEquals(MemoryScope.USER, preferredNameIntent!!.scope)
    assertEquals(
      """
      Expected live model to extract 阿青 as preferred name.
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      "阿青",
      preferredNameIntent.preferenceValue,
    )
  }

  @Test
  fun memoryStewardshipInterpreterSupersedesOldPreferredNameWhenReplacementIsExplicit() {
    val config = requireConfigOrSkip()
    val providerClient = RecordingProviderClient(
      delegate = OpenAiCompatibleLiteLlmProviderClient(),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
      llmSettingsProvider = { config.toSettingsState() },
      providerClient = providerClient,
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
      """
      Expected live memory stewardship interpreter success but got:
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      success,
    )
    assertTrue(
      """
      Expected a supersede decision for the old preferred-name record.
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      success!!.decisions.any { decision ->
        decision.action == MemoryStewardshipAction.SUPERSEDE_RECORD_WITH_CANDIDATE &&
          decision.recordId == "pref-old-name" &&
          decision.candidateIndex == 0
      },
    )
  }

  @Test
  fun memoryStewardshipInterpreterMergesProjectFactWhenCandidateAddsCompatibleDetail() {
    val config = requireConfigOrSkip()
    val providerClient = RecordingProviderClient(
      delegate = OpenAiCompatibleLiteLlmProviderClient(),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
      llmSettingsProvider = { config.toSettingsState() },
      providerClient = providerClient,
    )

    val result = interpreter.interpret(
      MemoryStewardshipRequest(
        sessionId = "live-memory-stewardship-merge",
        userInput = "还是用 Gradle，这点不变，再补充一下要从仓库根目录走 wrapper。",
        activeRecords = listOf(
          StewardableMemoryRecord(
            id = "fact-gradle",
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "session-old",
            workspaceId = "workspace-main",
            content = "Project uses Gradle.",
          ),
        ),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "live-memory-stewardship-merge",
            workspaceId = "workspace-main",
            content = "Use the Gradle wrapper from the repo root.",
          ),
        ),
      ),
    )

    val success = result as? MemoryStewardshipInterpretation.Success
    assertNotNull(
      """
      Expected live memory stewardship interpreter success but got:
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      success,
    )
    assertTrue(
      """
      Expected a merge decision for the Gradle fact.
      ${debugSnapshot(providerClient, result)}
      """.trimIndent(),
      success!!.decisions.any { decision ->
        decision.action == MemoryStewardshipAction.MERGE_RECORD_WITH_CANDIDATE &&
          decision.recordId == "fact-gradle" &&
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

  private fun debugSnapshot(
    providerClient: RecordingProviderClient,
    interpretation: Any?,
  ): String = buildString {
    appendLine("interpretation=$interpretation")
    appendLine("provider_request=${providerClient.lastRequest?.let(::formatRequest) ?: "<none>"}")
    appendLine("provider_result=${providerClient.lastResult?.let(::formatResult) ?: "<none>"}")
  }

  private fun formatRequest(request: LiteLlmProviderRequest): String = buildString {
    append("route={")
    append("providerId=${request.route.providerId}")
    append(", model=${request.route.model}")
    request.route.baseUrl?.let { baseUrl ->
      append(", baseUrl=$baseUrl")
    }
    append(", timeoutMs=${request.route.timeoutMs}")
    append(", metadata=${request.route.metadata.toSortedMap()}")
    append("}")
    append(", request={")
    append("requestId=${request.request.requestId}")
    append(", systemPrompt=${preview(request.request.systemPrompt)}")
    append(", prompt=${preview(request.request.prompt)}")
    append("}")
  }

  private fun formatResult(result: LiteLlmProviderResult): String = when (result) {
    is LiteLlmProviderResult.Success -> buildString {
      append("Success(")
      append("finishReason=${result.finishReason}")
      append(", outputText=${preview(result.outputText)}")
      append(", completion=")
      append(
        result.completion?.let { completion ->
          "{" +
            "finalText=${preview(completion.finalText)}, " +
            "commentaryText=${preview(completion.commentaryText)}, " +
            "reasoningText=${preview(completion.reasoningText)}, " +
            "rawText=${preview(completion.rawText)}, " +
            "toolCalls=${completion.toolCalls}" +
            "}"
        } ?: "<none>",
      )
      append(", metadata=${result.metadata.toSortedMap()}")
      append(")")
    }

    is LiteLlmProviderResult.Timeout ->
      "Timeout(errorMessage=${result.errorMessage}, metadata=${result.metadata.toSortedMap()})"

    is LiteLlmProviderResult.RateLimited ->
      "RateLimited(retryAfterMs=${result.retryAfterMs}, errorMessage=${result.errorMessage}, metadata=${result.metadata.toSortedMap()})"

    is LiteLlmProviderResult.Failure ->
      "Failure(errorCode=${result.errorCode}, errorMessage=${result.errorMessage}, completion=${result.completion}, metadata=${result.metadata.toSortedMap()})"
  }

  private fun preview(value: String?): String {
    val normalized = value?.replace("\r", "\\r")?.replace("\n", "\\n")
    if (normalized.isNullOrBlank()) {
      return "<none>"
    }
    return if (normalized.length <= DEBUG_PREVIEW_LIMIT) {
      normalized
    } else {
      normalized.take(DEBUG_PREVIEW_LIMIT) + "...<truncated>"
    }
  }

  private class RecordingProviderClient(
    private val delegate: LiteLlmProviderClient,
  ) : LiteLlmProviderClient {
    var lastRequest: LiteLlmProviderRequest? = null
      private set

    var lastResult: LiteLlmProviderResult? = null
      private set

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      lastRequest = request
      return delegate.execute(request).also { result ->
        lastResult = result
      }
    }
  }

  private companion object {
    const val DEBUG_PREVIEW_LIMIT: Int = 2_000
  }
}
