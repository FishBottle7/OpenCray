package com.opencray.runtime.web

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put

private const val DEFAULT_WEB_SEARCH_USER_AGENT: String = "OpenCray-WebSearch/1.0"

data class ConfiguredWebSearchSlot(
  val providerId: String,
  val baseUrl: String = "",
  val model: String = "",
  val apiKey: String,
  val label: String = "",
  val enabled: Boolean = true,
) {
  fun sanitized(): ConfiguredWebSearchSlot = copy(
    providerId = providerId.trim().lowercase(),
    baseUrl = baseUrl.trim(),
    model = model.trim(),
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
    val normalizedRequest = request.normalized()
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
      when (val outcome = searchWithSlot(slot = slot, request = normalizedRequest)) {
        is ProviderSearchOutcome.Success -> {
          return WebSearchResult(
            providerName = slot.displayName(),
            results = outcome.hits,
            summaryText = outcome.summaryText,
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
      "openai_web_search" -> searchOpenAiWebSearch(slot, request)
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
          if (request.domains.isNotEmpty()) {
            putJsonArray("includeDomains") {
              request.domains.forEach { domain -> add(JsonPrimitive(domain)) }
            }
          }
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
    return ProviderSearchOutcome.Success(request.filterHits(hits))
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
          if (request.domains.isNotEmpty()) {
            putJsonArray("include_domains") {
              request.domains.forEach { domain -> add(JsonPrimitive(domain)) }
            }
          }
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
    return ProviderSearchOutcome.Success(request.filterHits(hits))
  }

  private fun searchBrave(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome {
    val encodedQuery = URLEncoder.encode(request.query, StandardCharsets.UTF_8.name())
    val requestedCount = request.providerFetchResultCount()
    val response = transport.execute(
      WebSearchHttpRequest(
        method = "GET",
        url = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$requestedCount",
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
    return ProviderSearchOutcome.Success(request.filterHits(hits))
  }

  private fun searchOpenAiWebSearch(
    slot: ConfiguredWebSearchSlot,
    request: WebSearchRequest,
  ): ProviderSearchOutcome {
    val response = transport.execute(
      WebSearchHttpRequest(
        method = "POST",
        url = openAiResponsesEndpoint(slot.baseUrl),
        headers = mapOf(
          "Content-Type" to "application/json",
          "Authorization" to "Bearer ${slot.apiKey}",
        ),
        body = buildJsonObject {
          put("model", slot.model.ifBlank { DEFAULT_OPENAI_WEB_SEARCH_MODEL })
          put("input", request.query)
          put("tool_choice", "required")
          putJsonArray("tools") {
            add(
              buildJsonObject {
                put("type", "web_search")
                if (request.domains.isNotEmpty()) {
                  put(
                    "filters",
                    buildJsonObject {
                      putJsonArray("allowed_domains") {
                        request.domains.forEach { domain ->
                          add(JsonPrimitive(domain))
                        }
                      }
                    },
                  )
                }
              },
            )
          }
          putJsonArray("include") {
            add(JsonPrimitive("web_search_call.action.sources"))
          }
        }.toString(),
      ),
    )
    if (response.statusCode !in 200..299) {
      return ProviderSearchOutcome.Failure(httpErrorMessage(response))
    }
    val payload = parseJsonObject(response.body)
    val parsed = openAiWebSearchParseResult(payload)
    return ProviderSearchOutcome.Success(
      hits = request.filterHits(parsed.hits),
      summaryText = parsed.summaryText,
    )
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

  private fun openAiWebSearchParseResult(payload: JsonObject): OpenAiWebSearchParseResult {
    val output: List<JsonElement> = payload.arrayValue("output").orEmpty()
    val summaryText = firstNonBlank(
      responsesOutputText(output),
      payload.string("output_text"),
      payload.objectValue("output_text")?.string("value"),
    ).normalizedSnippet()
    val byUrl = linkedMapOf<String, WebSearchHit>()
    output.forEach { element ->
      val item = element as? JsonObject ?: return@forEach
      when (item.string("type")) {
        "web_search_call" -> {
          val sources = item.objectValue("action")?.arrayValue("sources")
            ?: item.arrayValue("sources")
          sources.orEmpty().forEach sourceLoop@{ source ->
            val sourceObject = source as? JsonObject ?: return@sourceLoop
            val url = sourceObject.string("url").orEmpty().trim()
            if (url.isEmpty()) {
              return@sourceLoop
            }
            byUrl.putIfAbsent(
              url,
              WebSearchHit(
                title = sourceObject.string("title").orEmpty().ifBlank { url },
                url = url,
                snippet = summaryText,
              ),
            )
          }
        }

        "message" -> {
          responsesMessageContentObjects(item).forEach contentLoop@{ content ->
            content.responseAnnotations().forEach annotationLoop@{ annotationElement ->
              val annotation = annotationElement as? JsonObject ?: return@annotationLoop
              if (annotation.string("type") != "url_citation") {
                return@annotationLoop
              }
              val url = annotation.string("url").orEmpty().trim()
              if (url.isEmpty()) {
                return@annotationLoop
              }
              byUrl[url] = WebSearchHit(
                title = annotation.string("title").orEmpty().ifBlank {
                  byUrl[url]?.title ?: url
                },
                url = url,
                snippet = byUrl[url]?.snippet?.takeIf(String::isNotBlank) ?: summaryText,
              )
            }
          }
        }
      }
    }
    return OpenAiWebSearchParseResult(
      hits = byUrl.values.toList(),
      summaryText = summaryText,
    )
  }

  private fun responsesOutputText(output: List<JsonElement>): String = buildString {
    output.forEach { element ->
      val item = element as? JsonObject ?: return@forEach
      if (item.string("type") != "message") {
        return@forEach
      }
      val textSegments = buildList<String> {
        responsesMessageContentObjects(item).forEach { content ->
          responseContentText(content)
            .takeIf(String::isNotBlank)
            ?.let(::add)
        }
        if (isEmpty()) {
          firstNonBlank(
            item.string("content"),
            item.objectValue("content")?.let(::responseContentText),
            item.string("text"),
            item.objectValue("text")?.string("value"),
          ).takeIf(String::isNotBlank)?.let(::add)
        }
      }
      textSegments.forEach { text ->
        if (isNotEmpty()) {
          append(' ')
        }
        append(text)
      }
    }
  }

  private fun responsesMessageContentObjects(message: JsonObject): List<JsonObject> {
    val directArray = message.arrayValue("content")
      ?.mapNotNull { element -> element as? JsonObject }
      .orEmpty()
    if (directArray.isNotEmpty()) {
      return directArray
    }
    message.objectValue("content")?.let { contentObject ->
      return listOf(contentObject)
    }
    return emptyList()
  }

  private fun responseContentText(content: JsonObject): String = firstNonBlank(
    content.string("text"),
    content.string("output_text"),
    content.string("summary_text"),
    content.string("content"),
    content.string("value"),
    content.objectValue("text")?.string("value"),
    content.objectValue("text")?.string("text"),
  )

  private fun JsonObject.responseAnnotations(): List<JsonElement> = buildList {
    arrayValue("annotations")?.let(::addAll)
    objectValue("text")?.arrayValue("annotations")?.let(::addAll)
  }

  private fun parseJsonObject(source: String): JsonObject = json.parseToJsonElement(source).jsonObject

  private fun WebSearchRequest.normalized(): WebSearchRequest = copy(
    query = query.trim(),
    domains = domains
      .mapNotNull(::normalizeDomain)
      .distinct(),
  )

  private fun WebSearchRequest.providerFetchResultCount(): Int =
    if (domains.isEmpty()) {
      maxResults
    } else {
      (maxResults * BRAVE_DOMAIN_FETCH_MULTIPLIER).coerceAtMost(MAX_PROVIDER_FETCH_RESULTS)
    }

  private fun WebSearchRequest.filterHits(hits: List<WebSearchHit>): List<WebSearchHit> {
    if (domains.isEmpty()) {
      return hits.take(maxResults)
    }
    return hits
      .filter { hit -> hit.matchesAnyDomain(domains) }
      .take(maxResults)
  }

  private fun WebSearchHit.matchesAnyDomain(domains: List<String>): Boolean {
    val host = extractHost(url) ?: return false
    return domains.any { domain ->
      host == domain || host.endsWith(".$domain")
    }
  }

  private fun extractHost(rawUrl: String): String? = runCatching {
    URI(rawUrl.trim()).host
      ?.trim()
      ?.lowercase()
      ?.removePrefix(".")
      ?.takeIf(String::isNotBlank)
  }.getOrNull()

  private fun normalizeDomain(rawDomain: String): String? {
    val trimmed = rawDomain.trim()
    if (trimmed.isEmpty()) {
      return null
    }
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val host = runCatching { URI(withScheme).host }.getOrNull()
      ?.trim()
      ?.lowercase()
      ?.removePrefix("www.")
      ?.removePrefix(".")
      ?.takeIf(String::isNotBlank)
    return host
  }

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

  private fun openAiResponsesEndpoint(baseUrl: String): String {
    val trimmedBaseUrl = baseUrl.trim().ifBlank { DEFAULT_OPENAI_RESPONSES_BASE_URL }.trimEnd('/')
    return when {
      trimmedBaseUrl.endsWith("/v1/responses") -> trimmedBaseUrl
      trimmedBaseUrl.endsWith("/v1") -> "$trimmedBaseUrl/responses"
      else -> "$trimmedBaseUrl/v1/responses"
    }
  }

  private fun ConfiguredWebSearchSlot.displayName(): String =
    if (label.isBlank()) providerId else "$providerId:$label"

  private sealed interface ProviderSearchOutcome {
    data class Success(
      val hits: List<WebSearchHit>,
      val summaryText: String = "",
    ) : ProviderSearchOutcome

    data class Failure(val message: String) : ProviderSearchOutcome
  }

  private data class OpenAiWebSearchParseResult(
    val hits: List<WebSearchHit>,
    val summaryText: String,
  )

  private companion object {
    private const val MAX_SNIPPET_CHARS: Int = 280
    private const val MAX_PROVIDER_FETCH_RESULTS: Int = 20
    private const val BRAVE_DOMAIN_FETCH_MULTIPLIER: Int = 4
    private const val DEFAULT_OPENAI_RESPONSES_BASE_URL: String = "https://api.openai.com/v1"
    private const val DEFAULT_OPENAI_WEB_SEARCH_MODEL: String = "gpt-5"
    private val WHITESPACE_REGEX = Regex("\\s+")
  }
}
