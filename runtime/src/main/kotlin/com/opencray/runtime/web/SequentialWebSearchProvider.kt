package com.opencray.runtime.web

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val DEFAULT_WEB_SEARCH_USER_AGENT: String = "OpenCray-WebSearch/1.0"

data class ConfiguredWebSearchSlot(
  val providerId: String,
  val apiKey: String,
  val label: String = "",
  val enabled: Boolean = true,
) {
  fun sanitized(): ConfiguredWebSearchSlot = copy(
    providerId = providerId.trim().lowercase(),
    apiKey = apiKey.trim(),
    label = label.trim(),
    enabled = enabled,
  )
}

data class WebSearchHttpRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String> = emptyMap(),
  val body: String? = null,
  val connectTimeoutMs: Int = 15_000,
  val readTimeoutMs: Int = 15_000,
)

data class WebSearchHttpResponse(
  val statusCode: Int,
  val body: String,
)

interface WebSearchHttpTransport {
  fun execute(request: WebSearchHttpRequest): WebSearchHttpResponse
}

class HttpUrlWebSearchTransport(
  private val userAgent: String = DEFAULT_WEB_SEARCH_USER_AGENT,
) : WebSearchHttpTransport {
  override fun execute(request: WebSearchHttpRequest): WebSearchHttpResponse {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      connectTimeout = request.connectTimeoutMs
      readTimeout = request.readTimeoutMs
      instanceFollowRedirects = true
      doInput = true
      doOutput = !request.body.isNullOrEmpty()
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", userAgent)
      request.headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      request.body?.takeIf(String::isNotBlank)?.let { body ->
        connection.outputStream.use { output ->
          output.write(body.toByteArray(StandardCharsets.UTF_8))
        }
      }
      WebSearchHttpResponse(
        statusCode = connection.responseCode,
        body = readStream(
          input = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream,
        ),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun readStream(input: InputStream?): String {
    if (input == null) {
      return ""
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toString(StandardCharsets.UTF_8.name())
    }
  }
}

class SequentialWebSearchProvider(
  slots: List<ConfiguredWebSearchSlot>,
  private val transport: WebSearchHttpTransport = HttpUrlWebSearchTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true },
) : WebSearchProvider {
  private val configuredSlots = slots.map(ConfiguredWebSearchSlot::sanitized)

  override val providerName: String = "configured-web-search"

  override fun search(request: WebSearchRequest): WebSearchResult {
    val activeSlots = configuredSlots.filter { slot ->
      slot.enabled && slot.apiKey.isNotBlank()
    }
    if (activeSlots.isEmpty()) {
      return WebSearchResult(
        providerName = providerName,
        errorCode = "WEB_SEARCH_NOT_CONFIGURED",
        errorMessage = "No enabled web search slot has an API key.",
      )
    }

    val failures = mutableListOf<String>()
    activeSlots.forEach { slot ->
      when (val outcome = searchWithSlot(slot = slot, request = request)) {
        is ProviderSearchOutcome.Success -> {
          return WebSearchResult(
            providerName = slot.displayName(),
            results = outcome.hits,
          )
        }

        is ProviderSearchOutcome.Failure -> {
          failures += "${slot.displayName()}: ${outcome.message}"
        }
      }
    }

    return WebSearchResult(
      providerName = providerName,
      errorCode = "WEB_SEARCH_ALL_PROVIDERS_FAILED",
      errorMessage = failures.joinToString(separator = " | "),
    )
  }

  private fun searchWithSlot(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome = try {
    when (slot.providerId) {
      "exa" -> searchExa(slot, request)
      "tavily" -> searchTavily(slot, request)
      "brave" -> searchBrave(slot, request)
      else -> ProviderSearchOutcome.Failure("Unsupported provider '${slot.providerId}'.")
    }
  } catch (timeout: SocketTimeoutException) {
    ProviderSearchOutcome.Failure(timeout.message ?: "Request timed out.")
  } catch (exception: Exception) {
    ProviderSearchOutcome.Failure(exception.message ?: exception::class.java.simpleName)
  }

  private fun searchExa(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome {
    val response = transport.execute(
      WebSearchHttpRequest(
        method = "POST",
        url = "https://api.exa.ai/search",
        headers = mapOf(
          "Content-Type" to "application/json",
          "x-api-key" to slot.apiKey,
        ),
        body = buildJsonObject {
          put("query", request.query)
          put("numResults", request.maxResults)
        }.toString(),
      ),
    )
    if (response.statusCode !in 200..299) {
      return ProviderSearchOutcome.Failure(httpErrorMessage(response))
    }
    val payload = parseJsonObject(response.body)
    val hits = payload.arrayValue("results")
      ?.mapNotNull { result -> exaHit(result.jsonObject) }
      .orEmpty()
    return ProviderSearchOutcome.Success(hits)
  }

  private fun searchTavily(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome {
    val response = transport.execute(
      WebSearchHttpRequest(
        method = "POST",
        url = "https://api.tavily.com/search",
        headers = mapOf(
          "Content-Type" to "application/json",
        ),
        body = buildJsonObject {
          put("api_key", slot.apiKey)
          put("query", request.query)
          put("max_results", request.maxResults)
          put("search_depth", "basic")
        }.toString(),
      ),
    )
    if (response.statusCode !in 200..299) {
      return ProviderSearchOutcome.Failure(httpErrorMessage(response))
    }
    val payload = parseJsonObject(response.body)
    val hits = payload.arrayValue("results")
      ?.mapNotNull { result -> tavilyHit(result.jsonObject) }
      .orEmpty()
    return ProviderSearchOutcome.Success(hits)
  }

  private fun searchBrave(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome {
    val encodedQuery = URLEncoder.encode(request.query, StandardCharsets.UTF_8.name())
    val response = transport.execute(
      WebSearchHttpRequest(
        method = "GET",
        url = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=${request.maxResults}",
        headers = mapOf(
          "X-Subscription-Token" to slot.apiKey,
        ),
      ),
    )
    if (response.statusCode !in 200..299) {
      return ProviderSearchOutcome.Failure(httpErrorMessage(response))
    }
    val payload = parseJsonObject(response.body)
    val hits = payload.objectValue("web")
      ?.arrayValue("results")
      ?.mapNotNull { result -> braveHit(result.jsonObject) }
      .orEmpty()
    return ProviderSearchOutcome.Success(hits)
  }

  private fun exaHit(result: JsonObject): WebSearchHit? {
    val url = result.string("url").orEmpty().trim()
    if (url.isEmpty()) {
      return null
    }
    val title = result.string("title").orEmpty().trim().ifBlank { url }
    val snippet = firstNonBlank(
      result.string("text"),
      result.arrayValue("highlights")?.firstText(),
    )
    return WebSearchHit(
      title = title,
      url = url,
      snippet = snippet.normalizedSnippet(),
    )
  }

  private fun tavilyHit(result: JsonObject): WebSearchHit? {
    val url = result.string("url").orEmpty().trim()
    if (url.isEmpty()) {
      return null
    }
    val title = result.string("title").orEmpty().trim().ifBlank { url }
    return WebSearchHit(
      title = title,
      url = url,
      snippet = firstNonBlank(
        result.string("content"),
        result.string("raw_content"),
      ).normalizedSnippet(),
    )
  }

  private fun braveHit(result: JsonObject): WebSearchHit? {
    val url = result.string("url").orEmpty().trim()
    if (url.isEmpty()) {
      return null
    }
    val title = result.string("title").orEmpty().trim().ifBlank { url }
    return WebSearchHit(
      title = title,
      url = url,
      snippet = firstNonBlank(
        result.string("description"),
        result.string("snippet"),
      ).normalizedSnippet(),
    )
  }

  private fun parseJsonObject(source: String): JsonObject = json.parseToJsonElement(source).jsonObject

  private fun httpErrorMessage(response: WebSearchHttpResponse): String {
    val payload = runCatching { parseJsonObject(response.body) }.getOrNull()
    val detail = firstNonBlank(
      payload?.string("error"),
      payload?.objectValue("error")?.string("message"),
      payload?.string("message"),
      response.body.trim(),
    )
    return "HTTP ${response.statusCode}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
  }

  private fun JsonObject.string(key: String): String? =
    this[key]
      ?.takeIf { element -> element is JsonPrimitive }
      ?.jsonPrimitive
      ?.contentOrNull()

  private fun JsonObject.objectValue(key: String): JsonObject? =
    this[key] as? JsonObject

  private fun JsonObject.arrayValue(key: String): JsonArray? =
    this[key] as? JsonArray

  private fun JsonPrimitive.contentOrNull(): String? = content.trim().takeIf(String::isNotBlank)

  private fun JsonArray.firstText(): String? =
    firstOrNull()
      ?.takeIf { element -> element is JsonPrimitive }
      ?.jsonPrimitive
      ?.contentOrNull()

  private fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { value -> !value.isNullOrBlank() }.orEmpty()

  private fun String.normalizedSnippet(): String =
    replace(WHITESPACE_REGEX, " ").trim().take(MAX_SNIPPET_CHARS)

  private fun ConfiguredWebSearchSlot.displayName(): String =
    if (label.isBlank()) providerId else "$providerId:$label"

  private sealed interface ProviderSearchOutcome {
    data class Success(val hits: List<WebSearchHit>) : ProviderSearchOutcome

    data class Failure(val message: String) : ProviderSearchOutcome
  }

  private companion object {
    private const val MAX_SNIPPET_CHARS: Int = 280
    private val WHITESPACE_REGEX = Regex("\\s+")
  }
}
