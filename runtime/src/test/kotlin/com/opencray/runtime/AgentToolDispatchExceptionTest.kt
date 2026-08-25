package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.runtime.web.WebSearchResult
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolDispatchExceptionTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test(expected = CancellationException::class)
  fun dispatchPropagatesCancellationExceptionInsteadOfFailedResult() {
    val dispatcher = dispatcher(
      webSearchProvider = ThrowingWebSearchProvider(CancellationException("run cancelled")),
    )

    dispatcher.dispatch(
      task = agentTask(),
      call = webSearchCall(),
      hooks = runtimeHooks(),
    )
  }

  @Test(expected = OutOfMemoryError::class)
  fun dispatchPropagatesOutOfMemoryErrorInsteadOfFailedResult() {
    val dispatcher = dispatcher(
      webSearchProvider = ThrowingWebSearchProvider(OutOfMemoryError("heap exhausted")),
    )

    dispatcher.dispatch(
      task = agentTask(),
      call = webSearchCall(),
      hooks = runtimeHooks(),
    )
  }

  @Test
  fun dispatchRethrowsInterruptedExceptionAndRestoresInterruptFlag() {
    val dispatcher = dispatcher(
      webSearchProvider = ThrowingWebSearchProvider(InterruptedException("tool thread interrupted")),
    )
    var caught: InterruptedException? = null
    try {
      dispatcher.dispatch(
        task = agentTask(),
        call = webSearchCall(),
        hooks = runtimeHooks(),
      )
    } catch (interrupted: InterruptedException) {
      caught = interrupted
    } finally {
      assertTrue(Thread.currentThread().isInterrupted)
      Thread.interrupted()
    }
    assertNotNull(caught)
  }

  @Test
  fun dispatchStillConvertsOrdinaryExceptionsToFailedResult() {
    val dispatcher = dispatcher(
      webSearchProvider = ThrowingWebSearchProvider(IllegalStateException("boom")),
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = webSearchCall(),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("TOOL_EXECUTION_FAILED", result.errorCode)
    assertEquals("boom", result.content)
    assertEquals("boom", result.errorMessage)
  }

  private fun dispatcher(webSearchProvider: WebSearchProvider): OpenCrayToolDispatcher =
    OpenCrayToolDispatcher(
      OpenCrayToolDispatcherConfig(
        workspaceRoots = setOf(temporaryFolder.newFolder("dispatch-exception-workspace").toPath()),
        webSearchProvider = webSearchProvider,
      ),
    )

  private fun webSearchCall(): AgentToolCall = AgentToolCall(
    toolName = "WebSearch",
    arguments = JsonObject(mapOf("query" to JsonPrimitive("opencray"))),
  )

  private fun agentTask(): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = mapOf("chatMode" to "AUTO"),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentToolDispatchExceptionTest.") },
  )

  private class ThrowingWebSearchProvider(
    private val error: Throwable,
  ) : WebSearchProvider {
    override val providerName: String = "throwing-search"

    override fun search(request: WebSearchRequest): WebSearchResult = throw error
  }
}
