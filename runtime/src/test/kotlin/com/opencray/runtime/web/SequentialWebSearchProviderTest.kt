package com.opencray.runtime.web

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialWebSearchProviderTest {
  @Test
  fun returnsNotConfiguredWhenNoEnabledKeysExist() {
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(
          providerId = "exa",
          apiKey = "",
          enabled = true,
        ),
      ),
      transport = FakeWebSearchHttpTransport(),
    )

    val result = provider.search(WebSearchRequest(query = "opencray"))

    assertEquals(false, result.isSuccess)
    assertEquals("WEB_SEARCH_NOT_CONFIGURED", result.errorCode)
  }

  @Test
  fun fallsBackToNextProviderWhenFirstSlotFails() {
    val transport = FakeWebSearchHttpTransport(
      responses = mapOf(
        "https://api.exa.ai/search" to WebSearchHttpResponse(
          statusCode = 401,
          body = """{"error":"invalid key"}""",
        ),
        "https://api.tavily.com/search" to WebSearchHttpResponse(
          statusCode = 200,
          body = """{"results":[{"title":"OpenCray Docs","url":"https://example.com/docs","content":"Tool reference"}]}""",
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(providerId = "exa", apiKey = "bad-key", label = "Primary"),
        ConfiguredWebSearchSlot(providerId = "tavily", apiKey = "good-key", label = "Backup"),
      ),
      transport = transport,
    )

    val result = provider.search(WebSearchRequest(query = "opencray", maxResults = 3))

    assertTrue(result.isSuccess)
    assertEquals("tavily:Backup", result.providerName)
    assertEquals(1, result.results.size)
    assertEquals("OpenCray Docs", result.results.single().title)
  }

  @Test
  fun parsesBraveWebResults() {
    val transport = FakeWebSearchHttpTransport(
      responses = mapOf(
        "https://api.search.brave.com/res/v1/web/search?q=opencray&count=2" to WebSearchHttpResponse(
          statusCode = 200,
          body = """{"web":{"results":[{"title":"OpenCray","url":"https://example.com","description":"Search runtime docs"}]}}""",
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(providerId = "brave", apiKey = "brave-key"),
      ),
      transport = transport,
    )

    val result = provider.search(WebSearchRequest(query = "opencray", maxResults = 2))

    assertTrue(result.isSuccess)
    assertEquals("brave", result.providerName)
    assertEquals("Search runtime docs", result.results.single().snippet)
  }

  @Test
  fun exaIncludesDomainFilterInRequestBody() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://api.exa.ai/search" to WebSearchHttpResponse(
          statusCode = 200,
          body = """{"results":[{"title":"Docs","url":"https://docs.example.com/opencray","text":"Reference"}]}""",
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(ConfiguredWebSearchSlot(providerId = "exa", apiKey = "exa-key")),
      transport = transport,
    )

    provider.search(
      WebSearchRequest(
        query = "opencray",
        maxResults = 3,
        domains = listOf("docs.example.com", "example.com"),
      ),
    )

    val request = transport.requests.single()
    val payload = Json.parseToJsonElement(request.body.orEmpty()).toString()
    assertTrue(payload.contains("includeDomains"))
    assertTrue(payload.contains("docs.example.com"))
    assertTrue(payload.contains("example.com"))
  }

  @Test
  fun tavilyIncludesDomainFilterInRequestBody() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://api.tavily.com/search" to WebSearchHttpResponse(
          statusCode = 200,
          body = """{"results":[{"title":"Docs","url":"https://example.com/docs","content":"Reference"}]}""",
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(ConfiguredWebSearchSlot(providerId = "tavily", apiKey = "tavily-key")),
      transport = transport,
    )

    provider.search(
      WebSearchRequest(
        query = "opencray",
        maxResults = 3,
        domains = listOf("example.com"),
      ),
    )

    val request = transport.requests.single()
    val payload = Json.parseToJsonElement(request.body.orEmpty()).toString()
    assertTrue(payload.contains("include_domains"))
    assertTrue(payload.contains("example.com"))
  }

  @Test
  fun braveFiltersResultsToRequestedDomainsLocally() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://api.search.brave.com/res/v1/web/search?q=opencray&count=8" to WebSearchHttpResponse(
          statusCode = 200,
          body = """{"web":{"results":[
            {"title":"Off domain","url":"https://news.other.com/post","description":"Ignore"},
            {"title":"On domain","url":"https://docs.example.com/opencray","description":"Keep"}
          ]}}""",
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(ConfiguredWebSearchSlot(providerId = "brave", apiKey = "brave-key")),
      transport = transport,
    )

    val result = provider.search(
      WebSearchRequest(
        query = "opencray",
        maxResults = 2,
        domains = listOf("example.com"),
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals(1, result.results.size)
    assertEquals("https://docs.example.com/opencray", result.results.single().url)
    assertFalse(result.results.any { hit -> hit.url.contains("other.com") })
    assertEquals(
      "https://api.search.brave.com/res/v1/web/search?q=opencray&count=8",
      transport.requests.single().url,
    )
  }

  private class FakeWebSearchHttpTransport(
    private val responses: Map<String, WebSearchHttpResponse> = emptyMap(),
  ) : WebSearchHttpTransport {
    override fun execute(request: WebSearchHttpRequest): WebSearchHttpResponse =
      responses[request.url] ?: error("No fake response registered for ${request.url}")
  }

  private class RecordingWebSearchHttpTransport(
    private val responses: Map<String, WebSearchHttpResponse>,
  ) : WebSearchHttpTransport {
    val requests = mutableListOf<WebSearchHttpRequest>()

    override fun execute(request: WebSearchHttpRequest): WebSearchHttpResponse {
      requests += request
      return responses[request.url] ?: error("No fake response registered for ${request.url}")
    }
  }
}
