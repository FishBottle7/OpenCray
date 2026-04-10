package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
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
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    assertTrue("ScheduledTaskCreate" in definitionNames)
    assertTrue("ScheduledTaskList" in definitionNames)
    assertTrue("ScheduledTaskGet" in definitionNames)
    assertTrue("ScheduledTaskUpdate" in definitionNames)
    assertTrue("ScheduledTaskDelete" in definitionNames)
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
    assertTrue("scheduledtaskcreate" in definitionNames)
    assertTrue("scheduled_task_create" in definitionNames)
    assertTrue("scheduledtasklist" in definitionNames)
    assertTrue("scheduled_task_list" in definitionNames)
    assertTrue("scheduledtaskget" in definitionNames)
    assertTrue("scheduled_task_get" in definitionNames)
    assertTrue("scheduledtaskupdate" in definitionNames)
    assertTrue("scheduled_task_update" in definitionNames)
    assertTrue("scheduledtaskdelete" in definitionNames)
    assertTrue("scheduled_task_delete" in definitionNames)
    assertTrue("Bash" in definitionNames)
    assertTrue("bash" in definitionNames)
    assertTrue(readDefinition.description.contains("Compatibility alias for Read"))
    assertTrue(pythonDefinition.description.contains("Use this instead of Bash for workspace Python scripts"))
  }

  @Test
  fun pythonRuntimeManifestToolAppearsWhenManifestProviderExists() {
    val dispatcher = dispatcher(
      pythonRuntimeManifestProvider = {
        PythonRuntimeManifestSnapshot(
          runtimeBackend = "p4a",
          packageInstallPolicy = "preinstalled_only",
          supportsDynamicInstall = false,
          interpreter = "python3",
          packages = listOf("numpy", "python-docx"),
        )
      },
    )

    val definitionNames = dispatcher.definitions().map { definition -> definition.name }.toSet()
    val pythonDefinition = requireNotNull(dispatcher.definitions().firstOrNull { definition -> definition.name == "python_exec" })

    assertTrue("python_runtime_manifest" in definitionNames)
    assertTrue(pythonDefinition.description.contains("python_runtime_manifest"))

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "python_runtime_manifest"),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    val payload = Json.parseToJsonElement(result.content).jsonObject
    assertEquals("p4a", payload.getValue("runtime_backend").jsonPrimitive.content)
    assertTrue(payload.getValue("packages").jsonArray.any { entry -> entry.jsonPrimitive.content == "numpy" })
    assertEquals("read_python_runtime", result.metadata["capabilityKind"])
    assertEquals("2", result.metadata["packageCount"])
    assertEquals("false", result.metadata["supportsDynamicInstall"])
  }

  @Test
  fun hiddenToolNamePrefixesRemoveMatchingDefinitionsAndDenyDispatch() {
    val dispatcher = dispatcher(hiddenToolNamePrefixes = setOf("web"))

    val definitionNames = dispatcher.definitions().map { definition -> definition.name }.toSet()

    assertFalse("WebSearch" in definitionNames)
    assertFalse("WebFetch" in definitionNames)
    assertFalse("websearch" in definitionNames)
    assertFalse("webfetch" in definitionNames)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "webfetch",
        arguments = JsonObject(
          mapOf("url" to JsonPrimitive("https://example.com")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("TOOL_UNAVAILABLE_IN_RUNTIME", result.errorCode)
    assertEquals("WebFetch", result.metadata["canonicalToolName"])
    assertTrue(result.content.contains("unavailable in the current execution environment"))
  }

  @Test
  fun multiEditAndTodoWriteDefinitionsExposeStructuredObjectSchemas() {
    val dispatcher = dispatcher()
    val multiEditDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "MultiEdit" },
    )
    val todoWriteDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "TodoWrite" },
    )
    val scheduledTaskDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "ScheduledTaskCreate" },
    )
    val scheduledTaskListDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "ScheduledTaskList" },
    )
    val scheduledTaskGetDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "ScheduledTaskGet" },
    )
    val scheduledTaskUpdateDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "ScheduledTaskUpdate" },
    )
    val scheduledTaskDeleteDefinition = requireNotNull(
      dispatcher.definitions().firstOrNull { definition -> definition.name == "ScheduledTaskDelete" },
    )

    val multiEditSchema = multiEditDefinition.toJsonSchema()
    val editsSchema = multiEditSchema.requiredProperty("properties").requiredProperty("edits")
    val editItemSchema = editsSchema.requiredProperty("items")
    val editItemProperties = editItemSchema.requiredProperty("properties")
    assertEquals("array", editsSchema.requiredString("type"))
    assertEquals("object", editItemSchema.requiredString("type"))
    assertEquals("string", editItemProperties.requiredProperty("old_string").requiredString("type"))
    assertEquals("string", editItemProperties.requiredProperty("new_string").requiredString("type"))
    assertEquals("boolean", editItemProperties.requiredProperty("replace_all").requiredString("type"))
    assertTrue(editItemSchema.requiredStringArray("required").containsAll(listOf("old_string", "new_string")))
    assertEquals("false", editItemSchema.requiredPrimitive("additionalProperties").content)

    val todoWriteSchema = todoWriteDefinition.toJsonSchema()
    val todosSchema = todoWriteSchema.requiredProperty("properties").requiredProperty("todos")
    val todoItemSchema = todosSchema.requiredProperty("items")
    val todoItemProperties = todoItemSchema.requiredProperty("properties")
    val statusSchema = todoItemProperties.requiredProperty("status")
    assertEquals("array", todosSchema.requiredString("type"))
    assertEquals("object", todoItemSchema.requiredString("type"))
    assertEquals("string", todoItemProperties.requiredProperty("content").requiredString("type"))
    assertEquals("string", statusSchema.requiredString("type"))
    assertEquals(
      listOf("pending", "in_progress", "completed"),
      statusSchema.requiredStringArray("enum"),
    )
    assertTrue(
      todoWriteDefinition.description.contains("empty todos array to clear"),
    )
    assertTrue(
      todosSchema.requiredString("description").contains("Send an empty array to clear"),
    )
    assertTrue(todoItemSchema.requiredStringArray("required").containsAll(listOf("content", "status")))
    assertEquals("false", todoItemSchema.requiredPrimitive("additionalProperties").content)

    val scheduledTaskSchema = scheduledTaskDefinition.toJsonSchema()
    val triggerSchema = scheduledTaskSchema.requiredProperty("properties").requiredProperty("trigger")
    val triggerProperties = triggerSchema.requiredProperty("properties")
    assertEquals("object", triggerSchema.requiredString("type"))
    assertEquals("string", triggerProperties.requiredProperty("at").requiredString("type"))
    assertEquals("string", triggerProperties.requiredProperty("after").requiredString("type"))
    assertEquals("string", triggerProperties.requiredProperty("start_at").requiredString("type"))
    assertEquals("string", triggerProperties.requiredProperty("timezone").requiredString("type"))
    assertEquals("string", triggerProperties.requiredProperty("rrule").requiredString("type"))
    assertTrue(
      triggerSchema.requiredString("description").contains("start_at plus rrule"),
    )
    assertEquals("false", triggerSchema.requiredPrimitive("additionalProperties").content)
    assertTrue(
      scheduledTaskListDefinition.description.contains("current chat session"),
    )
    assertTrue(
      scheduledTaskGetDefinition.toJsonSchema().requiredStringArray("required").contains("schedule_id"),
    )
    assertTrue(
      scheduledTaskUpdateDefinition.toJsonSchema().requiredStringArray("required").contains("schedule_id"),
    )
    assertTrue(
      scheduledTaskDeleteDefinition.toJsonSchema().requiredStringArray("required").contains("schedule_id"),
    )
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
  fun bashPythonScriptCommandRewritesToPythonExec() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-bash-python").toPath()
    Files.createDirectories(workspaceRoot.resolve("scripts"))
    Files.write(
      workspaceRoot.resolve("scripts").resolve("run.py"),
      "print('alias python')".toByteArray(StandardCharsets.UTF_8),
    )
    val pythonRuntime = RecordingPythonScriptRuntime()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      pythonRuntime = pythonRuntime,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "bash",
        arguments = JsonObject(
          mapOf(
            "command" to JsonPrimitive("python run.py --flag \"two words\""),
            "working_directory" to JsonPrimitive("scripts"),
            "timeout_ms" to JsonPrimitive(45_000),
          ),
        ),
      ),
      hooks = runtimeHooks(),
    )

    val request = requireNotNull(pythonRuntime.lastRequest)
    val expectedScriptPath = workspaceRoot.resolve("scripts").resolve("run.py").toRealPath()
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("python_exec", result.toolName)
    assertEquals("bash", result.metadata["requestedToolName"])
    assertEquals("python_exec", result.metadata["normalizedToolName"])
    assertEquals("true", result.metadata["toolRewrite"])
    assertEquals(expectedScriptPath, request.scriptPath)
    assertEquals(listOf("--flag", "two words"), request.args)
    assertEquals(45_000L, request.timeoutMs)
  }

  @Test
  fun bashPythonVersionCommandFailsWithPythonExecGuidance() {
    val workspaceRoot = temporaryFolder.newFolder("tool-alias-bash-python-version").toPath()
    val processRegistry = AliasProcessRegistry()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      processRegistry = processRegistry,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "bash",
        arguments = JsonObject(
          mapOf("command" to JsonPrimitive("python --version")),
        ),
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("bash", result.toolName)
    assertEquals("Bash", result.metadata["canonicalToolName"])
    assertEquals("BASH_PYTHON_UNSUPPORTED", result.errorCode)
    assertEquals("python", result.metadata["pythonCommand"])
    assertEquals("python_exec", result.metadata["recommendedTool"])
    assertEquals("true", result.metadata["bashPythonInvocationBlocked"])
    assertTrue(result.content.contains("Use python_exec instead"))
    assertTrue(result.content.contains("Python version or environment details"))
    assertTrue(processRegistry.list().isEmpty())
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
    pythonRuntime: PythonScriptRuntime = RecordingPythonScriptRuntime(),
    pythonRuntimeManifestProvider: (() -> PythonRuntimeManifestSnapshot?)? = null,
    hiddenToolNamePrefixes: Set<String> = emptySet(),
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      processRegistry = processRegistry,
      webContentFetcher = webContentFetcher,
      webSearchProvider = webSearchProvider,
      pythonRuntimeAdapter = pythonRuntime,
      pythonRuntimeManifestProvider = pythonRuntimeManifestProvider,
      hiddenToolNamePrefixes = hiddenToolNamePrefixes,
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

  private fun JsonObject.requiredProperty(key: String): JsonObject =
    (get(key) ?: error("Missing property '$key'.")).jsonObject

  private fun JsonObject.requiredPrimitive(key: String): JsonPrimitive =
    (get(key) ?: error("Missing primitive '$key'.")) as JsonPrimitive

  private fun JsonObject.requiredString(key: String): String = requiredPrimitive(key).content

  private fun JsonObject.requiredStringArray(key: String): List<String> =
    (get(key) ?: error("Missing array '$key'.")).let { element ->
      (element as JsonArray).map { item -> (item as JsonPrimitive).content }
    }

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

  private class RecordingPythonScriptRuntime : PythonScriptRuntime {
    var lastRequest: PythonExecRequest? = null

    override fun exec(request: PythonExecRequest): ExecutionResult {
      lastRequest = request
      return ExecutionResult(
        taskId = request.taskId,
        status = ExecutionStatus.SUCCESS,
        stdout = "python ok",
        startedAtEpochMs = 1_000L,
        finishedAtEpochMs = 1_050L,
        metadata = mapOf("runtimeBackend" to "test-python"),
      )
    }
  }
}
