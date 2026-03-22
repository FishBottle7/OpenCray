package com.opencray.app

import com.opencray.runtime.OpenCrayBinaryAsset
import com.opencray.runtime.OpenCrayImageGenerationClient
import com.opencray.runtime.OpenCrayImageGenerationRequest
import com.opencray.runtime.OpenCrayImageGenerationResponse
import com.opencray.runtime.OpenCraySpeechSynthesisClient
import com.opencray.runtime.OpenCraySpeechSynthesisRequest
import com.opencray.runtime.OpenCraySpeechSynthesisResponse
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class OpenCrayConfigurableMediaProviderClient(
  private val userAgent: String = OpenCrayUserAgent.providerApi("0"),
) : OpenCrayImageGenerationClient, OpenCraySpeechSynthesisClient {
  override fun generate(request: OpenCrayImageGenerationRequest): OpenCrayImageGenerationResponse {
    val endpoint = buildEndpointUrl(
      baseUrl = request.settings.baseUrl,
      endpoint = request.settings.endpoint,
    )
    val response = executeJsonPost(
      endpoint = endpoint,
      body = buildImageRequestBody(request).toString(),
      authHeaders = request.settings.authHeaders,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith("image/") == true) {
      return OpenCrayImageGenerationResponse(
        images = listOf(
          OpenCrayBinaryAsset(
            bytes = response.body,
            mimeType = contentType,
          ),
        ),
        providerRequestId = response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObject(response)
    val images = parseBinaryAssetList(
      payload = payload,
      authHeaders = request.settings.authHeaders,
      arrayKeys = listOf("images", "data", "outputs", "artifacts", "files"),
      singleKeys = listOf("image", "output", "file"),
    )
    if (images.isEmpty()) {
      throw IllegalStateException("Image provider returned no images.")
    }
    return OpenCrayImageGenerationResponse(
      images = images.take(request.count),
      providerRequestId = payload.optString("id").ifBlank {
        payload.optString("request_id")
      }.ifBlank {
        response.providerRequestId().orEmpty()
      }.ifBlank { null },
      metadata = mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  override fun synthesize(request: OpenCraySpeechSynthesisRequest): OpenCraySpeechSynthesisResponse {
    val endpoint = buildEndpointUrl(
      baseUrl = request.settings.baseUrl,
      endpoint = request.settings.endpoint,
    )
    val response = executeJsonPost(
      endpoint = endpoint,
      body = buildSpeechRequestBody(request).toString(),
      authHeaders = request.settings.authHeaders,
    )
    if (response.statusCode !in 200..299) {
      throw IllegalStateException(extractErrorMessage(response))
    }
    val contentType = normalizedContentType(response.contentType)
    if (contentType?.startsWith("audio/") == true) {
      return OpenCraySpeechSynthesisResponse(
        audio = OpenCrayBinaryAsset(
          bytes = response.body,
          mimeType = contentType,
        ),
        providerRequestId = response.providerRequestId(),
        metadata = mapOf("statusCode" to response.statusCode.toString()),
      )
    }

    val payload = parseJsonObject(response)
    val audio = parseBinaryAssetList(
      payload = payload,
      authHeaders = request.settings.authHeaders,
      arrayKeys = listOf("audio", "data", "outputs", "files"),
      singleKeys = listOf("audio", "file", "output", "data"),
    ).firstOrNull()
      ?: throw IllegalStateException("Speech provider returned no audio payload.")
    return OpenCraySpeechSynthesisResponse(
      audio = audio,
      providerRequestId = payload.optString("id").ifBlank {
        payload.optString("request_id")
      }.ifBlank {
        response.providerRequestId().orEmpty()
      }.ifBlank { null },
      durationMs = payload.optLong("duration_ms").takeIf { it > 0L }
        ?: payload.optLong("durationMs").takeIf { it > 0L },
      transcriptText = payload.optString("transcript_text").ifBlank {
        payload.optString("transcript")
      }.ifBlank {
        payload.optString("text")
      }.ifBlank { null },
      metadata = mapOf("statusCode" to response.statusCode.toString()),
    )
  }

  private fun buildImageRequestBody(request: OpenCrayImageGenerationRequest): JSONObject =
    JSONObject()
      .put("prompt", request.prompt)
      .put("model", request.modelOverride ?: request.settings.model)
      .put("n", request.count)
      .apply {
        request.size?.let { put("size", it) }
        request.format?.let { put("format", it) }
      }

  private fun buildSpeechRequestBody(request: OpenCraySpeechSynthesisRequest): JSONObject =
    JSONObject()
      .put("input", request.text)
      .put("model", request.modelOverride ?: request.settings.defaultModel)
      .put("voice", request.voiceOverride ?: request.settings.defaultVoice)
      .apply {
        request.format?.let { format ->
          put("response_format", format)
        }
      }

  private fun executeJsonPost(
    endpoint: String,
    body: String,
    authHeaders: Map<String, String>,
  ): HttpResponse {
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = DEFAULT_TIMEOUT_MS
      readTimeout = DEFAULT_TIMEOUT_MS
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "*/*")
      setRequestProperty("User-Agent", userAgent)
      authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }
      readHttpResponse(connection)
    } finally {
      connection.disconnect()
    }
  }

  private fun readHttpResponse(connection: HttpURLConnection): HttpResponse {
    val statusCode = connection.responseCode
    val body = readStreamBytes(
      if (statusCode in 200..299) connection.inputStream else connection.errorStream,
    )
    return HttpResponse(
      statusCode = statusCode,
      contentType = connection.getHeaderField("Content-Type"),
      body = body,
      headers = mapOf(
        "X-Request-Id" to connection.getHeaderField("X-Request-Id").orEmpty(),
        "OpenAI-Request-ID" to connection.getHeaderField("OpenAI-Request-ID").orEmpty(),
      ),
    )
  }

  private fun parseJsonObject(response: HttpResponse): JSONObject =
    runCatching {
      JSONObject(response.body.toString(StandardCharsets.UTF_8))
    }.getOrElse {
      throw IllegalStateException(
        extractErrorMessage(response).ifBlank { "Provider returned a non-JSON response." },
      )
    }

  private fun parseBinaryAssetList(
    payload: JSONObject,
    authHeaders: Map<String, String>,
    arrayKeys: List<String>,
    singleKeys: List<String>,
  ): List<OpenCrayBinaryAsset> {
    arrayKeys.forEach { key ->
      payload.optJSONArray(key)?.let { array ->
        val parsed = parseBinaryAssetArray(array, authHeaders)
        if (parsed.isNotEmpty()) {
          return parsed
        }
      }
    }
    singleKeys.forEach { key ->
      parseBinaryAssetCandidate(payload.opt(key), authHeaders)?.let { candidate ->
        return listOf(candidate)
      }
    }
    parseBinaryAssetCandidate(payload, authHeaders)?.let { candidate ->
      return listOf(candidate)
    }
    return emptyList()
  }

  private fun parseBinaryAssetArray(
    array: JSONArray,
    authHeaders: Map<String, String>,
  ): List<OpenCrayBinaryAsset> = buildList {
    for (index in 0 until array.length()) {
      parseBinaryAssetCandidate(array.opt(index), authHeaders)?.let(::add)
    }
  }

  private fun parseBinaryAssetCandidate(
    rawValue: Any?,
    authHeaders: Map<String, String>,
  ): OpenCrayBinaryAsset? = when (rawValue) {
    null,
    JSONObject.NULL,
    -> null

    is JSONObject -> parseBinaryAssetObject(rawValue, authHeaders)
    is String -> parseBinaryAssetString(rawValue, authHeaders)
    else -> null
  }

  private fun parseBinaryAssetObject(
    payload: JSONObject,
    authHeaders: Map<String, String>,
  ): OpenCrayBinaryAsset? {
    parseBinaryAssetString(payload.optString("url"), authHeaders)?.let { return it }
    parseBinaryAssetString(payload.optString("image_url"), authHeaders)?.let { return it }
    parseBinaryAssetString(payload.optString("output_url"), authHeaders)?.let { return it }
    decodeBase64Asset(
      base64Value = payload.optString("b64_json").ifBlank {
        payload.optString("base64")
      }.ifBlank {
        payload.optString("data")
      }.ifBlank {
        payload.optString("bytes")
      },
      mimeType = payload.optString("mime_type").ifBlank {
        payload.optString("mimeType")
      }.ifBlank { null },
      fileName = payload.optString("file_name").ifBlank {
        payload.optString("filename")
      }.ifBlank {
        payload.optString("name")
      }.ifBlank { null },
    )?.let { return it }
    payload.optJSONObject("file")?.let { nested ->
      parseBinaryAssetObject(nested, authHeaders)?.let { return it }
    }
    payload.optJSONObject("audio")?.let { nested ->
      parseBinaryAssetObject(nested, authHeaders)?.let { return it }
    }
    return null
  }

  private fun parseBinaryAssetString(
    rawValue: String?,
    authHeaders: Map<String, String>,
  ): OpenCrayBinaryAsset? {
    val normalized = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (normalized.startsWith("data:", ignoreCase = true)) {
      return parseDataUri(normalized)
    }
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      return downloadAsset(normalized, authHeaders)
    }
    return decodeBase64Asset(
      base64Value = normalized,
      mimeType = null,
      fileName = null,
    )
  }

  private fun parseDataUri(rawValue: String): OpenCrayBinaryAsset? {
    val commaIndex = rawValue.indexOf(',')
    if (commaIndex <= 0) {
      return null
    }
    val metadata = rawValue.substring(5, commaIndex)
    val mimeType = metadata.substringBefore(';').trim().ifBlank { null }
    val body = rawValue.substring(commaIndex + 1)
    val bytes = if (metadata.contains(";base64", ignoreCase = true)) {
      decodeBase64(body) ?: return null
    } else {
      body.toByteArray(StandardCharsets.UTF_8)
    }
    return OpenCrayBinaryAsset(
      bytes = bytes,
      mimeType = mimeType,
    )
  }

  private fun decodeBase64Asset(
    base64Value: String?,
    mimeType: String?,
    fileName: String?,
  ): OpenCrayBinaryAsset? {
    val decoded = decodeBase64(base64Value) ?: return null
    return OpenCrayBinaryAsset(
      bytes = decoded,
      mimeType = mimeType,
      fileName = fileName,
    )
  }

  private fun decodeBase64(rawValue: String?): ByteArray? {
    val normalized = rawValue
      ?.trim()
      ?.replace("\\s".toRegex(), "")
      ?.takeIf(String::isNotBlank)
      ?: return null
    return runCatching {
      Base64.getDecoder().decode(normalized)
    }.getOrNull()
  }

  private fun downloadAsset(
    url: String,
    authHeaders: Map<String, String>,
  ): OpenCrayBinaryAsset {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      connectTimeout = DEFAULT_TIMEOUT_MS
      readTimeout = DEFAULT_TIMEOUT_MS
      doInput = true
      setRequestProperty("Accept", "*/*")
      setRequestProperty("User-Agent", userAgent)
      authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      val response = readHttpResponse(connection)
      if (response.statusCode !in 200..299) {
        throw IllegalStateException(extractErrorMessage(response))
      }
      OpenCrayBinaryAsset(
        bytes = response.body,
        mimeType = normalizedContentType(response.contentType),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun extractErrorMessage(response: HttpResponse): String {
    val contentType = normalizedContentType(response.contentType)
    if (contentType == "application/json" || contentType?.endsWith("+json") == true) {
      runCatching {
        val payload = JSONObject(response.body.toString(StandardCharsets.UTF_8))
        payload.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
          ?: payload.optString("message").takeIf(String::isNotBlank)
      }.getOrNull()?.let { message ->
        return message
      }
    }
    val text = response.body.toString(StandardCharsets.UTF_8).trim()
    return text.ifBlank { "Provider returned HTTP ${response.statusCode}." }
  }

  private fun normalizedContentType(rawValue: String?): String? =
    rawValue
      ?.substringBefore(';')
      ?.trim()
      ?.lowercase(Locale.US)
      ?.takeIf(String::isNotBlank)

  private fun readStreamBytes(input: java.io.InputStream?): ByteArray {
    if (input == null) {
      return ByteArray(0)
    }
    return input.use { stream ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      val output = ByteArrayOutputStream()
      while (true) {
        val read = stream.read(buffer)
        if (read < 0) {
          break
        }
        if (read > 0) {
          output.write(buffer, 0, read)
        }
      }
      output.toByteArray()
    }
  }

  private fun buildEndpointUrl(
    baseUrl: String,
    endpoint: String,
  ): String {
    val trimmedEndpoint = endpoint.trim()
    if (trimmedEndpoint.startsWith("http://") || trimmedEndpoint.startsWith("https://")) {
      return trimmedEndpoint
    }
    val trimmedBaseUrl = baseUrl.trim().trimEnd('/')
    val normalizedEndpoint = trimmedEndpoint.trimStart('/')
    return when {
      trimmedBaseUrl.isBlank() -> normalizedEndpoint
      normalizedEndpoint.isBlank() -> trimmedBaseUrl
      else -> "$trimmedBaseUrl/$normalizedEndpoint"
    }
  }

  private data class HttpResponse(
    val statusCode: Int,
    val contentType: String?,
    val body: ByteArray,
    val headers: Map<String, String>,
  ) {
    fun providerRequestId(): String? = headers.values
      .asSequence()
      .map(String::trim)
      .firstOrNull(String::isNotBlank)
  }

  companion object {
    private const val DEFAULT_TIMEOUT_MS: Int = 60_000
  }
}
