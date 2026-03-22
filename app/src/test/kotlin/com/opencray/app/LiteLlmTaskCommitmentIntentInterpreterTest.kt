package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.memory.OpenTaskCommitment
import com.opencray.runtime.memory.ProposedTaskCommitment
import com.opencray.runtime.memory.TaskCommitmentIntentAction
import com.opencray.runtime.memory.TaskCommitmentIntentDecision
import com.opencray.runtime.memory.TaskCommitmentIntentInterpretation
import com.opencray.runtime.memory.TaskCommitmentIntentRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmTaskCommitmentIntentInterpreterTest {
  @Test
  fun interpretReturnsExtendedDecisionsFromJsonPayload() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"decisions":[
            {"commitment_id":"commitment-1","action":"resolve"},
            {"commitment_id":"commitment-2","action":"supersede_with_proposed","proposed_commitment_index":0},
            {"proposed_commitment_index":1,"action":"drop_proposed"},
            {"commitment_id":"commitment-3","action":"abandon"}
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmTaskCommitmentIntentInterpreter(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "test-key",
          model = "gpt-4o-mini",
        )
      },
      providerClient = providerClient,
    )

    val result = interpreter.interpret(
      TaskCommitmentIntentRequest(
        sessionId = "session-1",
        commitments = listOf(
          OpenTaskCommitment(
            id = "commitment-1",
            content = "run the targeted runtime tests",
          ),
          OpenTaskCommitment(
            id = "commitment-2",
            content = "stabilize the flaky runtime test",
          ),
          OpenTaskCommitment(
            id = "commitment-3",
            content = "prepare the release branch",
          ),
        ),
        userInput = "Stop the runtime-test plan and switch to Android smoke tests.",
        proposedCommitments = listOf(
          ProposedTaskCommitment(
            candidateIndex = 0,
            content = "verify the Android smoke tests",
          ),
          ProposedTaskCommitment(
            candidateIndex = 1,
            content = "stabilize the flaky runtime test",
          ),
        ),
        assistantOutput = "The targeted runtime tests are green now, but the flaky runtime test still needs work.",
      ),
    )

    val success = result as TaskCommitmentIntentInterpretation.Success
    assertEquals(4, success.decisions.size)
    assertEquals("gpt-4o-mini", providerClient.lastRequest?.route?.model)
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("commitment-1"))
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("Android smoke tests"))
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("candidate 0"))
    assertEquals(TaskCommitmentIntentAction.RESOLVE, success.decisions.first { it.commitmentId == "commitment-1" }.action)
    assertEquals(
      TaskCommitmentIntentDecision(
        commitmentId = "commitment-2",
        action = TaskCommitmentIntentAction.SUPERSEDE_WITH_PROPOSED,
        proposedCommitmentIndex = 0,
      ),
      success.decisions.first { it.commitmentId == "commitment-2" },
    )
    assertEquals(
      TaskCommitmentIntentDecision(
        action = TaskCommitmentIntentAction.DROP_PROPOSED,
        proposedCommitmentIndex = 1,
      ),
      success.decisions.first { it.proposedCommitmentIndex == 1 },
    )
    assertEquals(TaskCommitmentIntentAction.ABANDON, success.decisions.first { it.commitmentId == "commitment-3" }.action)
  }

  @Test
  fun interpretSuppressesFallbackWhenModelResponseIsMalformed() {
    val interpreter = LiteLlmTaskCommitmentIntentInterpreter(
      llmSettingsProvider = {
        LlmSettingsState(
          enabled = true,
          providerId = "openai",
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          apiKey = "test-key",
          model = "gpt-4o-mini",
        )
      },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(
          outputText = "not json",
        ),
      ),
    )

    val result = interpreter.interpret(
      TaskCommitmentIntentRequest(
        sessionId = "session-2",
        commitments = listOf(
          OpenTaskCommitment(
            id = "commitment-1",
            content = "run the targeted runtime tests",
          ),
        ),
        assistantOutput = "The targeted runtime tests are green now.",
      ),
    )

    val unavailable = result as TaskCommitmentIntentInterpretation.Unavailable
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  @Test
  fun interpretFailsClosedWhenNoLlmIsConfigured() {
    val interpreter = LiteLlmTaskCommitmentIntentInterpreter(
      llmSettingsProvider = { LlmSettingsState() },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(outputText = """{"decisions":[]}"""),
      ),
    )

    val result = interpreter.interpret(
      TaskCommitmentIntentRequest(
        sessionId = "session-3",
        commitments = listOf(
          OpenTaskCommitment(
            id = "commitment-1",
            content = "run the targeted runtime tests",
          ),
        ),
        assistantOutput = "The targeted runtime tests are green now.",
      ),
    )

    val unavailable = result as TaskCommitmentIntentInterpretation.Unavailable
    assertEquals(false, unavailable.allowHeuristicFallback)
  }

  private class RecordingProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var lastRequest: LiteLlmProviderRequest? = null

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      lastRequest = request
      return result
    }
  }
}
