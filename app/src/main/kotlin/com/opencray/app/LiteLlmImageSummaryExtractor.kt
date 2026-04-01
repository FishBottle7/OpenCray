package com.opencray.app

import com.opencray.llm.DefaultLiteLlmGateway
import com.opencray.llm.InMemoryLiteLlmRoutingSettingsStore
import com.opencray.llm.LiteLlmGateway
import com.opencray.llm.LiteLlmGatewayAttachment
import com.opencray.llm.LiteLlmGatewayAttachmentKind
import com.opencray.llm.LiteLlmGatewayMessage
import com.opencray.llm.LiteLlmGatewayMessageRole
import com.opencray.llm.LiteLlmGatewayRequest
import com.opencray.llm.LiteLlmGatewayStatus
import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.ModelProfile
import com.opencray.llm.ProviderRoute
import com.opencray.llm.ProviderRouting
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class LiteLlmImageSummaryExtractor(
  private val llmSettingsProvider: () -> LlmSettingsState,
  private val providerClient: LiteLlmProviderClient,
  private val gatewayFactory: (ProviderRouting, LiteLlmProviderClient) -> LiteLlmGateway =
    { routing, client ->
      DefaultLiteLlmGateway(
        routingStore = InMemoryLiteLlmRoutingSettingsStore(routing),
        providerClient = client,
      )
    },
) : AppImageSummaryExtractor {
  override fun extract(request: AppImageSummaryExtractionRequest): AppImageSummary? {
    val settings = llmSettingsProvider().sanitized()
    if (!settings.enabled || !settings.isConfigured()) {
      return null
    }
    val metadata = LiteLlmJsonExtractionSupport.routeMetadata(settings)
    if (metadata["visionInputSupported"] != "true") {
      return null
    }
    val gateway = gatewayFactory(buildRouting(settings, metadata), providerClient)
    val result = gateway.execute(
      LiteLlmGatewayRequest(
        requestId = "image-summary-${UUID.randomUUID()}",
        prompt = buildPrompt(request.targetKind),
        systemPrompt = SYSTEM_PROMPT,
        messages = listOf(
          LiteLlmGatewayMessage(
            role = LiteLlmGatewayMessageRole.USER,
            content = buildUserMessage(request),
            attachments = listOf(
              LiteLlmGatewayAttachment(
                attachmentId = request.source.displayName ?: request.imagePath.fileName?.toString(),
                kind = LiteLlmGatewayAttachmentKind.IMAGE,
                displayName = request.source.displayName ?: request.imagePath.fileName?.toString(),
                filePath = request.imagePath.toString(),
                mimeType = request.source.mimeType,
              ),
            ),
          ),
        ),
        metadata = metadata,
        authHeaders = LlmProviderProtocols.authHeaders(
          protocol = settings.protocol,
          apiKey = settings.apiKey,
        ),
      ),
    )
    if (result.status != LiteLlmGatewayStatus.SUCCESS) {
      return null
    }
    val rawOutput = result.outputText
      ?: result.completion?.finalText
      ?: result.completion?.rawText
      ?: result.completion?.commentaryText
      .orEmpty()
    return parsePayload(rawOutput, request.targetKind)
  }

  private fun parsePayload(
    raw: String,
    targetKind: AppImageReferenceTargetKind,
  ): AppImageSummary? {
    val jsonCandidate = extractEmbeddedJsonObject(raw.trim()) ?: return null
    val payload = runCatching {
      Json {
        ignoreUnknownKeys = true
      }.decodeFromString<InterpreterPayload>(jsonCandidate)
    }.getOrNull() ?: return null
    val caption = payload.caption?.trim()?.takeIf(String::isNotBlank) ?: return null
    val summary = payload.summary?.trim()?.takeIf(String::isNotBlank) ?: return null
    val portraitSummary = payload.portraitSummary
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: summary.takeIf { targetKind == AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT }
    return AppImageSummary(
      caption = caption,
      summary = summary,
      portraitSummary = portraitSummary,
    )
  }

  private fun buildRouting(
    settings: LlmSettingsState,
    metadata: Map<String, String>,
  ): ProviderRouting {
    val route = ProviderRoute(
      id = ROUTE_ID,
      providerId = settings.providerId,
      baseUrl = settings.baseUrl,
      model = settings.model,
      timeoutMs = recommendedInterpreterProviderRouteTimeoutMs(settings.model),
      metadata = metadata,
    )
    return ProviderRouting(
      activeProfileId = PROFILE_ID,
      profiles = listOf(
        ModelProfile(
          id = PROFILE_ID,
          displayName = "Image summary extractor",
          primaryRouteId = route.id,
          routes = listOf(route),
        ),
      ),
    )
  }

  private fun buildPrompt(
    targetKind: AppImageReferenceTargetKind,
  ): String = when (targetKind) {
    AppImageReferenceTargetKind.MEMORY ->
      "Analyze the attached image and return JSON with a short caption and a compact factual summary."

    AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT ->
      "Analyze the attached portrait image and return JSON with caption, summary, and portrait_summary."

    AppImageReferenceTargetKind.SOUL_REFERENCE ->
      "Analyze the attached reference image and return JSON with a short caption and a compact appearance summary."
  }

  private fun buildUserMessage(
    request: AppImageSummaryExtractionRequest,
  ): String = buildString {
    appendLine("Inspect the attached image and return exactly one JSON object.")
    appendLine("Do not include markdown or extra prose.")
    appendLine()
    appendLine("Required keys:")
    appendLine("- caption: 3 to 10 words.")
    appendLine("- summary: 1 to 3 short sentences, grounded only in visible details.")
    if (request.targetKind == AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT) {
      appendLine("- portrait_summary: 1 sentence describing stable visible identity anchors.")
    } else {
      appendLine("- portrait_summary: null.")
    }
    appendLine()
    appendLine("Rules:")
    appendLine("- Do not infer hidden biography, personality, or relationships.")
    appendLine("- Prefer concrete visible traits, objects, framing, colors, clothing, and composition.")
    appendLine("- If the image is ambiguous, stay conservative instead of inventing details.")
    request.source.displayName?.trim()?.takeIf(String::isNotBlank)?.let { displayName ->
      appendLine("- Source label: $displayName")
    }
    appendLine()
    appendLine("JSON example:")
    if (request.targetKind == AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT) {
      appendLine("""{"caption":"Front portrait","summary":"A front-facing portrait with short dark hair, a dark coat, and a calm expression.","portrait_summary":"Short dark hair, dark coat, steady front-facing gaze."}""")
    } else {
      appendLine("""{"caption":"Reference image","summary":"A softly lit three-quarter portrait with a dark coat and neutral expression.","portrait_summary":null}""")
    }
  }

  private fun extractEmbeddedJsonObject(raw: String): String? {
    if (raw.startsWith("{") && raw.endsWith("}")) {
      return raw
    }
    var depth = 0
    var startIndex = -1
    var inString = false
    var escaped = false
    for ((index, character) in raw.withIndex()) {
      when {
        inString && escaped -> escaped = false
        inString && character == '\\' -> escaped = true
        character == '"' -> inString = !inString
        !inString && character == '{' -> {
          if (depth == 0) {
            startIndex = index
          }
          depth += 1
        }

        !inString && character == '}' -> {
          depth -= 1
          if (depth == 0 && startIndex >= 0) {
            return raw.substring(startIndex, index + 1)
          }
        }
      }
    }
    return null
  }

  @Serializable
  private data class InterpreterPayload(
    val caption: String? = null,
    val summary: String? = null,
    @SerialName("portrait_summary")
    val portraitSummary: String? = null,
  )

  private companion object {
    const val PROFILE_ID: String = "image-summary-extractor"
    const val ROUTE_ID: String = "image-summary-primary"
    const val SYSTEM_PROMPT: String =
      "You are a strict JSON image summarizer for OpenCray durable image references. Output valid JSON only."
  }
}
