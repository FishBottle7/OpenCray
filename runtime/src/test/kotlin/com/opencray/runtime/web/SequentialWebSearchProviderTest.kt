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

  @Test
  fun openAiWebSearchUsesConfiguredBaseUrlAndMapsCitations() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://proxy.example.com/v1/responses" to WebSearchHttpResponse(
          statusCode = 200,
          body = """
          {
            "output": [
              {
                "type": "web_search_call",
                "action": {
                  "sources": [
                    { "type": "url", "url": "https://docs.example.com/guide" }
                  ]
                }
              },
              {
                "type": "message",
                "content": [
                  {
                    "type": "output_text",
                    "text": "OpenCray guide summary.",
                    "annotations": [
                      {
                        "type": "url_citation",
                        "title": "OpenCray Guide",
                        "url": "https://docs.example.com/guide"
                      }
                    ]
                  }
                ]
              }
            ]
          }
          """.trimIndent(),
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(
          providerId = "openai_web_search",
          baseUrl = "https://proxy.example.com/v1",
          model = "gpt-5-mini",
          apiKey = "openai-key",
        ),
      ),
      transport = transport,
    )

    val result = provider.search(
      WebSearchRequest(
        query = "opencray latest docs",
        maxResults = 3,
        domains = listOf("docs.example.com"),
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals("openai_web_search", result.providerName)
    assertEquals(1, result.results.size)
    assertEquals("OpenCray Guide", result.results.single().title)
    assertEquals("https://docs.example.com/guide", result.results.single().url)

    val request = transport.requests.single()
    assertEquals("https://proxy.example.com/v1/responses", request.url)
    val payload = Json.parseToJsonElement(request.body.orEmpty()).toString()
    assertTrue(payload.contains("\"model\":\"gpt-5-mini\""))
    assertTrue(payload.contains("\"type\":\"web_search\""))
    assertTrue(payload.contains("\"allowed_domains\":[\"docs.example.com\"]"))
    assertTrue(payload.contains("\"include\":[\"web_search_call.action.sources\"]"))
  }

  @Test
  fun openAiWebSearchParsesNestedTextObjectAnnotations() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://api.openai.com/v1/responses" to WebSearchHttpResponse(
          statusCode = 200,
          body = """
          {
            "output": [
              {
                "type": "message",
                "role": "assistant",
                "content": [
                  {
                    "type": "output_text",
                    "text": {
                      "value": "Nested text annotation summary.",
                      "annotations": [
                        {
                          "type": "url_citation",
                          "title": "Nested Guide",
                          "url": "https://docs.example.com/nested"
                        }
                      ]
                    }
                  }
                ]
              }
            ]
          }
          """.trimIndent(),
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(
          providerId = "openai_web_search",
          apiKey = "openai-key",
        ),
      ),
      transport = transport,
    )

    val result = provider.search(
      WebSearchRequest(
        query = "nested output text",
        maxResults = 3,
        domains = listOf("docs.example.com"),
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals(1, result.results.size)
    assertEquals("Nested Guide", result.results.single().title)
    assertEquals("https://docs.example.com/nested", result.results.single().url)
    assertEquals("Nested text annotation summary.", result.results.single().snippet)
  }

  @Test
  fun openAiWebSearchParsesSingleContentObjectResponsesShape() {
    val transport = RecordingWebSearchHttpTransport(
      responses = mapOf(
        "https://api.openai.com/v1/responses" to WebSearchHttpResponse(
          statusCode = 200,
          body = """
          {
            "output": [
              {
                "type": "message",
                "role": "assistant",
                "content": {
                  "type": "output_text",
                  "text": "Single object content summary.",
                  "annotations": [
                    {
                      "type": "url_citation",
                      "title": "Single Object Guide",
                      "url": "https://docs.example.com/object"
                    }
                  ]
                }
              }
            ]
          }
          """.trimIndent(),
        ),
      ),
    )
    val provider = SequentialWebSearchProvider(
      slots = listOf(
        ConfiguredWebSearchSlot(
          providerId = "openai_web_search",
          apiKey = "openai-key",
        ),
      ),
      transport = transport,
    )

    val result = provider.search(
      WebSearchRequest(
        query = "single object content",
        maxResults = 3,
        domains = listOf("docs.example.com"),
      ),
    )

    assertTrue(result.isSuccess)
    assertEquals(1, result.results.size)
    assertEquals("Single Object Guide", result.results.single().title)
    assertEquals("https://docs.example.com/object", result.results.single().url)
    assertEquals("Single object content summary.", result.results.single().snippet)
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
