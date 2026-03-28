package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.PythonExecRequest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class E2BCodeInterpreterPythonRuntimeTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun execCreatesSandboxUploadsWorkspaceExecutesAndKillsEphemeralSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-success").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('ok')\n")
    writeFile(workspaceRoot.resolve("input.txt"), "hello\n")
    val deleteSandboxCount = AtomicInteger(0)
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-1","domain":"e2b.app","envdAccessToken":"envd-1","trafficAccessToken":"traffic-1"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-1.e2b.app/contexts" ->
            E2BResponse(
              statusCode = 200,
              body = """{"id":"ctx-1","language":"python","cwd":"/home/user/opencray/workspace/req-1"}""",
            )

          request.method == "DELETE" && request.url == "https://49999-sb-1.e2b.app/contexts/ctx-1" ->
            E2BResponse(statusCode = 204)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-1" -> {
            deleteSandboxCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { upload ->
        E2BResponse(
          statusCode = 200,
          body = """[{"path":"${upload.url}"}]""",
        )
      }
      streamHandler = { request, onLine ->
        assertEquals("https://49999-sb-1.e2b.app/execute", request.url)
        onLine("""{"type":"stdout","text":"python ok","timestamp":1}""")
        onLine("""{"type":"result","text":"ignored","is_main_result":true}""")
        E2BResponse(statusCode = 200)
      }
    }
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-success",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-1",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("python ok", result.stdout)
    assertEquals("e2b_code_interpreter", result.metadata["runtimeBackend"])
    assertEquals("sb-1", result.metadata["sandboxId"])
    assertEquals("/home/user/opencray/workspace/req-1", result.metadata["remoteWorkspaceRoot"])
    assertEquals("2", result.metadata["uploadedFiles"])
    assertEquals(1, deleteSandboxCount.get())
    assertEquals("Bearer secret-token", transport.requests.first().headers["Authorization"])
    assertTrue(transport.uploads.all { upload -> upload.headers["X-Access-Token"] == "envd-1" })
    assertTrue(
      transport.uploads.any { upload ->
        upload.url.contains("path=%2Fhome%2Fuser%2Fopencray%2Fworkspace%2Freq-1%2Fscripts%2Frun.py")
      },
    )
  }

  @Test
  fun execReconnectsStoredStickySandboxWithoutCreatingOrDeletingNewSandbox() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sticky").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('sticky')\n")
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sticky-1",
          sandboxDomain = "e2b.app",
          envdAccessToken = "stale-envd",
          trafficAccessToken = "stale-traffic",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
        ),
      )
    }
    val createCount = AtomicInteger(0)
    val deleteCount = AtomicInteger(0)
    val connectCount = AtomicInteger(0)
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" -> {
            createCount.incrementAndGet()
            error("Sticky run should reuse stored sandbox instead of creating a new one.")
          }

          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes/sticky-1/connect" -> {
            connectCount.incrementAndGet()
            E2BResponse(
              statusCode = 200,
              body = """{"sandboxID":"sticky-1","domain":"e2b.app","envdAccessToken":"envd-2","trafficAccessToken":"traffic-2"}""",
            )
          }

          request.method == "POST" && request.url == "https://49999-sticky-1.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-sticky","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sticky-1.e2b.app/contexts/ctx-sticky" ->
            E2BResponse(statusCode = 204)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sticky-1" -> {
            deleteCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      streamHandler = { _, onLine ->
        onLine("""{"type":"stdout","text":"sticky ok","timestamp":1}""")
        E2BResponse(statusCode = 200)
      }
    }
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.STICKY.wireValue,
        autoResume = true,
      ),
      transport = transport,
      sessionStore = sessionStore,
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-sticky",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-sticky",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals(0, createCount.get())
    assertEquals(1, connectCount.get())
    assertEquals(0, deleteCount.get())
    assertEquals("sticky-1", sessionStore.load()?.sandboxId)
    assertEquals("envd-2", sessionStore.load()?.envdAccessToken)
  }

  @Test
  fun execDownloadsChangedWorkspaceFilesBackToLocalWorkspaceWithoutApplyingRemoteDeletes() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-download").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('download')\n")
    writeFile(workspaceRoot.resolve("data.txt"), "before\n")
    writeFile(workspaceRoot.resolve("removed.txt"), "keep-local\n")
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-download","domain":"e2b.app","envdAccessToken":"envd-download","trafficAccessToken":"traffic-download"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-download.e2b.app/contexts" ->
            E2BResponse(
              statusCode = 200,
              body = """{"id":"ctx-download","language":"python","cwd":"/home/user/opencray/workspace/req-download"}""",
            )

          request.method == "DELETE" && request.url == "https://49999-sb-download.e2b.app/contexts/ctx-download" ->
            E2BResponse(statusCode = 204)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-download" ->
            E2BResponse(statusCode = 204)

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      downloadHandler = { request ->
        when {
          request.url.contains("path=%2Fhome%2Fuser%2Fopencray%2Fworkspace%2Freq-download%2Fdata.txt") ->
            E2BBinaryResponse(statusCode = 200, bodyBytes = "after\n".toByteArray(StandardCharsets.UTF_8))

          request.url.contains("path=%2Fhome%2Fuser%2Fopencray%2Fworkspace%2Freq-download%2Fartifacts%2Fresult.bin") ->
            E2BBinaryResponse(statusCode = 200, bodyBytes = byteArrayOf(1, 2, 3, 4))

          else -> error("Unexpected download ${request.url}")
        }
      }
      streamHandler = { request, onLine ->
        assertTrue(request.body.orEmpty().contains(WORKSPACE_SYNC_MANIFEST_PREFIX))
        onLine("""{"type":"stdout","text":"download ok","timestamp":1}""")
        onLine(
          """{"type":"stdout","text":"${
            syncManifestLine(
              RemoteWorkspaceDiffManifest(
                changedFiles = listOf("data.txt", "artifacts/result.bin"),
                deletedFiles = listOf("removed.txt"),
              ),
            )
          }","timestamp":2}""",
        )
        E2BResponse(statusCode = 200)
      }
    }
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-download",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-download",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("download ok", result.stdout)
    assertEquals(
      "after\n",
      String(Files.readAllBytes(workspaceRoot.resolve("data.txt")), StandardCharsets.UTF_8),
    )
    assertArrayEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(workspaceRoot.resolve("artifacts/result.bin")))
    assertEquals(
      "keep-local\n",
      String(Files.readAllBytes(workspaceRoot.resolve("removed.txt")), StandardCharsets.UTF_8),
    )
    assertEquals("2", result.metadata["downloadedFiles"])
    assertEquals("10", result.metadata["downloadedBytes"])
    assertEquals("2", result.metadata["remoteChangedFiles"])
    assertEquals("1", result.metadata["remoteDeletedFiles"])
    assertEquals("1", result.metadata["skippedRemoteDeletes"])
    assertEquals("true", result.metadata["workspaceSyncManifestObserved"])
    assertEquals("false", result.metadata["workspaceSyncManifestParseFailed"])
    val artifacts = OpenCrayAttachmentArtifacts.fromWorkspaceRelativePaths(
      listOf("data.txt", "artifacts/result.bin"),
    )
    assertEquals(
      artifacts.first().artifactId,
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_ID],
    )
    assertEquals(
      artifacts.first().relativePath,
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_RELATIVE_PATH],
    )
    assertNotNull(result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON])
    assertEquals(2, transport.downloads.size)
  }

  @Test
  fun requestCancellationKillsSandboxAndReturnsCancelledResult() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-cancel").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('cancel')\n")
    val streamStarted = CountDownLatch(1)
    val allowStreamFailure = CountDownLatch(1)
    val deleteSandboxCount = AtomicInteger(0)
    val resultRef = AtomicReference<ExecutionResult?>()
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-cancel","domain":"e2b.app","envdAccessToken":"envd-cancel","trafficAccessToken":"traffic-cancel"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-cancel.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-cancel","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sb-cancel.e2b.app/contexts/ctx-cancel" ->
            E2BResponse(statusCode = 404)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-cancel" -> {
            deleteSandboxCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      streamHandler = { _, _ ->
        streamStarted.countDown()
        allowStreamFailure.await(2, TimeUnit.SECONDS)
        throw IllegalStateException("sandbox terminated")
      }
    }
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
    )

    val executionThread = Thread {
      resultRef.set(
        runtime.exec(
          PythonExecRequest(
            taskId = "task-cancel",
            workspaceRoot = workspaceRoot,
            scriptPath = workspaceRoot.resolve("scripts/run.py"),
            timeoutMs = 30_000L,
            requestId = "req-cancel",
          ),
        ),
      )
    }
    executionThread.start()
    assertTrue(streamStarted.await(2, TimeUnit.SECONDS))

    assertTrue(runtime.requestCancellation("req-cancel"))
    allowStreamFailure.countDown()
    executionThread.join(2_000L)

    val result = resultRef.get()
    assertNotNull(result)
    assertEquals(ExecutionStatus.CANCELLED, result!!.status)
    assertEquals(E2BCodeInterpreterPythonRuntime.ERROR_E2B_CANCELLED, result.errorCode)
    assertTrue(deleteSandboxCount.get() >= 1)
  }

  @Test
  fun execDeniesScriptPathEscapeWithoutCallingTransport() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-deny").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('deny')\n")
    val transport = FakeE2BTransport()
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-deny",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("../outside.py"),
        timeoutMs = 30_000L,
        requestId = "req-deny",
      ),
    )

    assertEquals(ExecutionStatus.DENIED, result.status)
    assertEquals(E2BCodeInterpreterPythonRuntime.ERROR_SCRIPT_PATH_NOT_ALLOWED, result.errorCode)
    assertTrue(transport.requests.isEmpty())
    assertTrue(transport.uploads.isEmpty())
    assertTrue(transport.streamRequests.isEmpty())
  }

  private fun runtime(
    state: SandboxSettingsState,
    transport: E2BTransport,
    sessionStore: E2BSandboxSessionStore,
  ): E2BCodeInterpreterPythonRuntime = E2BCodeInterpreterPythonRuntime(
    settingsProvider = {
      ResolvedSandboxSettings(
        state = state.copy(
          enabled = true,
          providerId = SandboxProviderId.E2B.wireValue,
          e2bApiKeyCredentialRef = SandboxSettingsRepository.E2B_API_KEY_REF.uri,
        ),
        e2bApiKey = "secret-token",
      )
    },
    sessionStore = sessionStore,
    transport = transport,
  )

  private fun writeFile(
    path: Path,
    content: String,
  ) {
    Files.createDirectories(path.parent)
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
  }

  private fun syncManifestLine(manifest: RemoteWorkspaceDiffManifest): String {
    val payload = """
      {"changedFiles":${manifest.changedFiles.toJsonArray()},"deletedFiles":${manifest.deletedFiles.toJsonArray()}}
    """.trimIndent()
    val encoded = Base64.getEncoder().encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    return WORKSPACE_SYNC_MANIFEST_PREFIX + encoded
  }

  private fun List<String>.toJsonArray(): String =
    joinToString(prefix = "[", postfix = "]") { value ->
      "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

  private class FakeE2BTransport : E2BTransport {
    val requests = mutableListOf<E2BRequest>()
    val uploads = mutableListOf<E2BUploadRequest>()
    val downloads = mutableListOf<E2BDownloadRequest>()
    val streamRequests = mutableListOf<E2BRequest>()

    var requestHandler: (E2BRequest) -> E2BResponse = {
      error("Unexpected request ${it.method} ${it.url}")
    }
    var uploadHandler: (E2BUploadRequest) -> E2BResponse = {
      error("Unexpected upload ${it.url}")
    }
    var downloadHandler: (E2BDownloadRequest) -> E2BBinaryResponse = {
      error("Unexpected download ${it.url}")
    }
    var streamHandler: (E2BRequest, (String) -> Unit) -> E2BResponse = { request, _ ->
      error("Unexpected stream ${request.method} ${request.url}")
    }

    override fun request(request: E2BRequest): E2BResponse {
      requests += request
      return requestHandler(request)
    }

    override fun upload(request: E2BUploadRequest): E2BResponse {
      uploads += request
      return uploadHandler(request)
    }

    override fun download(request: E2BDownloadRequest): E2BBinaryResponse {
      downloads += request
      return downloadHandler(request)
    }

    override fun stream(
      request: E2BRequest,
      onLine: (String) -> Unit,
    ): E2BResponse {
      streamRequests += request
      return streamHandler(request, onLine)
    }
  }
}
