package com.opencray.runtime.web

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

class HttpUrlWebContentFetcher(
  private val userAgent: String = DEFAULT_USER_AGENT,
) : WebContentFetcher {
  override fun fetch(request: WebFetchRequest): WebFetchResult {
    val normalizedUrl = request.url.trim()
    val parsedUrl = runCatching { URL(normalizedUrl) }.getOrNull()
      ?: return failedResult(
        request = request,
        errorCode = "WEB_FETCH_INVALID_URL",
        errorMessage = "WebFetch requires a valid absolute URL.",
      )
    val protocol = parsedUrl.protocol?.lowercase().orEmpty()
    if (protocol != "http" && protocol != "https") {
      return failedResult(
        request = request,
        errorCode = "WEB_FETCH_UNSUPPORTED_SCHEME",
        errorMessage = "WebFetch supports only http and https URLs.",
      )
    }

    val connection = runCatching {
      (parsedUrl.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        requestMethod = "GET"
        connectTimeout = request.connectTimeoutMs
        readTimeout = request.readTimeoutMs
        doInput = true
        setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
        setRequestProperty("User-Agent", userAgent)
      }
    }.getOrElse { error ->
      return failedResult(
        request = request,
        errorCode = "WEB_FETCH_TRANSPORT_ERROR",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    }

    return try {
      val statusCode = connection.responseCode
      val finalUrl = connection.url.toString()
      val rawContentType = connection.contentType?.trim().orEmpty()
      val contentType = rawContentType.substringBefore(';').trim().lowercase().ifBlank { null }
      val readResult = readLimitedBytes(
        input = if (statusCode in 200..299) connection.inputStream else connection.errorStream,
        maxBytes = request.maxBytes,
      )
      val decodedBody = decodeBytes(
        bytes = readResult.bytes,
        charsetName = rawContentType.substringAfter("charset=", "").substringBefore(';').trim(),
      )

      if (statusCode !in 200..299) {
        return WebFetchResult(
          requestedUrl = normalizedUrl,
          finalUrl = finalUrl,
          statusCode = statusCode,
          contentType = contentType,
          content = summarizeErrorBody(decodedBody),
          truncated = readResult.truncated,
          errorCode = "WEB_FETCH_HTTP_$statusCode",
          errorMessage = "Web request failed with HTTP $statusCode.",
        )
      }

      val extraction = extractContent(
        source = decodedBody,
        contentType = contentType,
        maxChars = request.maxChars,
      ) ?: return WebFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        contentType = contentType,
        errorCode = "WEB_FETCH_UNSUPPORTED_CONTENT_TYPE",
        errorMessage = "WebFetch supports HTML and plain text responses only.",
      )

      WebFetchResult(
        requestedUrl = normalizedUrl,
        finalUrl = finalUrl,
        statusCode = statusCode,
        contentType = contentType,
        title = extraction.title,
        content = extraction.content.ifBlank { "<empty page>" },
        truncated = readResult.truncated || extraction.truncated,
      )
    } catch (timeout: SocketTimeoutException) {
      failedResult(
        request = request,
        errorCode = "WEB_FETCH_TIMEOUT",
        errorMessage = timeout.message ?: "Web request timed out.",
      )
    } catch (error: Exception) {
      failedResult(
        request = request,
        errorCode = "WEB_FETCH_TRANSPORT_ERROR",
        errorMessage = error.message ?: error::class.java.simpleName,
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun extractContent(
    source: String,
    contentType: String?,
    maxChars: Int,
  ): ExtractedPageContent? {
    val normalizedType = contentType.orEmpty()
    return when {
      normalizedType.isEmpty() || normalizedType == "text/html" || normalizedType == "application/xhtml+xml" -> {
        val title = extractTitle(source)
        val text = normalizeVisibleText(
          decodeHtmlEntities(
            stripHtml(source),
          ),
        )
        val truncated = text.length > maxChars
        ExtractedPageContent(
          title = title,
          content = text.take(maxChars).ifBlank { "<empty page>" },
          truncated = truncated,
        )
      }

      normalizedType.startsWith("text/plain") -> {
        val normalized = normalizeVisibleText(source)
        val truncated = normalized.length > maxChars
        ExtractedPageContent(
          title = null,
          content = normalized.take(maxChars).ifBlank { "<empty page>" },
          truncated = truncated,
        )
      }

      else -> null
    }
  }

  private fun extractTitle(source: String): String? =
    TITLE_REGEX.find(source)
      ?.groupValues
      ?.getOrNull(1)
      ?.let(::decodeHtmlEntities)
      ?.replace(WHITESPACE_REGEX, " ")
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun stripHtml(source: String): String {
    var text = source
      .replace(COMMENT_REGEX, " ")
      .replace(SCRIPT_STYLE_REGEX, " ")
      .replace(BLOCK_BREAK_REGEX, "\n")
      .replace(TAG_REGEX, " ")
    text = text.replace('\u0000', ' ')
    return text
  }

  private fun decodeHtmlEntities(source: String): String =
    ENTITY_REGEX.replace(source) { match ->
      val token = match.groupValues[1]
      when {
        token.equals("amp", ignoreCase = true) -> "&"
        token.equals("lt", ignoreCase = true) -> "<"
        token.equals("gt", ignoreCase = true) -> ">"
        token.equals("quot", ignoreCase = true) -> "\""
        token.equals("apos", ignoreCase = true) -> "'"
        token.equals("nbsp", ignoreCase = true) -> " "
        token.startsWith("#x", ignoreCase = true) -> token.substring(2).toIntOrNull(16)?.toChar()?.toString()
        token.startsWith("#") -> token.substring(1).toIntOrNull()?.toChar()?.toString()
        else -> null
      } ?: match.value
    }

  private fun normalizeVisibleText(source: String): String {
    val normalizedLines = source
      .replace('\u00A0', ' ')
      .replace("\r", "\n")
      .lines()
      .map { line -> line.replace(WHITESPACE_REGEX, " ").trim() }

    val paragraphs = mutableListOf<String>()
    var blankPending = false
    normalizedLines.forEach { line ->
      if (line.isBlank()) {
        blankPending = paragraphs.isNotEmpty()
      } else {
        if (blankPending) {
          paragraphs += ""
        }
        paragraphs += line
        blankPending = false
      }
    }
    return paragraphs.joinToString(separator = "\n").trim()
  }

  private fun decodeBytes(
    bytes: ByteArray,
    charsetName: String,
  ): String {
    val charset = charsetName
      .takeIf(String::isNotBlank)
      ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
      ?: StandardCharsets.UTF_8
    return runCatching { bytes.toString(charset) }
      .getOrElse { bytes.toString(StandardCharsets.UTF_8) }
  }

  private fun readLimitedBytes(
    input: InputStream?,
    maxBytes: Int,
  ): LimitedByteReadResult {
    if (input == null) {
      return LimitedByteReadResult(bytes = ByteArray(0), truncated = false)
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
      var truncated = false
      while (true) {
        val read = stream.read(chunk)
        if (read <= 0) {
          break
        }
        val remaining = maxBytes - output.size()
        if (remaining <= 0) {
          truncated = true
          break
        }
        val bytesToWrite = minOf(read, remaining)
        output.write(chunk, 0, bytesToWrite)
        if (bytesToWrite < read) {
          truncated = true
          break
        }
      }
      return LimitedByteReadResult(
        bytes = output.toByteArray(),
        truncated = truncated,
      )
    }
  }

  private fun summarizeErrorBody(source: String): String {
    val normalized = normalizeVisibleText(
      decodeHtmlEntities(
        stripHtml(source),
      ),
    )
    return normalized.take(MAX_ERROR_PREVIEW_CHARS).ifBlank { "<empty response>" }
  }

  private fun failedResult(
    request: WebFetchRequest,
    errorCode: String,
    errorMessage: String,
  ): WebFetchResult = WebFetchResult(
    requestedUrl = request.url.trim(),
    finalUrl = request.url.trim(),
    errorCode = errorCode,
    errorMessage = errorMessage,
  )

  private data class LimitedByteReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
  )

  private data class ExtractedPageContent(
    val title: String?,
    val content: String,
    val truncated: Boolean,
  )

  private companion object {
    private const val DEFAULT_USER_AGENT: String = "OpenCray-WebFetcher/1.0"
    private const val MAX_ERROR_PREVIEW_CHARS: Int = 1_000
    private val TITLE_REGEX = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val COMMENT_REGEX = Regex("(?is)<!--.*?-->")
    private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style|noscript|svg|canvas).*?>.*?</\\1>")
    private val BLOCK_BREAK_REGEX = Regex("(?is)</?(address|article|aside|blockquote|br|dd|div|dl|dt|fieldset|figcaption|figure|footer|form|h[1-6]|header|hr|li|main|nav|ol|p|pre|section|table|td|th|tr|ul)[^>]*>")
    private val TAG_REGEX = Regex("(?is)<[^>]+>")
    private val ENTITY_REGEX = Regex("&(#x?[0-9A-Fa-f]+|[A-Za-z]{2,10});")
    private val WHITESPACE_REGEX = Regex("[\\t\\x0B\\f ]+")
  }
}
