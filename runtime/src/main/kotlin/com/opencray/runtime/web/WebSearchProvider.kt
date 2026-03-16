package com.opencray.runtime.web

data class WebSearchRequest(
  val query: String,
  val maxResults: Int = 5,
) {
  init {
    require(query.isNotBlank()) { "WebSearchRequest query must not be blank." }
    require(maxResults > 0) { "WebSearchRequest maxResults must be > 0." }
  }
}

data class WebSearchHit(
  val title: String,
  val url: String,
  val snippet: String = "",
) {
  init {
    require(title.isNotBlank()) { "WebSearchHit title must not be blank." }
    require(url.isNotBlank()) { "WebSearchHit url must not be blank." }
  }
}

data class WebSearchResult(
  val providerName: String,
  val results: List<WebSearchHit> = emptyList(),
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  init {
    require(providerName.isNotBlank()) { "WebSearchResult providerName must not be blank." }
  }

  val isSuccess: Boolean
    get() = errorCode == null
}

interface WebSearchProvider {
  val providerName: String

  fun search(request: WebSearchRequest): WebSearchResult
}

object UnconfiguredWebSearchProvider : WebSearchProvider {
  override val providerName: String = "unconfigured"

  override fun search(request: WebSearchRequest): WebSearchResult = WebSearchResult(
    providerName = providerName,
    errorCode = "WEB_SEARCH_NOT_CONFIGURED",
    errorMessage = "Web search provider is not configured.",
  )
}
