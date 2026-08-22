package com.opencray.runtime.media

import com.opencray.core.contracts.AgentTask
import com.opencray.runtime.AgentToolResult
import com.opencray.runtime.AgentToolResultStatus
import com.opencray.runtime.OpenCrayAttachmentArtifacts
import com.opencray.runtime.OpenCrayBinaryAsset
import com.opencray.runtime.OpenCrayGeneratedWorkspaceArtifact
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayImageGenerationRequest
import com.opencray.runtime.OpenCrayImageGenerationSettings
import com.opencray.runtime.OpenCrayMediaJobClient
import com.opencray.runtime.OpenCrayMediaJobPollResult
import com.opencray.runtime.OpenCrayMediaJobReceipt
import com.opencray.runtime.OpenCrayMediaJobSnapshot
import com.opencray.runtime.OpenCrayMediaJobStatus
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisRequest
import com.opencray.runtime.OpenCraySpeechSynthesisSettings
import com.opencray.runtime.OpenCrayToolDispatcher
import com.opencray.runtime.OpenCrayVideoGenerationClient
import com.opencray.runtime.OpenCrayVideoGenerationRequest
import com.opencray.runtime.OpenCrayVideoGenerationSettings
import com.opencray.runtime.inlinePreview
import com.opencray.runtime.optionalBoolean
import com.opencray.runtime.optionalInt
import com.opencray.runtime.optionalString
import com.opencray.runtime.requiredStringFrom
import com.opencray.runtime.requiredText
import com.opencray.runtime.policy.ToolMetadataContextRequest
import com.opencray.runtime.policy.ToolPolicyPlan
import com.opencray.runtime.policy.ToolTargetKind
import com.opencray.runtime.policy.ToolWorkspaceRelation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val DEFAULT_GENERATED_IMAGE_FORMAT: String = "png"
internal const val DEFAULT_GENERATED_VIDEO_FORMAT: String = "mp4"
internal const val DEFAULT_GENERATED_AUDIO_FORMAT: String = "mp3"
internal const val PROVIDER_MEDIA_JOB_ID_PREFIX: String = "provider_media_job:"
internal val ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS: Set<String> = setOf(
  "providerPollUrl",
  "providerCancelUrl",
)
internal const val MAX_GENERATED_IMAGE_COUNT: Int = 9
internal const val MAX_GENERATED_VIDEO_DURATION_SECONDS: Int = 60

internal fun OpenCrayToolDispatcher.generateImage(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.imageGeneration
      ?: return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation is not configured. Set provider base URL, endpoint, and model first.",
      )
    }
    val client = config.imageGenerationClient
      ?: return unavailableMediaTool(
        toolName = "GenerateImage",
        message = "Image generation provider support is unavailable on this runtime.",
      )
    val prompt = arguments.requiredText("prompt").trim()
    require(prompt.isNotBlank()) { "GenerateImage prompt must not be blank." }
    val count = arguments.optionalInt("count")?.coerceIn(1, MAX_GENERATED_IMAGE_COUNT) ?: 1
    val format = normalizeGeneratedImageFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_IMAGE_FORMAT
    val size = arguments.optionalString("size")?.trim()?.takeIf(String::isNotBlank)
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val runAsync = arguments.optionalBoolean("async") == true
    val outputDirectory = generatedMediaDirectory("images")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "GenerateImage",
      targetPath = outputDirectory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = outputDirectory,
        primaryTargetPath = toolTargetResolver.displayWritablePath(outputDirectory),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = inlinePreview(prompt, maxChars = 240),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("provider", settings.provider)
        put("endpoint", endpoint)
        put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      },
      askDetail = "Approval is required before GenerateImage can access the network.",
      denyDetail = "Policy denied GenerateImage.",
    )?.let { return it }
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("promptPreview", inlinePreview(prompt, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      size?.let { put("size", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeImageGeneration(
      client = client,
      settings = settings,
      prompt = prompt,
      count = count,
      size = size,
      format = format,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

internal fun OpenCrayToolDispatcher.generateVideo(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.videoGeneration
      ?: return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation is not configured. Set provider base URL, endpoint, and model first.",
      )
    }
    val client = videoGenerationClient
      ?: return unavailableMediaTool(
        toolName = "GenerateVideo",
        message = "Video generation provider support is unavailable on this runtime.",
      )
    val prompt = arguments.requiredText("prompt").trim()
    require(prompt.isNotBlank()) { "GenerateVideo prompt must not be blank." }
    val durationSeconds = arguments.optionalInt("duration_seconds")?.coerceIn(1, MAX_GENERATED_VIDEO_DURATION_SECONDS)
    val size = arguments.optionalString("size")?.trim()?.takeIf(String::isNotBlank)
    val format = normalizeGeneratedVideoFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_VIDEO_FORMAT
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val runAsync = arguments.optionalBoolean("async") ?: true
    val outputDirectory = generatedMediaDirectory("videos")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "GenerateVideo",
      targetPath = outputDirectory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = outputDirectory,
        primaryTargetPath = toolTargetResolver.displayWritablePath(outputDirectory),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = inlinePreview(prompt, maxChars = 240),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("provider", settings.provider)
        put("endpoint", endpoint)
        put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      },
      askDetail = "Approval is required before GenerateVideo can access the network.",
      denyDetail = "Policy denied GenerateVideo.",
    )?.let { return it }
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("promptPreview", inlinePreview(prompt, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      durationSeconds?.let { put("durationSeconds", it.toString()) }
      size?.let { put("size", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeVideoGeneration(
      client = client,
      settings = settings,
      prompt = prompt,
      durationSeconds = durationSeconds,
      size = size,
      format = format,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

internal fun OpenCrayToolDispatcher.synthesizeSpeech(
    task: AgentTask,
    arguments: JsonObject,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()?.speechSynthesis
      ?: return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis settings are unavailable on this runtime.",
      )
    if (!settings.isConfigured()) {
      return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis is not configured. Set provider base URL, endpoint, and default voice first.",
      )
    }
    val client = config.speechSynthesisClient
      ?: return unavailableMediaTool(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis provider support is unavailable on this runtime.",
      )
    val text = arguments.requiredText("text").trim()
    require(text.isNotBlank()) { "SynthesizeSpeech text must not be blank." }
    val format = normalizeGeneratedAudioFormat(arguments.optionalString("format")) ?: DEFAULT_GENERATED_AUDIO_FORMAT
    val voiceOverride = arguments.optionalString("voice")?.trim()?.takeIf(String::isNotBlank)
    val modelOverride = arguments.optionalString("model")?.trim()?.takeIf(String::isNotBlank)
    val runAsync = arguments.optionalBoolean("async") == true
    val outputDirectory = generatedMediaDirectory("voices")
    val endpoint = buildConfiguredEndpointPreview(
      baseUrl = settings.baseUrl,
      endpoint = settings.endpoint,
    )
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "SynthesizeSpeech",
      targetPath = outputDirectory,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        primaryPath = outputDirectory,
        primaryTargetPath = toolTargetResolver.displayWritablePath(outputDirectory),
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        targetSummary = inlinePreview(text, maxChars = 240),
      ),
    )
    toolPolicyPipeline.gate(
      plan = plan,
      affectedPaths = buildMap {
        put("provider", settings.provider)
        put("endpoint", endpoint)
        put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      },
      askDetail = "Approval is required before SynthesizeSpeech can access the network.",
      denyDetail = "Policy denied SynthesizeSpeech.",
    )?.let { return it }
    val baseMetadata = buildMap {
      put("provider", settings.provider)
      put("endpoint", endpoint)
      put("textPreview", inlinePreview(text, maxChars = 240))
      put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
      put("format", format)
      put("asyncCapable", "true")
      put("asyncRequested", runAsync.toString())
      voiceOverride?.let { put("voiceOverride", it) }
      modelOverride?.let { put("modelOverride", it) }
    }
    return executeSpeechSynthesis(
      client = client,
      settings = settings,
      text = text,
      format = format,
      voiceOverride = voiceOverride,
      modelOverride = modelOverride,
      preferAsync = runAsync,
      outputDirectory = outputDirectory,
      plan = plan,
      endpoint = endpoint,
      baseMetadata = baseMetadata,
      cancellationRequested = hooks.isCancellationRequested,
    )
  }

internal fun OpenCrayToolDispatcher.pollMediaJob(arguments: JsonObject): AgentToolResult {
    val jobId = arguments.requiredText("job_id").trim()
    require(jobId.isNotBlank()) { "PollMediaJob job_id must not be blank." }
    decodeProviderMediaJobId(jobId)?.let { providerSnapshot ->
      return pollProviderMediaJob(
        externalJobId = jobId,
        snapshot = providerSnapshot,
      )
    }
    val handle = synchronized(mediaJobCoordinator.jobs) { mediaJobCoordinator.jobs[jobId] }
      ?: return missingMediaJobResult(toolName = "PollMediaJob", jobId = jobId)
    if (!handle.future.isDone) {
      return mediaJobPendingResult(toolName = "PollMediaJob", handle = handle)
    }
    val finalResult = try {
      handle.future.get()
    } catch (_: CancellationException) {
      cancelledMediaJobTerminalResult(handle)
    } catch (exception: Throwable) {
      failedMediaJobTerminalResult(
        toolName = handle.toolName,
        message = exception.cause?.message ?: exception.message ?: "Background media job failed.",
      )
    }
    return when (finalResult.status) {
      AgentToolResultStatus.SUCCESS -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.SUCCESS,
        content = buildString {
          appendLine("Media job completed.")
          appendLine("job_id=${handle.jobId}")
          appendLine()
          append(finalResult.content)
        }.trim(),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = finalResult.metadata + mediaJobMetadata(
            handle = handle,
            status = OpenCrayMediaJobStatus.COMPLETED,
          ),
        ),
      )

      AgentToolResultStatus.CANCELLED -> mediaJobCancelledObservationResult(handle)
      else -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = finalResult.content,
        errorCode = finalResult.errorCode ?: "MEDIA_JOB_FAILED",
        errorMessage = finalResult.errorMessage ?: finalResult.content,
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = finalResult.metadata + mediaJobMetadata(
            handle = handle,
            status = OpenCrayMediaJobStatus.FAILED,
          ),
        ),
      )
    }
  }

internal fun OpenCrayToolDispatcher.cancelMediaJob(arguments: JsonObject): AgentToolResult {
    val jobId = arguments.requiredText("job_id").trim()
    require(jobId.isNotBlank()) { "CancelMediaJob job_id must not be blank." }
    decodeProviderMediaJobId(jobId)?.let { providerSnapshot ->
      return cancelProviderMediaJob(
        externalJobId = jobId,
        snapshot = providerSnapshot,
      )
    }
    val handle = synchronized(mediaJobCoordinator.jobs) { mediaJobCoordinator.jobs[jobId] }
      ?: return missingMediaJobResult(toolName = "CancelMediaJob", jobId = jobId)
    val alreadyDone = handle.future.isDone
    if (!alreadyDone) {
      handle.cancelRequested.set(true)
      handle.future.cancel(true)
    }
    val status = if (handle.future.isDone) {
      if (alreadyDone) {
        OpenCrayMediaJobStatus.COMPLETED
      } else {
        OpenCrayMediaJobStatus.CANCELLED
      }
    } else {
      OpenCrayMediaJobStatus.PENDING
    }
    return AgentToolResult(
      toolName = "CancelMediaJob",
      status = AgentToolResultStatus.SUCCESS,
      content = when (status) {
        OpenCrayMediaJobStatus.CANCELLED ->
          "Cancellation requested for media job.\njob_id=${handle.jobId}"

        OpenCrayMediaJobStatus.COMPLETED ->
          "Media job already completed.\njob_id=${handle.jobId}"

        else ->
          "Media job cancellation is pending.\njob_id=${handle.jobId}"
      },
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "CancelMediaJob",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NETWORK,
          workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        ),
        metadata = handle.baseMetadata + mediaJobMetadata(
          handle = handle,
          status = status,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.executeImageGeneration(
    client: OpenCrayImageGenerationClient,
    settings: OpenCrayImageGenerationSettings,
    prompt: String,
    count: Int,
    size: String?,
    format: String,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.generate(
        request = OpenCrayImageGenerationRequest(
          prompt = prompt,
          count = count,
          size = size,
          format = format,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "GenerateImage",
        message = "Image generation was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
    require(response.images.isNotEmpty()) { "Image provider returned no images." }
    require(response.images.size <= MAX_GENERATED_IMAGE_COUNT) {
      "Image provider returned too many images (${response.images.size})."
    }

    val batchId = UUID.randomUUID().toString().replace("-", "").take(12)
    val artifacts = response.images.mapIndexed { index, asset ->
      writeGeneratedWorkspaceArtifact(
        directory = outputDirectory,
        stem = buildString {
          append("image-")
          append(batchId)
          if (response.images.size > 1) {
            append("-")
            append(index + 1)
          }
        },
        requestedExtension = format,
        defaultExtension = DEFAULT_GENERATED_IMAGE_FORMAT,
        asset = asset,
        kindHint = "image",
      )
    }

    return AgentToolResult(
      toolName = "GenerateImage",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedImageResultContent(artifacts = artifacts),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("promptPreview", inlinePreview(prompt, maxChars = 240))
          put("imageCount", artifacts.size.toString())
          put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
          put("format", format)
          size?.let { put("size", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(artifacts))
          putAll(response.metadata)
        },
      ),
    )
  }

internal fun OpenCrayToolDispatcher.executeVideoGeneration(
    client: OpenCrayVideoGenerationClient,
    settings: OpenCrayVideoGenerationSettings,
    prompt: String,
    durationSeconds: Int?,
    size: String?,
    format: String,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.generateVideo(
        request = OpenCrayVideoGenerationRequest(
          prompt = prompt,
          durationSeconds = durationSeconds,
          size = size,
          format = format,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "GenerateVideo",
        message = "Video generation was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
    require(response.videos.isNotEmpty()) { "Video provider returned no videos." }
    val batchId = UUID.randomUUID().toString().replace("-", "").take(12)
    val artifacts = response.videos.mapIndexed { index, asset ->
      writeGeneratedWorkspaceArtifact(
        directory = outputDirectory,
        stem = buildString {
          append("video-")
          append(batchId)
          if (response.videos.size > 1) {
            append("-")
            append(index + 1)
          }
        },
        requestedExtension = format,
        defaultExtension = DEFAULT_GENERATED_VIDEO_FORMAT,
        asset = asset,
        kindHint = "file",
      )
    }
    return AgentToolResult(
      toolName = "GenerateVideo",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedVideoResultContent(artifacts = artifacts),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("promptPreview", inlinePreview(prompt, maxChars = 240))
          put("videoCount", artifacts.size.toString())
          put("outputDirectory", toolTargetResolver.displayWritablePath(outputDirectory))
          put("format", format)
          durationSeconds?.let { put("durationSeconds", it.toString()) }
          size?.let { put("size", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(artifacts))
          putAll(response.metadata)
        },
      ),
    )
  }

internal fun OpenCrayToolDispatcher.executeSpeechSynthesis(
    client: OpenCraySpeechSynthesisClient,
    settings: OpenCraySpeechSynthesisSettings,
    text: String,
    format: String,
    voiceOverride: String?,
    modelOverride: String?,
    preferAsync: Boolean,
    outputDirectory: Path,
    plan: ToolPolicyPlan,
    endpoint: String,
    baseMetadata: Map<String, String>,
    cancellationRequested: () -> Boolean,
  ): AgentToolResult {
    val response = try {
      client.synthesize(
        request = OpenCraySpeechSynthesisRequest(
          text = text,
          format = format,
          voiceOverride = voiceOverride,
          modelOverride = modelOverride,
          preferAsync = preferAsync,
          settings = settings,
        ),
        cancellationRequested = cancellationRequested,
      )
    } catch (_: CancellationException) {
      return cancelledMediaToolResult(
        toolName = "SynthesizeSpeech",
        message = "Speech synthesis was cancelled.",
        metadata = mapOf(
          "provider" to settings.provider,
          "endpoint" to endpoint,
        ),
      )
    }
    response.pendingJob?.let { pendingJob ->
      return providerPendingMediaJobResult(
        plan = plan,
        snapshot = pendingJob,
        metadata = response.metadata + baseMetadata,
      )
    }
    val audio = requireNotNull(response.audio) { "Speech provider returned no audio payload." }
    val transcriptText = response.transcriptText
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: text.takeIf(String::isNotBlank)
    val artifact = writeGeneratedWorkspaceArtifact(
      directory = outputDirectory,
      stem = "voice-${UUID.randomUUID().toString().replace("-", "").take(12)}",
      requestedExtension = format,
      defaultExtension = DEFAULT_GENERATED_AUDIO_FORMAT,
      asset = audio,
      kindHint = "voice",
      durationMs = response.durationMs,
      transcriptText = transcriptText,
    )

    return AgentToolResult(
      toolName = "SynthesizeSpeech",
      status = AgentToolResultStatus.SUCCESS,
      content = buildGeneratedSpeechResultContent(artifact = artifact),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = buildMap {
          put("provider", settings.provider)
          put("endpoint", endpoint)
          put("textPreview", inlinePreview(text, maxChars = 240))
          put("format", format)
          voiceOverride?.let { put("voiceOverride", it) }
          modelOverride?.let { put("modelOverride", it) }
          response.providerRequestId?.let { put("providerRequestId", it) }
          putAll(attachmentArtifactsMetadata(listOf(artifact)))
          putAll(response.metadata)
        },
      ),
    )
  }

internal fun OpenCrayToolDispatcher.startBackgroundMediaJob(
    task: AgentTask,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
    toolName: String,
    plan: ToolPolicyPlan,
    summary: String,
    baseMetadata: Map<String, String>,
    work: ((() -> Boolean)) -> AgentToolResult,
  ): AgentToolResult {
    val jobId = nextMediaJobId(toolName)
    val cancelRequested = AtomicBoolean(false)
    val future = mediaJobCoordinator.executor.submit<AgentToolResult> {
      work {
        cancelRequested.get() || hooks.isCancellationRequested()
      }
    }
    val handle = MediaJobHandle(
      jobId = jobId,
      toolName = toolName,
      summary = summary,
      createdAtEpochMs = System.currentTimeMillis(),
      cancelRequested = cancelRequested,
      future = future,
      baseMetadata = baseMetadata,
    )
    synchronized(mediaJobCoordinator.jobs) {
      mediaJobCoordinator.jobs[jobId] = handle
    }
    val snapshot = OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = jobId,
        toolName = toolName,
        status = OpenCrayMediaJobStatus.PENDING,
      ),
      metadata = baseMetadata,
    )
    return AgentToolResult(
      toolName = toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = pendingMediaJobContent(snapshot),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = baseMetadata + mediaJobMetadata(
          handle = handle,
          status = OpenCrayMediaJobStatus.PENDING,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.nextMediaJobId(toolName: String): String {
    val normalizedToolName = toolName.trim()
      .lowercase(Locale.US)
      .replace("[^a-z0-9]+".toRegex(), "-")
      .trim('-')
    val suffix = mediaJobCoordinator.idCounter.incrementAndGet()
    return "media-$normalizedToolName-$suffix"
  }

internal fun OpenCrayToolDispatcher.mediaJobPendingResult(
    toolName: String,
    handle: MediaJobHandle,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.SUCCESS,
    content = pendingMediaJobContent(
      OpenCrayMediaJobSnapshot(
        receipt = OpenCrayMediaJobReceipt(
          jobId = handle.jobId,
          toolName = handle.toolName,
          status = OpenCrayMediaJobStatus.PENDING,
        ),
        metadata = handle.baseMetadata,
      ),
    ),
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = handle.baseMetadata + mediaJobMetadata(
        handle = handle,
        status = OpenCrayMediaJobStatus.PENDING,
      ),
    ),
  )

internal fun OpenCrayToolDispatcher.mediaJobCancelledObservationResult(
    handle: MediaJobHandle,
  ): AgentToolResult = AgentToolResult(
    toolName = "PollMediaJob",
    status = AgentToolResultStatus.SUCCESS,
    content = "Media job was cancelled.\njob_id=${handle.jobId}",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = "PollMediaJob",
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = handle.baseMetadata + mediaJobMetadata(
        handle = handle,
        status = OpenCrayMediaJobStatus.CANCELLED,
      ),
    ),
  )

internal fun OpenCrayToolDispatcher.missingMediaJobResult(
    toolName: String,
    jobId: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = "Media job '$jobId' was not found.",
    errorCode = "MEDIA_JOB_NOT_FOUND",
    errorMessage = "Media job '$jobId' was not found.",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = mapOf(
        "jobId" to jobId,
        "jobStatus" to OpenCrayMediaJobStatus.FAILED.name.lowercase(Locale.US),
      ),
    ),
  )

internal fun OpenCrayToolDispatcher.cancelledMediaToolResult(
    toolName: String,
    message: String,
    metadata: Map<String, String>,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.CANCELLED,
    content = message,
    errorCode = "MEDIA_JOB_CANCELLED",
    errorMessage = message,
    metadata = metadata,
  )

internal fun OpenCrayToolDispatcher.cancelledMediaJobTerminalResult(handle: MediaJobHandle): AgentToolResult =
    AgentToolResult(
      toolName = handle.toolName,
      status = AgentToolResultStatus.CANCELLED,
      content = "Media job was cancelled.",
      errorCode = "MEDIA_JOB_CANCELLED",
      errorMessage = "Media job was cancelled.",
      metadata = handle.baseMetadata,
    )

internal fun OpenCrayToolDispatcher.failedMediaJobTerminalResult(
    toolName: String,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "MEDIA_JOB_FAILED",
    errorMessage = message,
  )

internal fun OpenCrayToolDispatcher.pendingMediaJobContent(
    snapshot: OpenCrayMediaJobSnapshot,
    externalJobId: String = snapshot.receipt.jobId,
  ): String = buildString {
    appendLine("Media job is pending.")
    appendLine("job_id=$externalJobId")
    appendLine("status=${snapshot.receipt.status.name.lowercase(Locale.US)}")
    appendLine("poll_tool=${snapshot.receipt.pollToolName}")
    appendLine("cancel_tool=${snapshot.receipt.cancelToolName}")
    append("Call ${snapshot.receipt.pollToolName} with this job_id to check completion.")
  }.trim()

internal fun OpenCrayToolDispatcher.mediaJobMetadata(
    handle: MediaJobHandle,
    status: OpenCrayMediaJobStatus,
  ): Map<String, String> = mapOf(
    "jobId" to handle.jobId,
    "jobStatus" to status.name.lowercase(Locale.US),
    "jobToolName" to handle.toolName,
    "jobCreatedAtEpochMs" to handle.createdAtEpochMs.toString(),
    "jobPollToolName" to "PollMediaJob",
    "jobCancelToolName" to "CancelMediaJob",
    "jobPending" to (status == OpenCrayMediaJobStatus.PENDING).toString(),
  )

internal fun OpenCrayToolDispatcher.providerPendingMediaJobResult(
    plan: ToolPolicyPlan,
    snapshot: OpenCrayMediaJobSnapshot,
    metadata: Map<String, String>,
  ): AgentToolResult {
    val externalJobId = encodeProviderMediaJobId(snapshot)
    return AgentToolResult(
      toolName = snapshot.receipt.toolName,
      status = AgentToolResultStatus.SUCCESS,
      content = pendingMediaJobContent(snapshot, externalJobId = externalJobId),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = metadata + providerMediaJobMetadata(
          externalJobId = externalJobId,
          snapshot = snapshot,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.pollProviderMediaJob(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()
      ?: return unavailableMediaTool(
        toolName = "PollMediaJob",
        message = "Media job settings are unavailable on this runtime.",
      )
    val client = providerMediaJobClient
      ?: return unavailableMediaTool(
        toolName = "PollMediaJob",
        message = "Provider media job support is unavailable on this runtime.",
      )
    val polled = try {
      client.poll(
        job = snapshot,
        settings = settings,
      )
    } catch (_: CancellationException) {
      return providerCancelledMediaJobResult(
        externalJobId = externalJobId,
        snapshot = snapshot.copy(
          receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
        ),
      )
    } catch (exception: Throwable) {
      return AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = exception.message ?: "Media job polling failed.",
        errorCode = "MEDIA_JOB_FAILED",
        errorMessage = exception.message ?: "Media job polling failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = providerMediaJobMetadata(
            externalJobId = externalJobId,
            snapshot = snapshot.copy(
              receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.FAILED),
            ),
          ),
        ),
      )
    }
    val updatedExternalJobId = encodeProviderMediaJobId(polled.snapshot)
    return when (polled.snapshot.receipt.status) {
      OpenCrayMediaJobStatus.PENDING -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.SUCCESS,
        content = pendingMediaJobContent(polled.snapshot, externalJobId = updatedExternalJobId),
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = polled.snapshot.metadata +
            polled.metadata +
            providerMediaJobMetadata(
              externalJobId = updatedExternalJobId,
              snapshot = polled.snapshot,
            ),
        ),
      )

      OpenCrayMediaJobStatus.CANCELLED -> providerCancelledMediaJobResult(
        externalJobId = updatedExternalJobId,
        snapshot = polled.snapshot,
      )

      OpenCrayMediaJobStatus.FAILED -> AgentToolResult(
        toolName = "PollMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = "Media job failed.",
        errorCode = "MEDIA_JOB_FAILED",
        errorMessage = "Media job failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PollMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = polled.snapshot.metadata +
            polled.metadata +
            providerMediaJobMetadata(
              externalJobId = updatedExternalJobId,
              snapshot = polled.snapshot,
            ),
        ),
      )

      OpenCrayMediaJobStatus.COMPLETED -> completedProviderMediaJobResult(
        externalJobId = updatedExternalJobId,
        snapshot = polled.snapshot,
        pollResult = polled,
      )
    }
  }

internal fun OpenCrayToolDispatcher.cancelProviderMediaJob(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult {
    val settings = config.mediaToolSettingsProvider()
      ?: return unavailableMediaTool(
        toolName = "CancelMediaJob",
        message = "Media job settings are unavailable on this runtime.",
      )
    val client = providerMediaJobClient
      ?: return unavailableMediaTool(
        toolName = "CancelMediaJob",
        message = "Provider media job support is unavailable on this runtime.",
      )
    val cancelledSnapshot = try {
      client.cancel(
        job = snapshot,
        settings = settings,
      )
    } catch (_: CancellationException) {
      snapshot.copy(
        receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
      )
    } catch (exception: Throwable) {
      return AgentToolResult(
        toolName = "CancelMediaJob",
        status = AgentToolResultStatus.FAILED,
        content = exception.message ?: "Media job cancellation failed.",
        errorCode = "MEDIA_JOB_CANCEL_FAILED",
        errorMessage = exception.message ?: "Media job cancellation failed.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "CancelMediaJob",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.NETWORK,
            workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
          ),
          metadata = providerMediaJobMetadata(
            externalJobId = externalJobId,
            snapshot = snapshot.copy(
              receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.FAILED),
            ),
          ),
        ),
      )
    }
    val updatedExternalJobId = encodeProviderMediaJobId(cancelledSnapshot)
    val status = cancelledSnapshot.receipt.status
    return AgentToolResult(
      toolName = "CancelMediaJob",
      status = AgentToolResultStatus.SUCCESS,
      content = when (status) {
        OpenCrayMediaJobStatus.CANCELLED ->
          "Cancellation requested for media job.\njob_id=$updatedExternalJobId"

        OpenCrayMediaJobStatus.COMPLETED ->
          "Media job already completed.\njob_id=$updatedExternalJobId"

        else ->
          "Media job cancellation is pending.\njob_id=$updatedExternalJobId"
      },
      metadata = toolPolicyPipeline.resultMetadata(
        toolName = "CancelMediaJob",
        request = ToolMetadataContextRequest(
          targetKind = ToolTargetKind.NETWORK,
          workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
        ),
        metadata = cancelledSnapshot.metadata + providerMediaJobMetadata(
          externalJobId = updatedExternalJobId,
          snapshot = cancelledSnapshot,
        ),
      ),
    )
  }

internal fun OpenCrayToolDispatcher.completedProviderMediaJobResult(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
    pollResult: OpenCrayMediaJobPollResult,
  ): AgentToolResult {
    val request = ToolMetadataContextRequest(
      targetKind = ToolTargetKind.NETWORK,
      workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
    )
    return when (snapshot.receipt.toolName) {
      "GenerateImage" -> {
        require(pollResult.images.isNotEmpty()) { "Media job completed without image payloads." }
        val artifacts = pollResult.images.mapIndexed { index, asset ->
          writeGeneratedWorkspaceArtifact(
            directory = generatedMediaDirectory("images"),
            stem = buildString {
              append("image-")
              append(UUID.randomUUID().toString().replace("-", "").take(12))
              if (pollResult.images.size > 1) {
                append("-")
                append(index + 1)
              }
            },
            requestedExtension = snapshot.metadata["format"],
            defaultExtension = DEFAULT_GENERATED_IMAGE_FORMAT,
            asset = asset,
            kindHint = "image",
          )
        }
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedImageResultContent(artifacts))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              mapOf("imageCount" to artifacts.size.toString()) +
              attachmentArtifactsMetadata(artifacts) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }

      "GenerateVideo" -> {
        require(pollResult.videos.isNotEmpty()) { "Media job completed without video payloads." }
        val artifacts = pollResult.videos.mapIndexed { index, asset ->
          writeGeneratedWorkspaceArtifact(
            directory = generatedMediaDirectory("videos"),
            stem = buildString {
              append("video-")
              append(UUID.randomUUID().toString().replace("-", "").take(12))
              if (pollResult.videos.size > 1) {
                append("-")
                append(index + 1)
              }
            },
            requestedExtension = snapshot.metadata["format"],
            defaultExtension = DEFAULT_GENERATED_VIDEO_FORMAT,
            asset = asset,
            kindHint = "file",
          )
        }
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedVideoResultContent(artifacts))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              mapOf("videoCount" to artifacts.size.toString()) +
              attachmentArtifactsMetadata(artifacts) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }

      else -> {
        val audio = requireNotNull(pollResult.audio) { "Media job completed without audio payload." }
        val transcriptText = pollResult.transcriptText
          ?.trim()
          ?.takeIf(String::isNotBlank)
        val artifact = writeGeneratedWorkspaceArtifact(
          directory = generatedMediaDirectory("voices"),
          stem = "voice-${UUID.randomUUID().toString().replace("-", "").take(12)}",
          requestedExtension = snapshot.metadata["format"],
          defaultExtension = DEFAULT_GENERATED_AUDIO_FORMAT,
          asset = audio,
          kindHint = "voice",
          durationMs = pollResult.durationMs,
          transcriptText = transcriptText,
        )
        AgentToolResult(
          toolName = "PollMediaJob",
          status = AgentToolResultStatus.SUCCESS,
          content = buildString {
            appendLine("Media job completed.")
            appendLine("job_id=$externalJobId")
            appendLine()
            append(buildGeneratedSpeechResultContent(artifact))
          }.trim(),
          metadata = toolPolicyPipeline.resultMetadata(
            toolName = "PollMediaJob",
            request = request,
            metadata = snapshot.metadata +
              pollResult.metadata +
              attachmentArtifactsMetadata(listOf(artifact)) +
              providerMediaJobMetadata(
                externalJobId = externalJobId,
                snapshot = snapshot,
              ),
          ),
        )
      }
    }
  }

internal fun OpenCrayToolDispatcher.providerCancelledMediaJobResult(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): AgentToolResult = AgentToolResult(
    toolName = "PollMediaJob",
    status = AgentToolResultStatus.SUCCESS,
    content = "Media job was cancelled.\njob_id=$externalJobId",
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = "PollMediaJob",
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = snapshot.metadata + providerMediaJobMetadata(
        externalJobId = externalJobId,
        snapshot = snapshot.copy(
          receipt = snapshot.receipt.copy(status = OpenCrayMediaJobStatus.CANCELLED),
        ),
      ),
    ),
  )

internal fun OpenCrayToolDispatcher.providerMediaJobMetadata(
    externalJobId: String,
    snapshot: OpenCrayMediaJobSnapshot,
  ): Map<String, String> = mapOf(
    "jobId" to externalJobId,
    "providerJobId" to snapshot.receipt.jobId,
    "jobStatus" to snapshot.receipt.status.name.lowercase(Locale.US),
    "jobToolName" to snapshot.receipt.toolName,
    "jobPollToolName" to "PollMediaJob",
    "jobCancelToolName" to "CancelMediaJob",
    "jobPending" to (snapshot.receipt.status == OpenCrayMediaJobStatus.PENDING).toString(),
    "jobPollAfterMs" to snapshot.receipt.pollAfterMs.toString(),
  ) + snapshot.providerRequestId?.let { mapOf("providerRequestId" to it) }.orEmpty()

internal fun OpenCrayToolDispatcher.encodeProviderMediaJobId(snapshot: OpenCrayMediaJobSnapshot): String {
    val payload = buildJsonObject {
      put("v", 1)
      put("toolName", snapshot.receipt.toolName)
      put("providerJobId", snapshot.receipt.jobId)
      put("status", snapshot.receipt.status.name.lowercase(Locale.US))
      put("pollAfterMs", snapshot.receipt.pollAfterMs)
      snapshot.providerRequestId?.let { put("providerRequestId", it) }
      put(
        "metadata",
        buildJsonObject {
          snapshot.metadata
            .filterKeys { key -> key in ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS }
            .toSortedMap()
            .forEach { (key, value) ->
            put(key, value)
          }
        },
      )
    }
    val encoded = Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(config.json.encodeToString(JsonObject.serializer(), payload).toByteArray(StandardCharsets.UTF_8))
    return "$PROVIDER_MEDIA_JOB_ID_PREFIX$encoded"
  }

internal fun OpenCrayToolDispatcher.decodeProviderMediaJobId(jobId: String): OpenCrayMediaJobSnapshot? {
    if (!jobId.startsWith(PROVIDER_MEDIA_JOB_ID_PREFIX)) {
      return null
    }
    val encodedPayload = jobId.removePrefix(PROVIDER_MEDIA_JOB_ID_PREFIX)
    val decoded = runCatching {
      String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8)
    }.getOrNull() ?: return null
    val payload = config.json.parseToJsonElement(decoded) as? JsonObject ?: return null
    val toolName = (payload["toolName"] as? JsonPrimitive)?.content.orEmpty()
      .takeIf(String::isNotBlank)
      ?: return null
    val providerJobId = (payload["providerJobId"] as? JsonPrimitive)?.content.orEmpty()
      .takeIf(String::isNotBlank)
      ?: return null
    val status = (payload["status"] as? JsonPrimitive)?.content
      ?.trim()
      ?.uppercase(Locale.US)
      ?.let { raw -> OpenCrayMediaJobStatus.entries.firstOrNull { entry -> entry.name == raw } }
      ?: OpenCrayMediaJobStatus.PENDING
    val pollAfterMs = (payload["pollAfterMs"] as? JsonPrimitive)?.content
      ?.toLongOrNull()
      ?.takeIf { it > 0L }
      ?: 1_000L
    val providerRequestId = (payload["providerRequestId"] as? JsonPrimitive)?.content
      ?.takeIf(String::isNotBlank)
    val metadata = (payload["metadata"] as? JsonObject)
      ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
      .orEmpty()
    return OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = providerJobId,
        toolName = toolName,
        status = status,
        pollAfterMs = pollAfterMs,
      ),
      providerRequestId = providerRequestId,
      metadata = metadata,
    )
  }

internal fun OpenCrayToolDispatcher.publishMediaArtifact(
    task: AgentTask,
    arguments: JsonObject,
  ): AgentToolResult {
    val artifactId = arguments.requiredStringFrom("artifact_id", "artifactId")
    val destination = toolTargetResolver.resolveWritablePath(
      candidate = arguments.requiredStringFrom("relative_path", "relativePath", "destination_path", "destinationPath"),
      label = "media artifact publish",
      defaultToRoot = false,
    )
    val registeredArtifact = config.mediaArtifactRegistry.resolve(artifactId)
      ?: return AgentToolResult(
        toolName = "PublishMediaArtifact",
        status = AgentToolResultStatus.FAILED,
        content = "Media artifact '$artifactId' was not found in the workspace media registry.",
        errorCode = "MEDIA_ARTIFACT_NOT_FOUND",
        errorMessage = "Media artifact '$artifactId' was not found in the workspace media registry.",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PublishMediaArtifact",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.FILE,
            primaryPath = destination,
          ),
          metadata = mapOf(
            "artifactId" to artifactId,
            "path" to toolTargetResolver.displayWritablePath(destination),
          ),
        ),
      )
    val source = writeBoundary.defaultRoot
      .resolve(registeredArtifact.artifact.relativePath)
      .normalize()
    require(source.startsWith(writeBoundary.defaultRoot)) {
      "Registered media artifact '$artifactId' escapes the workspace root."
    }
    require(Files.isRegularFile(source)) {
      "Registered media artifact '$artifactId' no longer exists."
    }
    if (Files.exists(destination)) {
      val displayPath = toolTargetResolver.displayWritablePath(destination)
      return AgentToolResult(
        toolName = "PublishMediaArtifact",
        status = AgentToolResultStatus.FAILED,
        content = "PublishMediaArtifact destination already exists: $displayPath",
        errorCode = "ILLEGAL_ARGUMENT",
        errorMessage = "PublishMediaArtifact destination already exists: $displayPath",
        metadata = toolPolicyPipeline.resultMetadata(
          toolName = "PublishMediaArtifact",
          request = ToolMetadataContextRequest(
            targetKind = ToolTargetKind.FILE,
            primaryPath = destination,
          ),
          metadata = mapOf(
            "artifactId" to artifactId,
            "path" to displayPath,
          ),
        ),
      )
    }
    val plan = toolPolicyPipeline.plan(
      task = task,
      toolName = "PublishMediaArtifact",
      targetPath = destination,
      metadataRequest = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.FILE,
        primaryPath = destination,
      ),
    )
    toolPolicyPipeline.gateFileMutation(
      plan = plan,
      affectedPaths = mapOf(
        "path" to toolTargetResolver.displayWritablePath(destination),
        "sourcePath" to toolTargetResolver.displayWritablePath(source),
      ),
    )?.let { return it }
    Files.createDirectories(destination.parent)
    Files.copy(source, destination)
    val artifact = OpenCrayGeneratedWorkspaceArtifact(
      path = destination,
      kindHint = registeredArtifact.artifact.kindHint,
      mimeType = registeredArtifact.artifact.mimeType,
      displayName = destination.fileName?.toString() ?: registeredArtifact.artifact.displayName,
      durationMs = registeredArtifact.artifact.durationMs,
      waveformBars = registeredArtifact.artifact.waveformBars,
      transcriptText = registeredArtifact.artifact.transcriptText,
    )
    val publishedMetadata = attachmentArtifactsMetadata(listOf(artifact))
    val publishedDescriptor = OpenCrayAttachmentArtifacts.decodeMetadata(config.json, publishedMetadata).firstOrNull()
    return AgentToolResult(
      toolName = "PublishMediaArtifact",
      status = AgentToolResultStatus.SUCCESS,
      content = buildString {
        appendLine("Published media artifact.")
        appendLine("source_artifact_id=$artifactId")
        publishedDescriptor?.let { descriptor ->
          appendLine("artifact_id=${descriptor.artifactId}")
          appendLine("relative_path=${descriptor.relativePath}")
        }
        append("Use the published relative_path in the final response attachment if the user requested a workspace file.")
      }.trim(),
      metadata = toolPolicyPipeline.resultMetadata(
        plan = plan,
        metadata = mapOf(
          "sourceArtifactId" to artifactId,
          "sourcePath" to toolTargetResolver.displayWritablePath(source),
          "path" to toolTargetResolver.displayWritablePath(destination),
        ) + publishedMetadata,
      ),
    )
  }

internal fun OpenCrayToolDispatcher.unavailableMediaTool(
    toolName: String,
    message: String,
  ): AgentToolResult = AgentToolResult(
    toolName = toolName,
    status = AgentToolResultStatus.FAILED,
    content = message,
    errorCode = "MEDIA_TOOL_UNAVAILABLE",
    errorMessage = message,
    metadata = toolPolicyPipeline.resultMetadata(
      toolName = toolName,
      request = ToolMetadataContextRequest(
        targetKind = ToolTargetKind.NETWORK,
        workspaceRelation = ToolWorkspaceRelation.INSIDE_WORKSPACE,
      ),
      metadata = mapOf("configured" to "false"),
    ),
  )

internal fun OpenCrayToolDispatcher.buildConfiguredEndpointPreview(
    baseUrl: String,
    endpoint: String,
  ): String {
    val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedEndpoint = endpoint.trim()
    if (normalizedEndpoint.startsWith("http://") || normalizedEndpoint.startsWith("https://")) {
      return normalizedEndpoint
    }
    val endpointSuffix = normalizedEndpoint.trimStart('/')
    return when {
      normalizedBaseUrl.isBlank() -> normalizedEndpoint
      endpointSuffix.isBlank() -> normalizedBaseUrl
      else -> "$normalizedBaseUrl/$endpointSuffix"
    }
  }

internal fun OpenCrayToolDispatcher.buildGeneratedImageResultContent(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): String = buildString {
    appendLine("Generated ${artifacts.size} image file(s).")
    artifacts.forEachIndexed { index, artifact ->
      val descriptor = attachmentArtifactDescriptor(artifact) ?: return@forEachIndexed
      appendLine("${index + 1}. artifact_id=${descriptor.artifactId}")
      appendLine("   relative_path=${descriptor.relativePath}")
    }
    append("Attach the artifact_id values in the final response attachments array to send these images.")
  }.trim()

internal fun OpenCrayToolDispatcher.buildGeneratedVideoResultContent(
    artifacts: List<OpenCrayGeneratedWorkspaceArtifact>,
  ): String = buildString {
    appendLine("Generated ${artifacts.size} video file(s).")
    artifacts.forEachIndexed { index, artifact ->
      val descriptor = attachmentArtifactDescriptor(artifact) ?: return@forEachIndexed
      appendLine("${index + 1}. artifact_id=${descriptor.artifactId}")
      appendLine("   relative_path=${descriptor.relativePath}")
    }
    append("Use kind=file when attaching these artifact_id values in the final response.")
  }.trim()

internal fun OpenCrayToolDispatcher.buildGeneratedSpeechResultContent(
    artifact: OpenCrayGeneratedWorkspaceArtifact,
  ): String {
    val descriptor = attachmentArtifactDescriptor(artifact)
      ?: return "Synthesized speech successfully."
    return buildString {
      appendLine("Synthesized one voice clip.")
      appendLine("artifact_id=${descriptor.artifactId}")
      appendLine("relative_path=${descriptor.relativePath}")
      append("Use kind=voice when attaching this artifact in the final response.")
    }.trim()
  }

internal fun OpenCrayToolDispatcher.generatedMediaDirectory(bucket: String): Path =
    writeBoundary.defaultRoot
      .resolve(".opencray")
      .resolve("generated-media")
      .resolve(bucket)
      .normalize()

internal fun OpenCrayToolDispatcher.writeGeneratedWorkspaceArtifact(
    directory: Path,
    stem: String,
    requestedExtension: String?,
    defaultExtension: String,
    asset: OpenCrayBinaryAsset,
    kindHint: String? = null,
    durationMs: Long? = null,
    transcriptText: String? = null,
  ): OpenCrayGeneratedWorkspaceArtifact {
    require(asset.bytes.isNotEmpty() || asset.sourcePath != null) { "Generated media asset was empty." }
    val resolvedExtension = resolveGeneratedAssetExtension(
      requestedExtension = requestedExtension,
      defaultExtension = defaultExtension,
      fileName = asset.fileName,
      mimeType = asset.mimeType,
    )
    Files.createDirectories(directory)
    val outputPath = directory.resolve("$stem.$resolvedExtension")
    asset.sourcePath?.let { sourcePath ->
      runCatching {
        Files.move(sourcePath, outputPath, StandardCopyOption.REPLACE_EXISTING)
      }.getOrElse {
        Files.copy(sourcePath, outputPath, StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(sourcePath)
      }
    } ?: Files.write(outputPath, asset.bytes)
    return OpenCrayGeneratedWorkspaceArtifact(
      path = outputPath,
      kindHint = kindHint,
      mimeType = asset.mimeType?.trim()?.takeIf(String::isNotBlank)
        ?: OpenCrayAttachmentArtifacts.mimeTypeForDisplayName(outputPath.fileName.toString()),
      displayName = outputPath.fileName.toString(),
      durationMs = durationMs,
      transcriptText = transcriptText,
    )
  }

internal fun OpenCrayToolDispatcher.resolveGeneratedAssetExtension(
    requestedExtension: String?,
    defaultExtension: String,
    fileName: String?,
    mimeType: String?,
  ): String = requestedExtension
    ?.trim()
    ?.lowercase(Locale.US)
    ?.takeIf(String::isNotBlank)
    ?: fileName
      ?.substringAfterLast('.', "")
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)
      ?: mimeTypeToExtension(mimeType)
      ?: defaultExtension

internal fun OpenCrayToolDispatcher.mimeTypeToExtension(mimeType: String?): String? = when (mimeType?.trim()?.lowercase(Locale.US)) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "audio/mpeg",
    "audio/mp3",
    -> "mp3"
    "audio/wav",
    "audio/x-wav",
    -> "wav"
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    -> "m4a"
    "video/mp4" -> "mp4"
    "video/quicktime" -> "mov"
    "video/webm" -> "webm"
    else -> null
  }

internal fun OpenCrayToolDispatcher.normalizeGeneratedImageFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "jpg" -> "jpg"
    "jpeg" -> "jpeg"
    "png" -> "png"
    "webp" -> "webp"
    else -> throw IllegalArgumentException("GenerateImage format must be png, jpg, jpeg, or webp.")
  }

internal fun OpenCrayToolDispatcher.normalizeGeneratedAudioFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "mp3" -> "mp3"
    "wav" -> "wav"
    "m4a" -> "m4a"
    else -> throw IllegalArgumentException("SynthesizeSpeech format must be mp3, wav, or m4a.")
  }

internal fun OpenCrayToolDispatcher.normalizeGeneratedVideoFormat(rawValue: String?): String? = when (rawValue?.trim()?.lowercase(Locale.US)) {
    null,
    "",
    -> null
    "mp4" -> "mp4"
    "mov" -> "mov"
    "webm" -> "webm"
    else -> throw IllegalArgumentException("GenerateVideo format must be mp4, mov, or webm.")
  }
