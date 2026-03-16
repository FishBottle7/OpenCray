package com.opencray.runtime.web

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

  private class FakeWebSearchHttpTransport(
    private val responses: Map<String, WebSearchHttpResponse> = emptyMap(),
  ) : WebSearchHttpTransport {
    override fun execute(request: WebSearchHttpRequest): WebSearchHttpResponse =
      responses[request.url] ?: error("No fake response registered for ${request.url}")
  }
}
