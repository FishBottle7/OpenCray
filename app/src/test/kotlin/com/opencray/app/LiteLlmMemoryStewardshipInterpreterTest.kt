package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import com.opencray.runtime.memory.MemoryKind
import com.opencray.runtime.memory.MemoryPreferenceKeys
import com.opencray.runtime.memory.MemoryScope
import com.opencray.runtime.memory.MemoryEvidenceSource
import com.opencray.runtime.memory.MemoryStewardshipAction
import com.opencray.runtime.memory.MemoryStewardshipInterpretation
import com.opencray.runtime.memory.MemoryStewardshipRequest
import com.opencray.runtime.memory.StewardableMemoryRecord
import com.opencray.runtime.memory.StewardableMemoryCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteLlmMemoryStewardshipInterpreterTest {
  @Test
  fun interpretAllowsCandidateOnlyDropDecisionsWhenThereAreNoActiveRecords() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"decisions":[
            {"action":"drop_candidate","candidate_index":0}
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "记住这个项目跑在 8000 端口，不是 3000。",
        activeRecords = emptyList(),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Project runs on port 3000",
            sourceSessionId = "session-1",
            workspaceId = "workspace-main",
          ),
          StewardableMemoryCandidate(
            index = 1,
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Project runs on port 8000",
            sourceSessionId = "session-1",
            workspaceId = "workspace-main",
          ),
        ),
      ),
    )

    val success = result as MemoryStewardshipInterpretation.Success
    assertEquals(1, success.decisions.size)
    assertEquals(MemoryStewardshipAction.DROP_CANDIDATE, success.decisions.single().action)
    assertEquals(0, success.decisions.single().candidateIndex)
    assertEquals("low", providerClient.lastRequest?.route?.metadata?.get("reasoning_effort"))
    assertEquals("512", providerClient.lastRequest?.route?.metadata?.get("max_tokens"))
    assertEquals("0", providerClient.lastRequest?.route?.metadata?.get("temperature"))
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("Active related records:\n<none>"))
    assertTrue(providerClient.lastRequest?.request?.prompt.orEmpty().contains("drop_candidate"))
  }

  @Test
  fun interpretDocumentsDurableInstructionCandidateOnlyReviewRules() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """{"decisions":[]}""",
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "以后这个仓库不要用 git reset --hard。",
        activeRecords = emptyList(),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.DURABLE_INSTRUCTION,
            scope = MemoryScope.WORKSPACE,
            content = "Do not use git reset --hard in this repo",
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "session-1",
            workspaceId = "workspace-main",
          ),
        ),
      ),
    )

    assertTrue(result is MemoryStewardshipInterpretation.Success)
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("For durable instructions, drop one-turn execution directions"))
    assertTrue(prompt.contains("kind=durable_instruction"))
    assertTrue(prompt.contains("source=user_input"))
    assertTrue(prompt.contains("Active related records:\n<none>"))
  }

  @Test
  fun interpretDocumentsUserPreferenceCandidateOnlyReviewRules() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """{"decisions":[]}""",
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "以后叫我阿澄，平时这样称呼我。",
        activeRecords = emptyList(),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            content = "Preferred user naming is 阿澄",
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "session-1",
            preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
            preferenceValue = "阿澄",
          ),
        ),
      ),
    )

    assertTrue(result is MemoryStewardshipInterpretation.Success)
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("For user preferences, drop one-turn formatting asks"))
    assertTrue(prompt.contains("kind=user_preference"))
    assertTrue(prompt.contains("preference_key=${MemoryPreferenceKeys.USER_PREFERRED_NAME}"))
    assertTrue(prompt.contains("preference_value=阿澄"))
  }

  @Test
  fun interpretIncludesRecordRecencyAndCandidateTaskMetadataInPrompt() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """{"decisions":[]}""",
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "不是 3000，现在是 8000。",
        activeRecords = listOf(
          StewardableMemoryRecord(
            id = "fact-old",
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Project runs on port 3000",
            source = MemoryEvidenceSource.TOOL_OBSERVATION,
            sourceSessionId = "session-0",
            workspaceId = "workspace-main",
            updatedAtEpochMs = 2_000L,
            lastConfirmedAtEpochMs = 1_500L,
          ),
        ),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Project runs on port 8000",
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "session-1",
            sourceTaskId = "task-1",
            workspaceId = "workspace-main",
          ),
        ),
      ),
    )

    assertTrue(result is MemoryStewardshipInterpretation.Success)
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("updated_at_epoch_ms=2000"))
    assertTrue(prompt.contains("last_confirmed_at_epoch_ms=1500"))
    assertTrue(prompt.contains("source_task_id=task-1"))
    assertTrue(prompt.contains("Prefer the current turn's explicit evidence over older conflicting records"))
    assertTrue(prompt.contains("别再叫我阿澄了，以后叫我阿青"))
  }

  @Test
  fun interpretParsesRefreshRecordWithCandidateAction() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"decisions":[
            {"action":"refresh_record_with_candidate","record_id":"fact-old","candidate_index":0}
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "对，还是 8000。",
        activeRecords = listOf(
          StewardableMemoryRecord(
            id = "fact-old",
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Project runs on port 8000",
          ),
        ),
        proposedCandidates = listOf(
          StewardableMemoryCandidate(
            index = 0,
            kind = MemoryKind.PROJECT_FACT,
            scope = MemoryScope.WORKSPACE,
            content = "Current project port is 8000",
            sourceSessionId = "session-1",
            workspaceId = "workspace-main",
          ),
        ),
      ),
    )

    val success = result as MemoryStewardshipInterpretation.Success
    assertEquals(1, success.decisions.size)
    assertEquals(
      MemoryStewardshipAction.REFRESH_RECORD_WITH_CANDIDATE,
      success.decisions.single().action,
    )
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("refresh_record_with_candidate"))
    assertTrue(prompt.contains("does not add new durable detail"))
  }

  @Test
  fun interpretParsesRecordOnlyResolveDecisionWhenThereAreNoCandidates() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"decisions":[
            {"action":"resolve_record","record_id":"pref-old","resolution_reason":"invalidated"}
          ]}
        """.trimIndent(),
      ),
    )
    val interpreter = LiteLlmMemoryStewardshipInterpreter(
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
      MemoryStewardshipRequest(
        sessionId = "session-1",
        userInput = "以后不要再叫我阿澄了。",
        activeRecords = listOf(
          StewardableMemoryRecord(
            id = "pref-old",
            kind = MemoryKind.USER_PREFERENCE,
            scope = MemoryScope.USER,
            content = "Preferred user naming is 阿澄",
            source = MemoryEvidenceSource.USER_INPUT,
            sourceSessionId = "session-0",
            preferenceKey = MemoryPreferenceKeys.USER_PREFERRED_NAME,
            preferenceValue = "阿澄",
          ),
        ),
        proposedCandidates = emptyList(),
      ),
    )

    val success = result as MemoryStewardshipInterpretation.Success
    assertEquals(1, success.decisions.size)
    assertEquals(MemoryStewardshipAction.RESOLVE_RECORD, success.decisions.single().action)
    val prompt = providerClient.lastRequest?.request?.prompt.orEmpty()
    assertTrue(prompt.contains("If there are no proposed candidates, only use resolve_record or reaffirm_record"))
    assertTrue(prompt.contains("Proposed candidates:\n<none>"))
    assertTrue(prompt.contains("id=pref-old"))
    assertTrue(prompt.contains("preference_key=${MemoryPreferenceKeys.USER_PREFERRED_NAME}"))
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
