package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteLlmImageSummaryExtractorTest {
  @get:Rule
  val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @Test
  fun extractBuildsVisionRequestAndParsesJsonPayload() {
    val imagePath = writeImage("extractor-image.png")
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """
          {"caption":"Front portrait","summary":"A front-facing portrait with short dark hair and a dark coat.","portrait_summary":"Short dark hair, dark coat, steady front-facing gaze."}
        """.trimIndent(),
      ),
    )
    val extractor = LiteLlmImageSummaryExtractor(
      llmSettingsProvider = {
        configuredSettings(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
          visionInputSupported = true,
        )
      },
      providerClient = providerClient,
    )

    val summary = extractor.extract(
      AppImageSummaryExtractionRequest(
        imagePath = imagePath,
        source = imageSource(displayName = "portrait.png"),
        targetKind = AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT,
      ),
    )

    assertNotNull(summary)
    requireNotNull(summary)
    assertEquals("Front portrait", summary.caption)
    assertEquals("Short dark hair, dark coat, steady front-facing gaze.", summary.portraitSummary)
    val recordedRequest = providerClient.recordedRequest
    assertNotNull(recordedRequest)
    requireNotNull(recordedRequest)
    assertEquals("true", recordedRequest.route.metadata["visionInputSupported"])
    assertEquals(imagePath.toString(), recordedRequest.request.messages.single().attachments.single().filePath)
  }

  @Test
  fun extractReturnsNullWhenVisionInputIsUnavailable() {
    val providerClient = RecordingProviderClient(
      result = LiteLlmProviderResult.Success(
        outputText = """{"caption":"Ignored","summary":"Ignored","portrait_summary":null}""",
      ),
    )
    val extractor = LiteLlmImageSummaryExtractor(
      llmSettingsProvider = {
        configuredSettings(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
          visionInputSupported = false,
        )
      },
      providerClient = providerClient,
    )

    val summary = extractor.extract(
      AppImageSummaryExtractionRequest(
        imagePath = writeImage("no-vision.png"),
        source = imageSource(displayName = "portrait.png"),
        targetKind = AppImageReferenceTargetKind.MEMORY,
      ),
    )

    assertNull(summary)
    assertNull(providerClient.recordedRequest)
  }

  @Test
  fun extractFallsBackToSummaryWhenPortraitSummaryIsMissing() {
    val extractor = LiteLlmImageSummaryExtractor(
      llmSettingsProvider = {
        configuredSettings(
          protocol = LlmProviderProtocols.OPENAI,
          baseUrl = "https://api.openai.com/v1",
          model = "gpt-4o-mini",
          visionInputSupported = true,
        )
      },
      providerClient = RecordingProviderClient(
        result = LiteLlmProviderResult.Success(
          outputText = """
            {"caption":"Front portrait","summary":"A calm front-facing portrait with short dark hair.","portrait_summary":null}
          """.trimIndent(),
        ),
      ),
    )

    val summary = extractor.extract(
      AppImageSummaryExtractionRequest(
        imagePath = writeImage("portrait-fallback.png"),
        source = imageSource(displayName = "portrait.png"),
        targetKind = AppImageReferenceTargetKind.SOUL_PRIMARY_PORTRAIT,
      ),
    )

    assertNotNull(summary)
    assertEquals(
      "A calm front-facing portrait with short dark hair.",
      summary?.portraitSummary,
    )
  }

  private fun configuredSettings(
    protocol: String,
    baseUrl: String,
    model: String,
    visionInputSupported: Boolean,
  ): LlmSettingsState {
    val routeFingerprint = llmRouteFingerprint(
      protocol = protocol,
      baseUrl = baseUrl,
      model = model,
    )
    return LlmSettingsState(
      enabled = true,
      providerId = "openai",
      protocol = protocol,
      baseUrl = baseUrl,
      apiKey = "test-key",
      model = model,
      agentCapability = LlmAgentCapabilitySnapshot(
        routeFingerprint = routeFingerprint,
        verifiedAtEpochMs = 1L,
        visionInputSupported = visionInputSupported,
      ),
    )
  }

  private fun imageSource(displayName: String): com.opencray.runtime.OpenCrayImageReferenceSource =
    com.opencray.runtime.OpenCrayImageReferenceSource(
      sourceKind = com.opencray.runtime.OpenCrayImageReferenceSourceKind.SETTINGS_ASSET,
      settingsAssetId = "settings-portrait",
      displayName = displayName,
      mimeType = "image/png",
    )

  private fun writeImage(fileName: String) = temporaryFolder.newFolder("image-summary-${System.nanoTime()}")
    .toPath()
    .resolve(fileName)
    .also { path ->
      Files.write(path, byteArrayOf(1, 2, 3, 4))
    }

  private class RecordingProviderClient(
    private val result: LiteLlmProviderResult,
  ) : LiteLlmProviderClient {
    var recordedRequest: LiteLlmProviderRequest? = null
      private set

    override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
      recordedRequest = request
      return result
    }
  }
}
