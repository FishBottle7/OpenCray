package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.media.encodeProviderMediaJobId
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenCrayToolDispatcherMediaToolTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun generateImageWritesWorkspaceArtifactsAndPublishesAttachmentMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("media-image-workspace").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      imageGenerationClient = object : OpenCrayImageGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse {
          assertEquals("Draw a test banner", request.prompt)
          assertEquals(2, request.count)
          assertEquals("1024x1024", request.size)
          return OpenCrayImageGenerationResponse(
            images = listOf(
              OpenCrayBinaryAsset(
                bytes = byteArrayOf(1, 2, 3),
                mimeType = "image/png",
              ),
              OpenCrayBinaryAsset(
                bytes = byteArrayOf(4, 5, 6),
                mimeType = "image/png",
              ),
            ),
            providerRequestId = "img_req_1",
          )
        }
      },
    )

    val result = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "GenerateImage",
        arguments = buildJsonObject {
          put("prompt", "Draw a test banner")
          put("count", 2)
          put("size", "1024x1024")
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("inside_workspace", result.metadata["workspaceRelation"])
    assertEquals("2", result.metadata["imageCount"])
    val descriptors = Json.decodeFromString(
      ListSerializer(OpenCrayAttachmentArtifact.serializer()),
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON].orEmpty(),
    )
    assertEquals(2, descriptors.size)
    descriptors.forEach { descriptor ->
      assertTrue(descriptor.artifactId.startsWith("artifact-image-"))
      assertTrue(descriptor.relativePath.startsWith(".opencray/generated-media/images/"))
      assertEquals("image", descriptor.kindHint)
      assertEquals("image/png", descriptor.mimeType)
      assertTrue(Files.exists(workspaceRoot.resolve(descriptor.relativePath)))
    }
  }

  @Test
  fun synthesizeSpeechPublishesVoiceArtifactAndTranscriptMetadata() {
    val workspaceRoot = temporaryFolder.newFolder("media-speech-workspace").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      speechSynthesisClient = object : OpenCraySpeechSynthesisClient {
        override fun synthesize(
          request: OpenCraySpeechSynthesisRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCraySpeechSynthesisResponse {
          assertEquals("Summarize the rollout status.", request.text)
          assertEquals("m4a", request.format)
          return OpenCraySpeechSynthesisResponse(
            audio = OpenCrayBinaryAsset(
              bytes = byteArrayOf(7, 8, 9),
              mimeType = "audio/mp4",
            ),
            providerRequestId = "speech_req_1",
            durationMs = 3_200L,
            transcriptText = "Summarize the rollout status.",
          )
        }
      },
    )

    val result = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "SynthesizeSpeech",
        arguments = buildJsonObject {
          put("text", "Summarize the rollout status.")
          put("format", "m4a")
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, result.status)
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals("audio/mp4", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_MIME_TYPE])
    assertEquals("3200", result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_DURATION_MS])
    assertEquals(
      "Summarize the rollout status.",
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACT_TRANSCRIPT_TEXT],
    )
    val descriptors = Json.decodeFromString(
      ListSerializer(OpenCrayAttachmentArtifact.serializer()),
      result.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON].orEmpty(),
    )
    val descriptor = descriptors.single()
    assertEquals("voice", descriptor.kindHint)
    assertEquals(3_200L, descriptor.durationMs)
    assertEquals("Summarize the rollout status.", descriptor.transcriptText)
    assertTrue(descriptor.relativePath.startsWith(".opencray/generated-media/voices/"))
    assertTrue(Files.exists(workspaceRoot.resolve(descriptor.relativePath)))
  }

  @Test
  fun generateVideoReturnsPendingJobAndPollCompletesWithArtifact() {
    val workspaceRoot = temporaryFolder.newFolder("media-video-workspace").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      mediaGenerationClient = object : MediaGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse = error("Image generation not expected.")

        override fun generateVideo(
          request: OpenCrayVideoGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayVideoGenerationResponse {
          return OpenCrayVideoGenerationResponse(
            pendingJob = OpenCrayMediaJobSnapshot(
              receipt = OpenCrayMediaJobReceipt(
                jobId = "provider-video-job-1",
                toolName = "GenerateVideo",
                status = OpenCrayMediaJobStatus.PENDING,
                pollAfterMs = 250L,
              ),
              metadata = mapOf(
                "providerPollUrl" to "https://media.example.com/jobs/provider-video-job-1",
                "providerCancelUrl" to "https://media.example.com/jobs/provider-video-job-1/cancel",
                "providerInternalSecret" to "top-secret",
              ),
            ),
          )
        }

        override fun poll(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobPollResult {
          assertEquals("provider-video-job-1", job.receipt.jobId)
          return OpenCrayMediaJobPollResult(
            snapshot = job.copy(
              receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.COMPLETED),
            ),
            videos = listOf(
              OpenCrayBinaryAsset(
                bytes = byteArrayOf(1, 2, 3, 4),
                mimeType = "video/mp4",
                fileName = "clip.mp4",
              ),
            ),
          )
        }
      },
    )

    val startResult = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "GenerateVideo",
        arguments = buildJsonObject {
          put("prompt", "A calm aerial harbor shot")
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals("true", startResult.metadata["jobPending"])
    val jobId = startResult.metadata["jobId"]
    assertNotNull(jobId)
    val encodedToken = jobId!!.removePrefix("provider_media_job:")
    assertTrue(encodedToken.contains('.'))
    val encodedPayload = encodedToken.substringBefore('.')
    val decodedPayload = String(
      Base64.getUrlDecoder().decode(encodedPayload),
      StandardCharsets.UTF_8,
    )
    assertTrue(decodedPayload.contains("providerPollUrl"))
    assertTrue(!decodedPayload.contains("providerInternalSecret"))
    assertTrue(!decodedPayload.contains("promptPreview"))
    assertTrue(!decodedPayload.contains("outputDirectory"))
    val recoveredDispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      mediaGenerationClient = object : MediaGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse = error("Image generation not expected.")

        override fun generateVideo(
          request: OpenCrayVideoGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayVideoGenerationResponse = error("Video generation not expected.")

        override fun poll(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobPollResult {
          assertEquals("provider-video-job-1", job.receipt.jobId)
          return OpenCrayMediaJobPollResult(
            snapshot = job.copy(
              receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.COMPLETED),
            ),
            videos = listOf(
              OpenCrayBinaryAsset(
                bytes = byteArrayOf(1, 2, 3, 4),
                mimeType = "video/mp4",
                fileName = "clip.mp4",
              ),
            ),
          )
        }
      },
    )
    val pollResult = dispatchScoped(
      task = agentTask(),
      dispatcher = recoveredDispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, pollResult.status)
    assertEquals("completed", pollResult.metadata["jobStatus"])
    val descriptors = Json.decodeFromString(
      ListSerializer(OpenCrayAttachmentArtifact.serializer()),
      pollResult.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON].orEmpty(),
    )
    val descriptor = descriptors.single()
    assertEquals("video/mp4", descriptor.mimeType)
    assertTrue(descriptor.relativePath.startsWith(".opencray/generated-media/videos/"))
    assertTrue(Files.exists(workspaceRoot.resolve(descriptor.relativePath)))
  }

  @Test
  fun cancelMediaJobStopsRunningVideoGenerationJob() {
    val workspaceRoot = temporaryFolder.newFolder("media-video-cancel-workspace").toPath()
    val dispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      mediaGenerationClient = object : MediaGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse = error("Image generation not expected.")

        override fun generateVideo(
          request: OpenCrayVideoGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayVideoGenerationResponse {
          return OpenCrayVideoGenerationResponse(
            pendingJob = OpenCrayMediaJobSnapshot(
              receipt = OpenCrayMediaJobReceipt(
                jobId = "provider-video-job-2",
                toolName = "GenerateVideo",
                status = OpenCrayMediaJobStatus.PENDING,
              ),
              metadata = mapOf(
                "providerPollUrl" to "https://media.example.com/jobs/provider-video-job-2",
                "providerCancelUrl" to "https://media.example.com/jobs/provider-video-job-2/cancel",
              ),
            ),
          )
        }

        override fun cancel(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobSnapshot {
          assertEquals("provider-video-job-2", job.receipt.jobId)
          return job.copy(
            receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
          )
        }

        override fun poll(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobPollResult {
          return OpenCrayMediaJobPollResult(
            snapshot = job.copy(
              receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
            ),
          )
        }
      },
    )

    val startResult = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "GenerateVideo",
        arguments = buildJsonObject {
          put("prompt", "A looping city timelapse")
        },
      ),
    )
    val jobId = startResult.metadata["jobId"]!!

    val recoveredDispatcher = dispatcher(
      workspaceRoot = workspaceRoot,
      mediaGenerationClient = object : MediaGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse = error("Image generation not expected.")

        override fun generateVideo(
          request: OpenCrayVideoGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayVideoGenerationResponse = error("Video generation not expected.")

        override fun cancel(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobSnapshot = job.copy(
          receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
        )

        override fun poll(
          job: OpenCrayMediaJobSnapshot,
          settings: OpenCrayMediaToolSettings,
          cancellationRequested: () -> Boolean,
        ): OpenCrayMediaJobPollResult = OpenCrayMediaJobPollResult(
          snapshot = job.copy(
            receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
          ),
        )
      },
    )

    val cancelResult = dispatchScoped(
      task = agentTask(),
      dispatcher = recoveredDispatcher,
      call = AgentToolCall(
        toolName = "CancelMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, cancelResult.status)
    assertEquals("cancelled", cancelResult.metadata["jobStatus"])
    val pollResult = dispatchScoped(
      task = agentTask(),
      dispatcher = recoveredDispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, pollResult.status)
    assertEquals("cancelled", pollResult.metadata["jobStatus"])
  }

  @Test
  fun publishMediaArtifactCopiesRegisteredArtifactAcrossDispatchers() {
    val workspaceRoot = temporaryFolder.newFolder("media-publish-workspace").toPath()
    val generator = dispatcher(
      workspaceRoot = workspaceRoot,
      imageGenerationClient = object : OpenCrayImageGenerationClient {
        override fun generate(
          request: OpenCrayImageGenerationRequest,
          cancellationRequested: () -> Boolean,
        ): OpenCrayImageGenerationResponse = OpenCrayImageGenerationResponse(
          images = listOf(
            OpenCrayBinaryAsset(
              bytes = byteArrayOf(10, 20, 30),
              mimeType = "image/png",
            ),
          ),
        )
      },
    )
    val generated = generator.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "GenerateImage",
        arguments = buildJsonObject {
          put("prompt", "Draw a reusable icon")
        },
      ),
      hooks = runtimeHooks(),
    )
    val generatedDescriptor = Json.decodeFromString(
      ListSerializer(OpenCrayAttachmentArtifact.serializer()),
      generated.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON].orEmpty(),
    ).single()
    val publisher = dispatcher(workspaceRoot = workspaceRoot)

    val published = publisher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "PublishMediaArtifact",
        arguments = buildJsonObject {
          put("artifact_id", generatedDescriptor.artifactId)
          put("relative_path", "exports/icon.png")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, published.status)
    assertEquals("write_file", published.metadata["capabilityKind"])
    assertTrue(Files.exists(workspaceRoot.resolve(generatedDescriptor.relativePath)))
    val destination = workspaceRoot.resolve("exports/icon.png")
    assertTrue(Files.exists(destination))
    assertEquals(listOf(10.toByte(), 20.toByte(), 30.toByte()), Files.readAllBytes(destination).toList())
    val publishedDescriptor = Json.decodeFromString(
      ListSerializer(OpenCrayAttachmentArtifact.serializer()),
      published.metadata[OpenCrayAttachmentArtifactMetadataKeys.ARTIFACTS_JSON].orEmpty(),
    ).single()
    assertEquals("exports/icon.png", publishedDescriptor.relativePath)
    assertEquals("image", publishedDescriptor.kindHint)
  }

  @Test
  fun publishMediaArtifactFailsWhenDestinationAlreadyExists() {
    val workspaceRoot = temporaryFolder.newFolder("media-publish-conflict-workspace").toPath()
    Files.createDirectories(workspaceRoot.resolve(".opencray/generated-media/images"))
    Files.write(workspaceRoot.resolve(".opencray/generated-media/images/source.png"), byteArrayOf(1, 2, 3))
    Files.createDirectories(workspaceRoot.resolve("exports"))
    Files.write(workspaceRoot.resolve("exports/icon.png"), byteArrayOf(9))
    val artifact = OpenCrayAttachmentArtifact(
      artifactId = "artifact-source-image",
      relativePath = ".opencray/generated-media/images/source.png",
      displayName = "source.png",
      kindHint = "image",
      mimeType = "image/png",
    )
    defaultOpenCrayMediaArtifactRegistry(workspaceRoot).register(
      artifacts = listOf(artifact),
      source = OpenCrayMediaArtifactSource(
        runId = "run-source",
        toolName = "GenerateImage",
        source = "generated",
      ),
    )
    val publisher = dispatcher(workspaceRoot = workspaceRoot)

    val result = publisher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "PublishMediaArtifact",
        arguments = buildJsonObject {
          put("artifact_id", artifact.artifactId)
          put("relative_path", "exports/icon.png")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("ILLEGAL_ARGUMENT", result.errorCode)
    assertEquals(listOf(9.toByte()), Files.readAllBytes(workspaceRoot.resolve("exports/icon.png")).toList())
  }

  @Test
  fun mediaArtifactRegistryRejectsPathsOutsideWorkspace() {
    val workspaceRoot = temporaryFolder.newFolder("media-registry-boundary-workspace").toPath()
    val registry = defaultOpenCrayMediaArtifactRegistry(workspaceRoot)

    registry.register(
      artifacts = listOf(
        OpenCrayAttachmentArtifact(
          artifactId = "artifact-escape",
          relativePath = "../outside.png",
          displayName = "outside.png",
          kindHint = "image",
          mimeType = "image/png",
        ),
      ),
      source = OpenCrayMediaArtifactSource(
        runId = "run-escape",
        toolName = "GenerateImage",
        source = "generated",
      ),
    )

    assertNull(registry.resolve("artifact-escape"))
  }

  @Test
  fun pollMediaJobRejectsTamperedJobIdSignatureWithoutNetworkRequests() {
    val workspaceRoot = temporaryFolder.newFolder("media-forge-workspace").toPath()
    val client = CountingMediaClient()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, imageGenerationClient = client)
    val snapshot = providerVideoSnapshot(
      jobId = "provider-video-forged-1",
      pollUrl = "https://media.example.com/jobs/provider-video-forged-1",
      cancelUrl = "https://media.example.com/jobs/provider-video-forged-1/cancel",
    )
    val legitimateJobId = dispatcher.encodeProviderMediaJobId(snapshot)
    val tamperedJobId = legitimateJobId.dropLast(3) + "AAA"

    val result = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject { put("job_id", tamperedJobId) },
      ),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MEDIA_JOB_ID_INVALID", result.errorCode)
    assertTrue(result.content.contains("signature verification failed"))
    assertEquals(0, client.pollCalls)
    assertEquals(0, client.cancelCalls)
  }

  @Test
  fun pollMediaJobRejectsLegacyUnsignedJobIdWithoutNetworkRequests() {
    val workspaceRoot = temporaryFolder.newFolder("media-legacy-workspace").toPath()
    val client = CountingMediaClient()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, imageGenerationClient = client)
    val legacyPayload = """
      {"v":1,"toolName":"GenerateVideo","providerJobId":"legacy-job","status":"pending",
       "pollAfterMs":1000,"metadata":{"providerPollUrl":"https://attacker.example.com/jobs/legacy-job"}}
    """.trimIndent()
    val legacyJobId = "provider_media_job:" + Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(legacyPayload.toByteArray(StandardCharsets.UTF_8))

    val result = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject { put("job_id", legacyJobId) },
      ),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MEDIA_JOB_ID_INVALID", result.errorCode)
    assertTrue(result.content.contains("no longer accepted"))
    assertEquals(0, client.pollCalls)
  }

  @Test
  fun pollMediaJobIsDeniedByTaskPolicyWithDecisionMetadataAndNoNetworkRequests() {
    val workspaceRoot = temporaryFolder.newFolder("media-deny-workspace").toPath()
    val client = CountingMediaClient()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, imageGenerationClient = client)
    val snapshot = providerVideoSnapshot(
      jobId = "provider-video-denied-1",
      pollUrl = "https://media.example.com/jobs/provider-video-denied-1",
      cancelUrl = null,
    )
    val jobId = dispatcher.encodeProviderMediaJobId(snapshot)
    val deniedTask = agentTask(
      policyDecision = PolicyDecision(
        outcome = PolicyDecisionOutcome.DENY,
        reasonCode = "TEST_DENY_NETWORK",
      ),
    )

    val result = dispatchScoped(
      task = deniedTask,
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject { put("job_id", jobId) },
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("DENY_POLICY", result.errorCode)
    assertEquals("DENY", result.metadata["policyOutcome"])
    assertEquals("TEST_DENY_NETWORK", result.metadata["policyReasonCode"])
    assertEquals("network_access", result.metadata["capabilityKind"])
    assertEquals(0, client.pollCalls)
  }

  @Test
  fun cancelMediaJobRequiresApprovalInSafeModeWithoutNetworkRequests() {
    val workspaceRoot = temporaryFolder.newFolder("media-ask-workspace").toPath()
    val client = CountingMediaClient()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, imageGenerationClient = client)
    val snapshot = providerVideoSnapshot(
      jobId = "provider-video-approval-1",
      pollUrl = "https://media.example.com/jobs/provider-video-approval-1",
      cancelUrl = "https://media.example.com/jobs/provider-video-approval-1/cancel",
    )
    val jobId = dispatcher.encodeProviderMediaJobId(snapshot)

    val result = dispatchScoped(
      task = agentTask(mode = "SAFE"),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "CancelMediaJob",
        arguments = buildJsonObject { put("job_id", jobId) },
      ),
    )

    assertEquals(AgentToolResultStatus.DENIED, result.status)
    assertEquals("HIGH_RISK_APPROVAL_REQUIRED", result.errorCode)
    assertEquals("ASK", result.metadata["policyOutcome"])
    assertEquals("HIGH_RISK", result.metadata["approvalRisk"])
    assertEquals(0, client.cancelCalls)
    assertEquals(0, client.pollCalls)
  }

  @Test
  fun pollMediaJobRejectsCrossOriginRegisteredUrlBeforeAnyNetworkRequest() {
    val workspaceRoot = temporaryFolder.newFolder("media-cross-origin-workspace").toPath()
    val client = CountingMediaClient()
    val dispatcher = dispatcher(workspaceRoot = workspaceRoot, imageGenerationClient = client)
    val snapshot = providerVideoSnapshot(
      jobId = "provider-video-cross-origin-1",
      pollUrl = "https://attacker.example.com/jobs/provider-video-cross-origin-1",
      cancelUrl = null,
    )
    val jobId = dispatcher.encodeProviderMediaJobId(snapshot)

    val result = dispatchScoped(
      task = agentTask(),
      dispatcher = dispatcher,
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject { put("job_id", jobId) },
      ),
    )

    assertEquals(AgentToolResultStatus.FAILED, result.status)
    assertEquals("MEDIA_JOB_ORIGIN_MISMATCH", result.errorCode)
    assertTrue(result.content.contains("attacker.example.com"))
    assertEquals(0, client.pollCalls)
  }

  private fun providerVideoSnapshot(
    jobId: String,
    pollUrl: String?,
    cancelUrl: String?,
  ): OpenCrayMediaJobSnapshot = OpenCrayMediaJobSnapshot(
    receipt = OpenCrayMediaJobReceipt(
      jobId = jobId,
      toolName = "GenerateVideo",
      status = OpenCrayMediaJobStatus.PENDING,
    ),
    metadata = buildMap {
      pollUrl?.let { put("providerPollUrl", it) }
      cancelUrl?.let { put("providerCancelUrl", it) }
    },
  )

  private class CountingMediaClient :
    OpenCrayImageGenerationClient,
    OpenCrayVideoGenerationClient,
    OpenCrayMediaJobClient {
    var generateCalls = 0
    var pollCalls = 0
    var cancelCalls = 0
    var pendingVideoJob: OpenCrayMediaJobSnapshot? = null
    var pollResponse: OpenCrayMediaJobPollResult? = null
    var cancelResponse: OpenCrayMediaJobSnapshot? = null

    override fun generate(
      request: OpenCrayImageGenerationRequest,
      cancellationRequested: () -> Boolean,
    ): OpenCrayImageGenerationResponse = error("Image generation not expected.")

    override fun generateVideo(
      request: OpenCrayVideoGenerationRequest,
      cancellationRequested: () -> Boolean,
    ): OpenCrayVideoGenerationResponse {
      generateCalls++
      return OpenCrayVideoGenerationResponse(pendingJob = pendingVideoJob)
    }

    override fun poll(
      job: OpenCrayMediaJobSnapshot,
      settings: OpenCrayMediaToolSettings,
      cancellationRequested: () -> Boolean,
    ): OpenCrayMediaJobPollResult {
      pollCalls++
      return pollResponse ?: error("Poll response not configured.")
    }

    override fun cancel(
      job: OpenCrayMediaJobSnapshot,
      settings: OpenCrayMediaToolSettings,
      cancellationRequested: () -> Boolean,
    ): OpenCrayMediaJobSnapshot {
      cancelCalls++
      return cancelResponse ?: job.copy(
        receipt = job.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
      )
    }
  }

  private fun dispatcher(
    workspaceRoot: Path,
    imageGenerationClient: OpenCrayImageGenerationClient? = null,
    speechSynthesisClient: OpenCraySpeechSynthesisClient? = null,
    mediaGenerationClient: MediaGenerationClient? = null,
  ): OpenCrayToolDispatcher = OpenCrayToolDispatcher(
    OpenCrayToolDispatcherConfig(
      workspaceRoots = setOf(workspaceRoot),
      mediaToolSettingsProvider = {
        OpenCrayMediaToolSettings(
          imageGeneration = OpenCrayImageGenerationSettings(
            provider = "Test Images",
            baseUrl = "https://media.example.com",
            endpoint = "/v1/images",
            model = "test-image-model",
          ),
          videoGeneration = OpenCrayVideoGenerationSettings(
            provider = "Test Video",
            baseUrl = "https://media.example.com",
            endpoint = "/v1/videos",
            model = "test-video-model",
          ),
          speechSynthesis = OpenCraySpeechSynthesisSettings(
            provider = "Test Speech",
            baseUrl = "https://media.example.com",
            endpoint = "/v1/speech",
            defaultVoice = "alloy",
          ),
        )
      },
      imageGenerationClient = mediaGenerationClient ?: imageGenerationClient,
      speechSynthesisClient = speechSynthesisClient,
      mediaArtifactRegistry = defaultOpenCrayMediaArtifactRegistry(workspaceRoot),
    ),
  )

  private fun dispatchScoped(
    task: AgentTask,
    dispatcher: OpenCrayToolDispatcher,
    call: AgentToolCall,
  ): AgentToolResult = DispatchTaskScope.withCurrentTask(task) {
    dispatcher.dispatch(task = task, call = call, hooks = runtimeHooks())
  }

  private fun agentTask(
    mode: String = "DEVELOPER",
    policyDecision: PolicyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
  ): AgentTask = AgentTask(
    id = "task-media-tool",
    type = AgentTaskType.PROMPT,
    input = "Generate media.",
    policyDecision = policyDecision,
    metadata = mapOf("mode" to mode),
    createdAtEpochMs = 1_000L,
  )

  private fun runtimeHooks(): RuntimeExecutionHooks = RuntimeExecutionHooks(
    isCancellationRequested = { false },
    requestRetry = { _: RetryRequest ->
      error("Retry not expected in OpenCrayToolDispatcherMediaToolTest.")
    },
  )

  private interface MediaGenerationClient :
    OpenCrayImageGenerationClient,
    OpenCrayVideoGenerationClient,
    OpenCrayMediaJobClient {
    override fun poll(
      job: OpenCrayMediaJobSnapshot,
      settings: OpenCrayMediaToolSettings,
      cancellationRequested: () -> Boolean,
    ): OpenCrayMediaJobPollResult = error("Poll not expected.")

    override fun cancel(
      job: OpenCrayMediaJobSnapshot,
      settings: OpenCrayMediaToolSettings,
      cancellationRequested: () -> Boolean,
    ): OpenCrayMediaJobSnapshot = error("Cancel not expected.")
  }
}
