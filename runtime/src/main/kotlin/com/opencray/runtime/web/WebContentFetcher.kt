package com.opencray.runtime.web

data class WebFetchRequest(
  val url: String,
  val maxChars: Int = 12_000,
  val maxBytes: Int = 256_000,
  val connectTimeoutMs: Int = 10_000,
  val readTimeoutMs: Int = 15_000,
) {
  init {
    require(url.isNotBlank()) { "WebFetchRequest url must not be blank." }
    require(maxChars > 0) { "WebFetchRequest maxChars must be > 0." }
    require(maxBytes > 0) { "WebFetchRequest maxBytes must be > 0." }
    require(connectTimeoutMs > 0) { "WebFetchRequest connectTimeoutMs must be > 0." }
    require(readTimeoutMs > 0) { "WebFetchRequest readTimeoutMs must be > 0." }
  }
}

data class WebFetchResult(
  val requestedUrl: String,
  val finalUrl: String = requestedUrl,
  val statusCode: Int? = null,
  val contentType: String? = null,
  val title: String? = null,
  val content: String = "",
  val truncated: Boolean = false,
  val errorCode: String? = null,
  val errorMessage: String? = null,
) {
  init {
    require(requestedUrl.isNotBlank()) { "WebFetchResult requestedUrl must not be blank." }
    require(finalUrl.isNotBlank()) { "WebFetchResult finalUrl must not be blank." }
  }

  val isSuccess: Boolean
    get() = errorCode == null
}

interface WebContentFetcher {
  fun fetch(request: WebFetchRequest): WebFetchResult
}
