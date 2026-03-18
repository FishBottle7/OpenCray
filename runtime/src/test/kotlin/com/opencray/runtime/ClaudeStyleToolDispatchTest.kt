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
import com.opencray.runtime.web.UnconfiguredWebSearchProvider
import com.opencray.runtime.web.WebContentFetcher
import com.opencray.runtime.web.WebFetchRequest
import com.opencray.runtime.web.WebFetchResult
import com.opencray.runtime.web.WebSearchHit
import com.opencray.runtime.web.WebSearchProvider
import com.opencray.runtime.web.WebSearchRequest
import com.opencray.runtime.web.WebSearchResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClaudeStyleToolDispatchTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun readSupportsOffsetAndLimit() {
    val workspaceRoot = temporaryFolder.newFolder("claude-read").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "one\ntwo\nthree\nfour".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Read",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put("offset", 2)
          put("limit", 2)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("two\nthree", result.content)
    assertEquals("read_file", result.metadata["capabilityKind"])
    assertEquals("file", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals("notes.txt", result.metadata["primaryTargetPath"])
    assertEquals("2", result.metadata["offset"])
    assertEquals("2", result.metadata["limit"])
    assertEquals("2", result.metadata["returnedLineCount"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("false", result.metadata["resultTruncated"])
    assertEquals("read_byte_budget", result.metadata["resultLimitKind"])
  }

  @Test
  fun readEmitsStableResultLimitContractWhenByteBudgetIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("claude-read-truncated").toPath()
    Files.write(
      workspaceRoot.resolve("notes.txt"),
      "123456789".toByteArray(StandardCharsets.UTF_8),
    )
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      maxReadBytes = 5,
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Read",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("12345", result.content)
    assertEquals("true", result.metadata["truncated"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("true", result.metadata["resultTruncated"])
    assertEquals("read_byte_budget", result.metadata["resultLimitKind"])
  }

  @Test
  fun writeAllowsEmptyContent() {
    val workspaceRoot = temporaryFolder.newFolder("claude-write-empty").toPath()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Write",
        arguments = buildJsonObject {
          put("file_path", "empty.txt")
          put("content", "")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("", String(Files.readAllBytes(workspaceRoot.resolve("empty.txt")), StandardCharsets.UTF_8))
  }

  @Test
  fun globMatchesWorkspaceRelativePathsRecursively() {
    val workspaceRoot = temporaryFolder.newFolder("claude-glob").toPath()
    Files.createDirectories(workspaceRoot.resolve("src").resolve("main"))
    Files.write(workspaceRoot.resolve("src").resolve("main").resolve("App.kt"), "class App".toByteArray(StandardCharsets.UTF_8))
    Files.write(workspaceRoot.resolve("README.md"), "readme".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Glob",
        arguments = buildJsonObject {
          put("pattern", "**/*.kt")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("src/main/App.kt"))
    assertTrue(!result.content.contains("README.md"))
    assertEquals("read_file", result.metadata["capabilityKind"])
    assertEquals("search_root", result.metadata["targetKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals(".", result.metadata["primaryTargetPath"])
    assertEquals("**/*.kt", result.metadata["pattern"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("false", result.metadata["resultTruncated"])
    assertEquals("search_match_limit", result.metadata["resultLimitKind"])
  }

  @Test
  fun lsEmitsStableResultLimitContractWhenEntryWindowIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("claude-ls-truncated").toPath()
    Files.write(workspaceRoot.resolve("a.txt"), "a".toByteArray(StandardCharsets.UTF_8))
    Files.write(workspaceRoot.resolve("b.txt"), "b".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      maxDirectoryEntries = 1,
    )

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "LS"),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("1", result.metadata["entryCount"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("true", result.metadata["resultTruncated"])
    assertEquals("directory_entry_limit", result.metadata["resultLimitKind"])
  }

  @Test
  fun editFailsWhenTargetIsAmbiguousAndLeavesFileUntouched() {
    val workspaceRoot = temporaryFolder.newFolder("claude-edit-ambiguous").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "dup\ndup\n".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "Edit",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put("old_string", "dup")
          put("new_string", "single")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("ambiguous"))
    assertEquals("dup\ndup\n", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun multiEditFailureDoesNotPartiallyRewriteFile() {
    val workspaceRoot = temporaryFolder.newFolder("claude-multiedit-fail").toPath()
    val target = workspaceRoot.resolve("notes.txt")
    Files.write(target, "alpha\nbeta\n".toByteArray(StandardCharsets.UTF_8))
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot)

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "MultiEdit",
        arguments = buildJsonObject {
          put("file_path", "notes.txt")
          put(
            "edits",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("old_string", "alpha")
                  put("new_string", "ALPHA")
                },
              )
              add(
                buildJsonObject {
                  put("old_string", "missing")
                  put("new_string", "MISSING")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertTrue(result.content.contains("was not found"))
    assertEquals("alpha\nbeta\n", String(Files.readAllBytes(target), StandardCharsets.UTF_8))
  }

  @Test
  fun todoWriteReadsSharedSessionStateAcrossDispatchers() {
    val workspaceRoot = temporaryFolder.newFolder("claude-todo").toPath()
    val todoStore = InMemoryAgentTodoStore()
    val firstDispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)
    val secondDispatcher = dispatcher(workspaceRoot = workspaceRoot, todoStore = todoStore)

    val writeResult = firstDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "TodoWrite",
        arguments = buildJsonObject {
          put(
            "todos",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("content", "Implement Grep")
                  put("status", "completed")
                },
              )
              add(
                buildJsonObject {
                  put("content", "Implement TodoWrite")
                  put("status", "in_progress")
                  put("activeForm", "Implementing TodoWrite")
                },
              )
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )
    val readResult = secondDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(toolName = "TodoWrite", arguments = JsonObject(emptyMap())),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, writeResult.status)
    assertEquals(AgentToolResultStatus.SUCCESS, readResult.status)
    assertEquals("todo_management", writeResult.metadata["capabilityKind"])
    assertEquals("todo_management", readResult.metadata["capabilityKind"])
    assertEquals("none", readResult.metadata["workspaceRelation"])
    assertEquals("2 todo(s)", readResult.metadata["targetSummary"])
    assertEquals("2", readResult.metadata["todoCount"])
    assertTrue(readResult.content.contains("Implement Grep"))
    assertTrue(readResult.content.contains("Implementing TodoWrite"))
  }

  @Test
  fun bashUsesHostShellAndReturnsForegroundOutput() {
    val workspaceRoot = temporaryFolder.newFolder("claude-bash").toPath()
    val processRegistry = ClaudeBashProcessRegistry()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      processRegistry = processRegistry,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = buildJsonObject {
          put("command", "git status")
          put("timeout_ms", 250)
        },
      ),
      hooks = runtimeHooks(),
    )

    val request = processRegistry.startRequests.single()
    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(expectedShellExecutable(), request.command)
    assertEquals(expectedShellArgsPrefix(), request.args.take(expectedShellArgsPrefix().size))
    assertEquals("git status", request.args.last())
    assertEquals("bash", request.metadata["runtimeKind"])
    assertEquals(expectedShellKind(), request.metadata["shellKind"])
    assertEquals("git status", request.metadata["shellCommand"])
    assertTrue(result.content.contains("Shell command finished."))
    assertTrue(result.content.contains("shell_command=git status"))
    assertTrue(result.content.contains("[stdout]"))
    assertTrue(result.content.contains("clean workspace"))
    assertEquals("250", result.metadata["waitTimeoutMs"])
  }

  @Test
  fun bashReturnsRunningManagedProcessWhenInitialWaitExpires() {
    val workspaceRoot = temporaryFolder.newFolder("claude-bash-running").toPath()
    val processRegistry = ClaudeBashProcessRegistry(waitResultStatus = ManagedProcessStatus.RUNNING)
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      processRegistry = processRegistry,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "Bash",
        arguments = buildJsonObject {
          put("command", "npm run dev")
          put("timeout_ms", 50)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("RUNNING", result.metadata["processStatus"])
    assertTrue(result.content.contains("still running after waiting 50ms"))
    assertTrue(result.content.contains("ProcessRead, ProcessWait, or ProcessTerminate"))
  }

  @Test
  fun webFetchReturnsReadablePageContent() {
    val workspaceRoot = temporaryFolder.newFolder("claude-webfetch").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      webContentFetcher = FakeWebContentFetcher(),
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "WebFetch",
        arguments = buildJsonObject {
          put("url", "https://example.com/post")
          put("max_chars", 4000)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("title=Example article"))
    assertTrue(result.content.contains("url=https://example.com/post"))
    assertTrue(result.content.contains("Body text from the fetched page."))
    assertEquals("Example article", result.metadata["title"])
    assertEquals("4000", result.metadata["requestedMaxChars"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("false", result.metadata["resultTruncated"])
    assertEquals("web_fetch_char_limit", result.metadata["resultLimitKind"])
  }

  @Test
  fun webSearchUsesConfiguredProvider() {
    val workspaceRoot = temporaryFolder.newFolder("claude-websearch").toPath()
    val provider = FakeWebSearchProvider()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      webSearchProvider = provider,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "WebSearch",
        arguments = buildJsonObject {
          put("query", "opencray tools")
          put("max_results", 2)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertTrue(result.content.contains("provider=fake-search"))
    assertTrue(result.content.contains("OpenCray Tools Overview"))
    assertTrue(result.content.contains("https://example.com/opencray"))
    assertEquals("fake-search", result.metadata["providerName"])
    assertEquals("2", result.metadata["requestedMaxResults"])
    assertEquals("true", result.metadata["resultLimitApplied"])
    assertEquals("false", result.metadata["resultTruncated"])
    assertEquals("web_search_result_limit", result.metadata["resultLimitKind"])
    assertEquals("opencray tools", provider.requests.single().query)
  }

  @Test
  fun webSearchPassesRequestedDomainsToProviderAndMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("claude-websearch-domains").toPath()
    val provider = FakeWebSearchProvider()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      webSearchProvider = provider,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "WebSearch",
        arguments = buildJsonObject {
          put("query", "opencray tools")
          put(
            "domains",
            buildJsonArray {
              add(kotlinx.serialization.json.JsonPrimitive("docs.example.com"))
              add(kotlinx.serialization.json.JsonPrimitive("example.com"))
            },
          )
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals(listOf("docs.example.com", "example.com"), provider.requests.single().domains)
    assertEquals("docs.example.com,example.com", result.metadata["domains"])
    assertEquals("2", result.metadata["requestedDomainCount"])
  }

  @Test
  fun webSearchFailsCleanlyWhenProviderIsNotConfigured() {
    val workspaceRoot = temporaryFolder.newFolder("claude-websearch-unconfigured").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      webSearchProvider = UnconfiguredWebSearchProvider,
    )

    val result = dispatcher.dispatch(
      task = agentTask(metadata = mapOf("chatMode" to "DEVELOPER")),
      call = AgentToolCall(
        toolName = "WebSearch",
        arguments = buildJsonObject {
          put("query", "latest sdk news")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("WEB_SEARCH_NOT_CONFIGURED", result.errorCode)
    assertTrue(result.content.contains("not configured"))
  }

  private fun dispatcher(
    workspaceRoot: java.nio.file.Path = temporaryFolder.newFolder("claude-tool-workspace").toPath(),
    todoStore: AgentTodoStore = InMemoryAgentTodoStore(),
    processRegistry: AgentProcessRegistry = ClaudeBashProcessRegistry(),
    webContentFetcher: WebContentFetcher = FakeWebContentFetcher(),
    webSearchProvider: WebSearchProvider = FakeWebSearchProvider(),
    maxReadBytes: Int = 32_000,
    maxDirectoryEntries: Int = 200,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      todoStore = todoStore,
      processRegistry = processRegistry,
      webContentFetcher = webContentFetcher,
      webSearchProvider = webSearchProvider,
      maxReadBytes = maxReadBytes,
      maxDirectoryEntries = maxDirectoryEntries,
    ),
  )

  private fun agentTask(metadata: Map<String, String> = emptyMap()): AgentTask = AgentTask(
    id = "task-${System.nanoTime()}",
    type = AgentTaskType.TOOL_CALL,
    input = """{"type":"tool_call"}""",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "HOST_ALLOW",
    ),
    metadata = metadata,
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest -> error("Retry not expected in ClaudeStyleToolDispatchTest.") },
  )

  private fun expectedShellExecutable(): String = if (isWindows()) "powershell.exe" else "sh"

  private fun expectedShellArgsPrefix(): List<String> = if (isWindows()) {
    listOf("-NoLogo", "-NoProfile", "-Command")
  } else {
    listOf("-lc")
  }

  private fun expectedShellKind(): String = if (isWindows()) "powershell" else "sh"

  private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("win")

  private class ClaudeBashProcessRegistry(
    private val waitResultStatus: ManagedProcessStatus = ManagedProcessStatus.SUCCESS,
  ) : AgentProcessRegistry {
    val startRequests = mutableListOf<ManagedProcessStartRequest>()
    private val snapshotsById = linkedMapOf<String, ManagedProcessSnapshot>()

    override fun start(request: ManagedProcessStartRequest): ManagedProcessSnapshot {
      startRequests += request
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
      val waited = when (waitResultStatus) {
        ManagedProcessStatus.RUNNING -> existing.copy(
          updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        )

        ManagedProcessStatus.SUCCESS -> existing.copy(
          status = ManagedProcessStatus.SUCCESS,
          stdout = "clean workspace",
          exitCode = 0,
          updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
          finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        )

        else -> existing.copy(
          status = waitResultStatus,
          stderr = "process failed",
          exitCode = 1,
          errorCode = "EXEC_ERROR",
          errorMessage = "Process exited with code 1.",
          updatedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
          finishedAtEpochMs = existing.updatedAtEpochMs + timeoutMs,
        )
      }
      snapshotsById[processId] = waited
      return waited
    }

    override fun terminate(processId: String): ManagedProcessSnapshot? = snapshotsById[processId]
  }

  private class FakeWebContentFetcher : WebContentFetcher {
    override fun fetch(request: WebFetchRequest): WebFetchResult = WebFetchResult(
      requestedUrl = request.url,
      finalUrl = request.url,
      statusCode = 200,
      contentType = "text/html",
      title = "Example article",
      content = "Body text from the fetched page.",
    )
  }

  private class FakeWebSearchProvider : WebSearchProvider {
    override val providerName: String = "fake-search"
    val requests = mutableListOf<WebSearchRequest>()

    override fun search(request: WebSearchRequest): WebSearchResult {
      requests += request
      return WebSearchResult(
        providerName = providerName,
        results = listOf(
          WebSearchHit(
            title = "OpenCray Tools Overview",
            url = "https://example.com/opencray",
            snippet = "Overview of the current tool surface.",
          ),
          WebSearchHit(
            title = "OpenCray Runtime Notes",
            url = "https://example.com/runtime",
            snippet = "Recent runtime changes and caveats.",
          ),
        ).take(request.maxResults),
      )
    }
  }
}
