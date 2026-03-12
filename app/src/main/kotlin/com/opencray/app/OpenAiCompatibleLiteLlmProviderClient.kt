package com.opencray.app

import com.opencray.llm.LiteLlmProviderClient
import com.opencray.llm.LiteLlmProviderRequest
import com.opencray.llm.LiteLlmProviderResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal class OpenAiCompatibleLiteLlmProviderClient : LiteLlmProviderClient {
  override fun execute(request: LiteLlmProviderRequest): LiteLlmProviderResult {
    val baseUrl = request.route.baseUrl?.trim().orEmpty()
    if (baseUrl.isEmpty()) {
      return LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_BASE_URL_MISSING",
        errorMessage = "Provider route baseUrl is required for OpenAI-compatible requests.",
      )
    }

    val endpoint = buildEndpointUrl(baseUrl)
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"
      connectTimeout = request.route.timeoutMs.toInt()
      readTimeout = request.route.timeoutMs.toInt()
      doInput = true
      doOutput = true
      setRequestProperty("Content-Type", "application/json")
      setRequestProperty("Accept", "application/json")
      request.request.authHeaders.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }

    return try {
      val body = buildRequestBody(request)
      connection.outputStream.use { output ->
        output.write(body.toByteArray(StandardCharsets.UTF_8))
      }

      val responseCode = connection.responseCode
      val responseText = readStream(
        input = if (responseCode in 200..299) connection.inputStream else connection.errorStream,
      )

      when {
        responseCode == 429 -> LiteLlmProviderResult.RateLimited(
          retryAfterMs = parseRetryAfterMillis(connection.getHeaderField("Retry-After")),
          errorMessage = extractErrorMessage(responseText).ifBlank { "Provider returned HTTP 429." },
          metadata = mapOf("statusCode" to responseCode.toString()),
        )

        responseCode !in 200..299 -> LiteLlmProviderResult.Failure(
          errorCode = "HTTP_$responseCode",
          errorMessage = extractErrorMessage(responseText).ifBlank { "Provider returned HTTP $responseCode." },
          metadata = mapOf("statusCode" to responseCode.toString()),
        )

        else -> {
          val parsed = JSONObject(responseText)
          val choice = parsed.optJSONArray("choices")?.optJSONObject(0)
          val finishReason = choice?.optString("finish_reason")?.takeIf { it.isNotBlank() }
          val content = extractMessageContent(choice)
          if (content.isBlank()) {
            LiteLlmProviderResult.Failure(
              errorCode = "PROVIDER_EMPTY_RESPONSE",
              errorMessage = "Provider returned an empty completion payload.",
            )
          } else {
            LiteLlmProviderResult.Success(
              outputText = content,
              finishReason = finishReason,
              metadata = buildMap {
                put("statusCode", responseCode.toString())
                parsed.optString("id").takeIf { it.isNotBlank() }?.let { put("providerRequestId", it) }
              },
            )
          }
        }
      }
    } catch (timeout: java.net.SocketTimeoutException) {
      LiteLlmProviderResult.Timeout(
        errorMessage = timeout.message ?: "Provider request timed out.",
      )
    } catch (exception: Exception) {
      LiteLlmProviderResult.Failure(
        errorCode = "PROVIDER_TRANSPORT_ERROR",
        errorMessage = exception.message ?: exception::class.java.simpleName,
        metadata = mapOf("exceptionType" to exception::class.java.name),
      )
    } finally {
      connection.disconnect()
    }
  }

  private fun buildEndpointUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    return when {
      trimmed.endsWith("/chat/completions") -> trimmed
      trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
      else -> "$trimmed/chat/completions"
    }
  }

  private fun buildRequestBody(request: LiteLlmProviderRequest): String {
    val payload = JSONObject()
      .put("model", request.route.model)
      .put(
        "messages",
        JSONArray().apply {
          request.request.systemPrompt?.takeIf { it.isNotBlank() }?.let { systemPrompt ->
            put(
              JSONObject()
                .put("role", "system")
                .put("content", systemPrompt),
            )
          }
          put(
            JSONObject()
              .put("role", "user")
              .put("content", request.request.prompt),
          )
        },
      )

    request.route.metadata["temperature"]?.toDoubleOrNull()?.let { payload.put("temperature", it) }
    request.route.metadata["max_tokens"]?.toIntOrNull()?.let { payload.put("max_tokens", it) }
    return payload.toString()
  }

  private fun extractMessageContent(choice: JSONObject?): String {
    if (choice == null) return ""
    val message = choice.optJSONObject("message") ?: return ""
    val content = message.opt("content")
    return when (content) {
      is String -> content
      is JSONArray -> {
        buildString {
          for (index in 0 until content.length()) {
            val segment = content.opt(index)
            when (segment) {
              is JSONObject -> {
                val text = segment.optString("text")
                if (text.isNotBlank()) {
                  append(text)
                }
              }

              is String -> append(segment)
            }
          }
        }
      }

      else -> ""
    }
  }

  private fun extractErrorMessage(responseText: String): String = runCatching {
    val errorObject = JSONObject(responseText).optJSONObject("error")
    errorObject?.optString("message")?.takeIf { it.isNotBlank() } ?: responseText
  }.getOrDefault(responseText)

  private fun parseRetryAfterMillis(rawValue: String?): Long? {
    val seconds = rawValue?.trim()?.toLongOrNull() ?: return null
    return seconds * 1_000L
  }

  private fun readStream(input: InputStream?): String {
    if (input == null) return ""
    return BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
      buildString {
        var line = reader.readLine()
        while (line != null) {
          append(line)
          line = reader.readLine()
          if (line != null) {
            append('\n')
          }
        }
      }
    }
  }
}
