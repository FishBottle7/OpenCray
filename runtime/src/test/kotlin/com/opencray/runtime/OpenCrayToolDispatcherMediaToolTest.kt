package com.opencray.runtime

import com.opencray.core.contracts.AgentTask
import com.opencray.core.contracts.AgentTaskType
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.contracts.PolicyDecisionOutcome
import com.opencray.core.orchestrator.RetryRequest
import com.opencray.core.orchestrator.RuntimeExecutionHooks
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

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "GenerateImage",
        arguments = buildJsonObject {
          put("prompt", "Draw a test banner")
          put("count", 2)
          put("size", "1024x1024")
        },
      ),
      hooks = runtimeHooks(),
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

    val result = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "SynthesizeSpeech",
        arguments = buildJsonObject {
          put("text", "Summarize the rollout status.")
          put("format", "m4a")
        },
      ),
      hooks = runtimeHooks(),
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

    val startResult = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "GenerateVideo",
        arguments = buildJsonObject {
          put("prompt", "A calm aerial harbor shot")
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, startResult.status)
    assertEquals("true", startResult.metadata["jobPending"])
    val jobId = startResult.metadata["jobId"]
    assertNotNull(jobId)
    val encodedPayload = String(
      Base64.getUrlDecoder().decode(jobId!!.removePrefix("provider_media_job:")),
      StandardCharsets.UTF_8,
    )
    assertTrue(encodedPayload.contains("providerPollUrl"))
    assertTrue(!encodedPayload.contains("providerInternalSecret"))
    assertTrue(!encodedPayload.contains("promptPreview"))
    assertTrue(!encodedPayload.contains("outputDirectory"))
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
    val pollResult = recoveredDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
      hooks = runtimeHooks(),
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

    val startResult = dispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "GenerateVideo",
        arguments = buildJsonObject {
          put("prompt", "A looping city timelapse")
        },
      ),
      hooks = runtimeHooks(),
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

    val cancelResult = recoveredDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "CancelMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, cancelResult.status)
    assertEquals("cancelled", cancelResult.metadata["jobStatus"])
    val pollResult = recoveredDispatcher.dispatch(
      task = agentTask(),
      call = AgentToolCall(
        toolName = "PollMediaJob",
        arguments = buildJsonObject {
          put("job_id", jobId)
        },
      ),
      hooks = runtimeHooks(),
    )

    assertEquals(AgentToolResultStatus.SUCCESS, pollResult.status)
    assertEquals("cancelled", pollResult.metadata["jobStatus"])
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
    ),
  )

  private fun agentTask(): AgentTask = AgentTask(
    id = "task-media-tool",
    type = AgentTaskType.PROMPT,
    input = "Generate media.",
    policyDecision = PolicyDecision(
      outcome = PolicyDecisionOutcome.ALLOW,
      reasonCode = "TEST_ALLOW",
    ),
    metadata = mapOf("mode" to "DEVELOPER"),
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
