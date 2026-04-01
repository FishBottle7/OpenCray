package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.ExecutionStatus
import com.opencray.runtime.OpenCrayAttachmentArtifactMetadataKeys
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.PythonExecRequest
import com.opencray.runtime.SandboxSessionCloseOutcome
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

  private val json: Json = Json { ignoreUnknownKeys = true }

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
  fun stickyExecUsesIncrementalUploadAndStableRemoteWorkspaceRootOnSecondRun() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sticky-incremental").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('sticky incremental')\n")
    writeFile(workspaceRoot.resolve("input.txt"), "before\n")
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    val transport = FakeE2BTransport()
    val createCount = AtomicInteger(0)
    val connectCount = AtomicInteger(0)
    val contextCount = AtomicInteger(0)
    transport.requestHandler = { request ->
      when {
        request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" -> {
          createCount.incrementAndGet()
          E2BResponse(
            statusCode = 201,
            body = """{"sandboxID":"sb-sticky-incremental","domain":"e2b.app","envdAccessToken":"envd-1","trafficAccessToken":"traffic-1"}""",
          )
        }

        request.method == "POST" &&
          request.url == "https://api.e2b.app/sandboxes/sb-sticky-incremental/connect" -> {
            connectCount.incrementAndGet()
            E2BResponse(
              statusCode = 200,
              body = """{"sandboxID":"sb-sticky-incremental","domain":"e2b.app","envdAccessToken":"envd-2","trafficAccessToken":"traffic-2"}""",
            )
          }

        request.method == "POST" && request.url == "https://49999-sb-sticky-incremental.e2b.app/contexts" ->
          E2BResponse(
            statusCode = 200,
            body = """{"id":"ctx-${contextCount.incrementAndGet()}","language":"python","cwd":"/x"}""",
          )

        request.method == "DELETE" &&
          request.url.startsWith("https://49999-sb-sticky-incremental.e2b.app/contexts/ctx-") ->
          E2BResponse(statusCode = 204)

        request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-sticky-incremental" ->
          error("Sticky session should not be deleted after a successful reusable run.")

        else -> error("Unexpected request ${request.method} ${request.url}")
      }
    }
    transport.uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
    transport.streamHandler = { _, onLine ->
      onLine("""{"type":"stdout","text":"sticky incremental ok","timestamp":1}""")
      E2BResponse(statusCode = 200)
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
    val expectedRemoteWorkspaceRoot = "/home/user/opencray/workspace-sticky/sb-sticky-incremental"

    val firstResult = runtime.exec(
      PythonExecRequest(
        taskId = "task-sticky-incremental-1",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-sticky-incremental-1",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstResult.status)
    assertEquals(expectedRemoteWorkspaceRoot, firstResult.metadata["remoteWorkspaceRoot"])
    assertEquals("full", firstResult.metadata["workspaceUploadMode"])
    assertEquals("2", firstResult.metadata["uploadedFiles"])
    assertEquals(expectedRemoteWorkspaceRoot, sessionStore.load()?.remoteWorkspaceRoot)
    val firstRunUploadUrls = transport.uploads.map(E2BUploadRequest::url)
    assertEquals(2, firstRunUploadUrls.size)
    assertTrue(firstRunUploadUrls.all { url -> url.contains("path=%2Fhome%2Fuser%2Fopencray%2Fworkspace-sticky%2Fsb-sticky-incremental%2F") })

    Thread.sleep(25L)
    writeFile(workspaceRoot.resolve("input.txt"), "after\n")

    val secondResult = runtime.exec(
      PythonExecRequest(
        taskId = "task-sticky-incremental-2",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-sticky-incremental-2",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, secondResult.status)
    assertEquals(expectedRemoteWorkspaceRoot, secondResult.metadata["remoteWorkspaceRoot"])
    assertEquals("incremental", secondResult.metadata["workspaceUploadMode"])
    assertEquals("1", secondResult.metadata["uploadedFiles"])
    assertEquals("1", secondResult.metadata["workspaceUnchangedFiles"])
    assertEquals(expectedRemoteWorkspaceRoot, sessionStore.load()?.remoteWorkspaceRoot)
    assertTrue(Files.isRegularFile(workspaceRoot.resolve(".opencray/sandbox-sync/e2b-workspace-sync-state.json")))
    val secondRunUploadUrls = transport.uploads
      .drop(firstRunUploadUrls.size)
      .map(E2BUploadRequest::url)
    assertEquals(1, secondRunUploadUrls.size)
    assertTrue(secondRunUploadUrls.single().contains("path=%2Fhome%2Fuser%2Fopencray%2Fworkspace-sticky%2Fsb-sticky-incremental%2Finput.txt"))
    assertFalse(secondRunUploadUrls.single().contains("run.py"))
    assertEquals(1, createCount.get())
    assertEquals(1, connectCount.get())
  }

  @Test
  fun stickyExecReplaysPendingRemoteDeletesBeforeRunningUserScript() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-sticky-remote-delete-replay").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('remote delete replay')\n")
    writeFile(workspaceRoot.resolve("obsolete.txt"), "delete me\n")
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    val transport = FakeE2BTransport()
    val executeBodies = mutableListOf<String>()
    transport.requestHandler = { request ->
      when {
        request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
          E2BResponse(
            statusCode = 201,
            body = """{"sandboxID":"sb-remote-delete","domain":"e2b.app","envdAccessToken":"envd-1","trafficAccessToken":"traffic-1"}""",
          )

        request.method == "POST" && request.url == "https://api.e2b.app/sandboxes/sb-remote-delete/connect" ->
          E2BResponse(
            statusCode = 200,
            body = """{"sandboxID":"sb-remote-delete","domain":"e2b.app","envdAccessToken":"envd-2","trafficAccessToken":"traffic-2"}""",
          )

        request.method == "POST" && request.url == "https://49999-sb-remote-delete.e2b.app/contexts" ->
          E2BResponse(statusCode = 200, body = """{"id":"ctx-remote-delete","language":"python","cwd":"/x"}""")

        request.method == "DELETE" && request.url == "https://49999-sb-remote-delete.e2b.app/contexts/ctx-remote-delete" ->
          E2BResponse(statusCode = 204)

        request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-remote-delete" ->
          error("Sticky session should remain reusable after remote delete replay.")

        else -> error("Unexpected request ${request.method} ${request.url}")
      }
    }
    transport.uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
    transport.streamHandler = { request, onLine ->
      executeBodies += request.body.orEmpty()
      onLine("""{"type":"stdout","text":"remote delete replay ok","timestamp":1}""")
      E2BResponse(statusCode = 200)
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

    val firstResult = runtime.exec(
      PythonExecRequest(
        taskId = "task-remote-delete-replay-1",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-remote-delete-replay-1",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, firstResult.status)
    assertTrue(Files.deleteIfExists(workspaceRoot.resolve("obsolete.txt")))

    val secondResult = runtime.exec(
      PythonExecRequest(
        taskId = "task-remote-delete-replay-2",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-remote-delete-replay-2",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, secondResult.status)
    assertEquals("incremental", secondResult.metadata["workspaceUploadMode"])
    assertEquals("1", secondResult.metadata["workspacePendingRemoteDeleteFiles"])
    assertEquals(2, executeBodies.size)
    val replayCode = json.parseToJsonElement(executeBodies.last()).jsonObject["code"]?.jsonPrimitive?.content.orEmpty()
    assertTrue(replayCode.contains("pending_remote_delete_paths = [\"obsolete.txt\"]"))
    assertTrue(replayCode.contains("replay_pending_remote_deletes(workspace_root, pending_remote_delete_paths)"))
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
    assertEquals("2", result.metadata["archivedArtifactFiles"])
    assertEquals("10", result.metadata["archivedArtifactBytes"])
    assertEquals(".opencray/sandbox-downloads/req-download", result.metadata["sandboxDownloadArchiveRoot"])
    assertEquals("0", result.metadata["sandboxDownloadArchivePrunedDirectories"])
    assertEquals("0", result.metadata["sandboxDownloadArchivePrunedBytes"])
    assertEquals("1", result.metadata["sandboxDownloadArchiveRetainedDirectories"])
    assertEquals("true", result.metadata["workspaceSyncManifestObserved"])
    assertEquals("false", result.metadata["workspaceSyncManifestParseFailed"])
    assertEquals(
      "after\n",
      String(
        Files.readAllBytes(workspaceRoot.resolve(".opencray/sandbox-downloads/req-download/data.txt")),
        StandardCharsets.UTF_8,
      ),
    )
    assertArrayEquals(
      byteArrayOf(1, 2, 3, 4),
      Files.readAllBytes(workspaceRoot.resolve(".opencray/sandbox-downloads/req-download/artifacts/result.bin")),
    )
    val artifacts = OpenCrayAttachmentArtifacts.fromWorkspaceRelativePaths(
      listOf(
        ".opencray/sandbox-downloads/req-download/data.txt",
        ".opencray/sandbox-downloads/req-download/artifacts/result.bin",
      ),
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
  fun execPrunesOldDownloadArchivesWhenRequestDirectoryLimitIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-archive-retention-count").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('archive retention count')\n")
    createArchivedFile(
      workspaceRoot = workspaceRoot,
      requestId = "req-old-1",
      relativePath = "artifact.txt",
      content = "old1",
      modifiedAtEpochMs = 1_000L,
    )
    createArchivedFile(
      workspaceRoot = workspaceRoot,
      requestId = "req-old-2",
      relativePath = "artifact.txt",
      content = "old2",
      modifiedAtEpochMs = 2_000L,
    )
    val transport = downloadTransport(
      sandboxId = "sb-archive-retention-count",
      requestId = "req-retention-count",
      changedFiles = listOf("artifacts/result.txt"),
      changedFileBodies = mapOf("artifacts/result.txt" to "new"),
    )
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      downloadArchiveRetentionPolicy = E2BDownloadArchiveRetentionPolicy(
        maxRequestDirectories = 2,
        maxTotalBytes = 1_024L,
      ),
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-archive-retention-count",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-retention-count",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertFalse(Files.exists(workspaceRoot.resolve(".opencray/sandbox-downloads/req-old-1")))
    assertTrue(Files.exists(workspaceRoot.resolve(".opencray/sandbox-downloads/req-old-2")))
    assertTrue(Files.exists(workspaceRoot.resolve(".opencray/sandbox-downloads/req-retention-count")))
    assertEquals("1", result.metadata["sandboxDownloadArchivePrunedDirectories"])
    assertEquals("2", result.metadata["sandboxDownloadArchiveRetainedDirectories"])
  }

  @Test
  fun execPrunesOldDownloadArchivesWhenTotalByteLimitIsExceeded() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-archive-retention-size").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('archive retention size')\n")
    createArchivedFile(
      workspaceRoot = workspaceRoot,
      requestId = "req-old-size",
      relativePath = "artifact.bin",
      content = "12345678",
      modifiedAtEpochMs = 1_000L,
    )
    val transport = downloadTransport(
      sandboxId = "sb-archive-retention-size",
      requestId = "req-retention-size",
      changedFiles = listOf("artifacts/result.txt"),
      changedFileBodies = mapOf("artifacts/result.txt" to "abcde"),
    )
    val runtime = runtime(
      state = SandboxSettingsState(
        enabled = true,
        sessionMode = SandboxSessionMode.EPHEMERAL.wireValue,
      ),
      transport = transport,
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
      downloadArchiveRetentionPolicy = E2BDownloadArchiveRetentionPolicy(
        maxRequestDirectories = 10,
        maxTotalBytes = 8L,
      ),
    )

    val result = runtime.exec(
      PythonExecRequest(
        taskId = "task-archive-retention-size",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-retention-size",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertFalse(Files.exists(workspaceRoot.resolve(".opencray/sandbox-downloads/req-old-size")))
    assertTrue(Files.exists(workspaceRoot.resolve(".opencray/sandbox-downloads/req-retention-size")))
    assertEquals("1", result.metadata["sandboxDownloadArchivePrunedDirectories"])
    assertEquals("8", result.metadata["sandboxDownloadArchivePrunedBytes"])
    assertEquals("1", result.metadata["sandboxDownloadArchiveRetainedDirectories"])
    assertEquals("5", result.metadata["sandboxDownloadArchiveRetainedBytes"])
  }

  @Test
  fun stickyExecDiscoversPreviewCandidatePortsFromSandboxOutput() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-preview-port-discovery").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('preview')\n")
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-preview-ports","domain":"e2b.app","envdAccessToken":"envd-preview","trafficAccessToken":"traffic-preview"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-preview-ports.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-preview","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sb-preview-ports.e2b.app/contexts/ctx-preview" ->
            E2BResponse(statusCode = 204)

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      streamHandler = { _, onLine ->
        onLine("""{"type":"stdout","text":"App listening on http://127.0.0.1:4173","timestamp":1}""")
        onLine("""{"type":"stderr","text":"started server on 3000","timestamp":2}""")
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
        taskId = "task-preview-ports",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-preview-ports",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("3000,4173", result.metadata["sandboxPreviewCandidatePorts"])
    assertEquals(listOf(3000, 4173), sessionStore.load()?.previewCandidatePorts)
    assertEquals(listOf(3000, 4173), runtime.activeStickySessionSnapshot()?.previewCandidatePorts)
  }

  @Test
  fun stickyExecPreservesStoredPreviewCandidatePortsWhenExecutionDoesNotEmitNewOnes() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-preview-port-preserve").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('preview')\n")
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()).apply {
      save(
        E2BSandboxSessionSnapshot(
          sandboxId = "sticky-preview-1",
          sandboxDomain = "e2b.app",
          envdAccessToken = "stale-envd",
          trafficAccessToken = "stale-traffic",
          workspaceRoot = workspaceRoot.toString(),
          templateId = E2BCodeInterpreterPythonRuntime.DEFAULT_TEMPLATE_ID,
          updatedAtEpochMs = 1L,
          previewCandidatePorts = listOf(4173),
        ),
      )
    }
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes/sticky-preview-1/connect" ->
            E2BResponse(
              statusCode = 200,
              body = """{"sandboxID":"sticky-preview-1","domain":"e2b.app","envdAccessToken":"envd-2","trafficAccessToken":"traffic-2"}""",
            )

          request.method == "POST" && request.url == "https://49999-sticky-preview-1.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-preview-preserve","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sticky-preview-1.e2b.app/contexts/ctx-preview-preserve" ->
            E2BResponse(statusCode = 204)

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
        taskId = "task-preview-preserve",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-preview-preserve",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, result.status)
    assertEquals("4173", result.metadata["sandboxPreviewCandidatePorts"])
    assertEquals(listOf(4173), sessionStore.load()?.previewCandidatePorts)
    assertEquals(listOf(4173), runtime.activeStickySessionSnapshot()?.previewCandidatePorts)
  }

  @Test
  fun closeReusableSessionTerminatesStickySessionAndClearsResumeSnapshot() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-close-session").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('close')\n")
    val deleteCount = AtomicInteger(0)
    val sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore())
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-close","domain":"e2b.app","envdAccessToken":"envd-close","trafficAccessToken":"traffic-close"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-close.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-close","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sb-close.e2b.app/contexts/ctx-close" ->
            E2BResponse(statusCode = 204)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-close" -> {
            deleteCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      streamHandler = { _, onLine ->
        onLine("""{"type":"stdout","text":"close ok","timestamp":1}""")
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

    val execResult = runtime.exec(
      PythonExecRequest(
        taskId = "task-close-session",
        workspaceRoot = workspaceRoot,
        scriptPath = workspaceRoot.resolve("scripts/run.py"),
        timeoutMs = 30_000L,
        requestId = "req-close-session",
      ),
    )

    assertEquals(ExecutionStatus.SUCCESS, execResult.status)
    val activeSession = runtime.activeStickySessionSnapshot()
    assertNotNull(activeSession)
    assertEquals("sb-close", sessionStore.load()?.sandboxId)

    val closeResult = runtime.closeReusableSession(requireNotNull(activeSession))

    assertEquals(SandboxSessionCloseOutcome.TERMINATED, closeResult.outcome)
    assertEquals("sb-close", closeResult.sandboxId)
    assertEquals(1, deleteCount.get())
    assertEquals(null, runtime.activeStickySessionSnapshot())
    assertEquals(null, sessionStore.load())
  }

  @Test
  fun closeReusableSessionReturnsBusyWhenMatchingRequestIsStillRunning() {
    val workspaceRoot = temporaryFolder.newFolder("workspace-close-session-busy").toPath()
    writeFile(workspaceRoot.resolve("scripts/run.py"), "print('close')\n")
    val streamStarted = CountDownLatch(1)
    val allowStreamFinish = CountDownLatch(1)
    val deleteCount = AtomicInteger(0)
    val transport = FakeE2BTransport().apply {
      requestHandler = { request ->
        when {
          request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
            E2BResponse(
              statusCode = 201,
              body = """{"sandboxID":"sb-close-busy","domain":"e2b.app","envdAccessToken":"envd-close","trafficAccessToken":"traffic-close"}""",
            )

          request.method == "POST" && request.url == "https://49999-sb-close-busy.e2b.app/contexts" ->
            E2BResponse(statusCode = 200, body = """{"id":"ctx-close-busy","language":"python","cwd":"/x"}""")

          request.method == "DELETE" && request.url == "https://49999-sb-close-busy.e2b.app/contexts/ctx-close-busy" ->
            E2BResponse(statusCode = 204)

          request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/sb-close-busy" -> {
            deleteCount.incrementAndGet()
            E2BResponse(statusCode = 204)
          }

          else -> error("Unexpected request ${request.method} ${request.url}")
        }
      }
      uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
      streamHandler = { _, onLine ->
        streamStarted.countDown()
        assertTrue(allowStreamFinish.await(2, TimeUnit.SECONDS))
        onLine("""{"type":"stdout","text":"close busy ok","timestamp":1}""")
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
      sessionStore = E2BSandboxSessionStore(InMemoryE2BSandboxSessionKeyValueStore()),
    )
    val resultRef = AtomicReference<ExecutionResult?>()

    val executionThread = Thread {
      resultRef.set(
        runtime.exec(
          PythonExecRequest(
            taskId = "task-close-session-busy",
            workspaceRoot = workspaceRoot,
            scriptPath = workspaceRoot.resolve("scripts/run.py"),
            timeoutMs = 30_000L,
            requestId = "req-close-session-busy",
          ),
        ),
      )
    }
    executionThread.start()
    assertTrue(streamStarted.await(2, TimeUnit.SECONDS))
    val activeSession = runtime.activeStickySessionSnapshot()
    assertNotNull(activeSession)

    val closeResult = runtime.closeReusableSession(requireNotNull(activeSession))

    assertEquals(SandboxSessionCloseOutcome.BUSY, closeResult.outcome)
    assertEquals("req-close-session-busy", closeResult.blockingRequestId)
    assertEquals(0, deleteCount.get())

    allowStreamFinish.countDown()
    executionThread.join(2_000L)
    assertEquals(ExecutionStatus.SUCCESS, resultRef.get()?.status)
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
    downloadArchiveRetentionPolicy: E2BDownloadArchiveRetentionPolicy = E2BDownloadArchiveRetentionPolicy(),
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
    downloadArchiveRetentionPolicy = downloadArchiveRetentionPolicy,
    transport = transport,
  )

  private fun writeFile(
    path: Path,
    content: String,
  ) {
    Files.createDirectories(path.parent)
    Files.write(path, content.toByteArray(StandardCharsets.UTF_8))
  }

  private fun createArchivedFile(
    workspaceRoot: Path,
    requestId: String,
    relativePath: String,
    content: String,
    modifiedAtEpochMs: Long,
  ) {
    val requestDirectory = workspaceRoot
      .resolve(".opencray")
      .resolve("sandbox-downloads")
      .resolve(requestId)
    val archivePath = workspaceRoot
      .resolve(".opencray")
      .resolve("sandbox-downloads")
      .resolve(requestId)
      .resolve(relativePath.replace('/', java.io.File.separatorChar))
    writeFile(archivePath, content)
    val modifiedAt = FileTime.fromMillis(modifiedAtEpochMs)
    Files.setLastModifiedTime(archivePath, modifiedAt)
    archivePath.parent?.let { parent ->
      Files.setLastModifiedTime(parent, modifiedAt)
    }
    Files.setLastModifiedTime(requestDirectory, modifiedAt)
  }

  private fun downloadTransport(
    sandboxId: String,
    requestId: String,
    changedFiles: List<String>,
    changedFileBodies: Map<String, String>,
  ): FakeE2BTransport = FakeE2BTransport().apply {
    requestHandler = { request ->
      when {
        request.method == "POST" && request.url == "https://api.e2b.app/sandboxes" ->
          E2BResponse(
            statusCode = 201,
            body = """{"sandboxID":"$sandboxId","domain":"e2b.app","envdAccessToken":"envd","trafficAccessToken":"traffic"}""",
          )

        request.method == "POST" && request.url == "https://49999-$sandboxId.e2b.app/contexts" ->
          E2BResponse(
            statusCode = 200,
            body = """{"id":"ctx-$requestId","language":"python","cwd":"/home/user/opencray/workspace/$requestId"}""",
          )

        request.method == "DELETE" && request.url == "https://49999-$sandboxId.e2b.app/contexts/ctx-$requestId" ->
          E2BResponse(statusCode = 204)

        request.method == "DELETE" && request.url == "https://api.e2b.app/sandboxes/$sandboxId" ->
          E2BResponse(statusCode = 204)

        else -> error("Unexpected request ${request.method} ${request.url}")
      }
    }
    uploadHandler = { E2BResponse(statusCode = 200, body = "[]") }
    downloadHandler = { request ->
      val matched = changedFileBodies.entries.firstOrNull { (relativePath, _) ->
        request.url.contains(
          "path=%2Fhome%2Fuser%2Fopencray%2Fworkspace%2F$requestId%2F${
            relativePath.replace("/", "%2F")
          }",
        )
      } ?: error("Unexpected download ${request.url}")
      E2BBinaryResponse(
        statusCode = 200,
        bodyBytes = matched.value.toByteArray(StandardCharsets.UTF_8),
      )
    }
    streamHandler = { _, onLine ->
      onLine("""{"type":"stdout","text":"download ok","timestamp":1}""")
      onLine(
        """{"type":"stdout","text":"${
          syncManifestLine(
            RemoteWorkspaceDiffManifest(
              changedFiles = changedFiles,
              deletedFiles = emptyList(),
            ),
          )
        }","timestamp":2}""",
      )
      E2BResponse(statusCode = 200)
    }
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
