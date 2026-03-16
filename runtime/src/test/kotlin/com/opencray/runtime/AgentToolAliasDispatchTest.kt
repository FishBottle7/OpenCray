package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.process.AgentProcessRegistry
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.web.WebContentFetcher
import com.opencray.runtime.web.WebFetchRequest
import com.opencray.runtime.web.WebFetchResult
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.runtime.web.WebSearchResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolAliasDispatchTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun definitionsExposeClaudeStyleToolsAndCompatibleAliases() {
    val dispatcher = dispatcher()

    val definitionNames = dispatcher.definitions().map { definition -> definition.name }.toSet()
    val readDefinition = requireNotNull(dispatcher.definitions().firstOrNull { definition -> definition.name == "read" })
    val pythonDefinition = requireNotNull(dispatcher.definitions().firstOrNull { definition -> definition.name == "python_exec" })

    assertTrue("LS" in definitionNames)
    assertTrue("Read" in definitionNames)
    assertTrue("Write" in definitionNames)
    assertTrue("Grep" in definitionNames)
    assertTrue("Glob" in definitionNames)
    assertTrue("WebSearch" in definitionNames)
    assertTrue("WebFetch" in definitionNames)
    assertTrue("Edit" in definitionNames)
    assertTrue("MultiEdit" in definitionNames)
    assertTrue("TodoWrite" in definitionNames)
    assertTrue("read" in definitionNames)
    assertTrue("write" in definitionNames)
    assertTrue("list" in definitionNames)
    assertTrue("ls" in definitionNames)
    assertTrue("grep" in definitionNames)
    assertTrue("glob" in definitionNames)
    assertTrue("websearch" in definitionNames)
    assertTrue("webfetch" in definitionNames)
    assertTrue("edit" in definitionNames)
    assertTrue("multiedit" in definitionNames)
    assertTrue("todowrite" in definitionNames)
    assertTrue("Bash" in definitionNames)
    assertTrue("bash" in definitionNames)
    assertTrue(readDefinition.description.contains("Compatibility alias for Read"))
    assertTrue(pythonDefinition.description.contains("follows execute-command policy gates"))
  }

  @Test
  fun readAliasDispatchesToClaudeReadAndPreservesCanonicalMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-read").toPath()
    Files.write(
      workspaceRoot.resolve("README.md"),
      "alias content".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "read",
        arguments = JsonObject(
          mapOf("path" to JsonPrimitive("README.md")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("read", result.toolName)
    assertEquals("Read", result.metadata["canonicalToolName"])
    assertEquals("alias content", result.content)
  }

  @Test
  fun grepAliasDispatchesToClaudeGrepAndPreservesCanonicalMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-grep").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "first line\nmatch here".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "grep",
        arguments = JsonObject(
          mapOf(
            "pattern" to JsonPrimitive("match"),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("grep", result.toolName)
    assertEquals("Grep", result.metadata["canonicalToolName"])
    assertTrue(result.content.contains("notes.txt:2:match here"))
  }

  @Test
  fun bashAliasDispatchesToClaudeBashAndPreservesCanonicalMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-bash").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      processRegistry = AliasProcessRegistry(),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "bash",
        arguments = JsonObject(
          mapOf("command" to JsonPrimitive("Get-ChildItem")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("bash", result.toolName)
    assertEquals("Bash", result.metadata["canonicalToolName"])
    assertTrue(result.content.contains("Shell command finished."))
  }

  @Test
  fun webFetchAliasDispatchesToCanonicalToolAndPreservesMapping() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-webfetch").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      webContentFetcher = FakeWebContentFetcher(),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "webfetch",
        arguments = JsonObject(
          mapOf("url" to JsonPrimitive("https://example.com/post")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("webfetch", result.toolName)
    assertEquals("WebFetch", result.metadata["canonicalToolName"])
    assertTrue(result.content.contains("Example Title"))
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path = temporaryFolder.newFolder("tool-alias-workspace").toPath(),
    processRegistry: AgentProcessRegistry = AliasProcessRegistry(),
    webContentFetcher: WebContentFetcher = FakeWebContentFetcher(),
    webSearchProvider: WebSearchProvider = FakeWebSearchProvider(),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      processRegistry = processRegistry,
      webContentFetcher = webContentFetcher,
      webSearchProvider = webSearchProvider,
    ),
  )

  private fun agentTask(
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata: Map<String, String> = emptyMap(),
  ): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = policyDecision,
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in AgentToolAliasDispatchTest.") },
  )

  private class AliasProcessRegistry : AgentProcessRegistry {
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      val snapshot = ManagedProcessSnapshot(
        processId = request.processId,
        taskId = request.taskId,
        command = request.command,
        args = request.args,
        workingDirectory = request.workingDirectory,
        status = ManagedProcessStatus.RUNNING,
        processStarted = true,
        timeoutMs = request.timeoutMs,
        startedAtEpochMs = 1_000L,
        updatedAtEpochMs = 1_000L,
        metadata = request.metadata,
      )
      snapshotsById[request.processId] = snapshot
      return snapshot
    }

    override fun list(): List<ManagedProcessSnapshot> = snapshotsById.values.toList()

    override fun read(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]

    override fun wait(processId: String, timeoutMs: Long): ManagedProcessSnapshot? {
      val existing = snapshotsById[processId] ?: return null
      val completed = existing.copy(
        status = ManagedProcessStatus.SUCCESS,
        stdout = "alias ok",
        exitCode = 0,
        updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
      )
      snapshotsById[processId] = completed
      return completed
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]
  }

  private class FakeWebContentFetcher : WebContentFetcher {
    override fun fetch(request: WebFetchRequest): WebFetchResult = WebFetchResult(
      requestedUrl = request.url,
      finalUrl = request.url,
      statusCode = 200,
      contentType = "text/html",
      title = "Example Title",
      content = "Example body",
    )
  }

  private class FakeWebSearchProvider : WebSearchProvider {
    override val providerName: String = "fake-search"

    override fun search(request: WebSearchRequest): WebSearchResult = WebSearchResult(
      providerName = providerName,
    )
  }
}
