package com.opencray.core.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class McpServerTrustState {
  @SerialName("disabled") DISABLED,
  @SerialName("enabled") ENABLED,
  @SerialName("requires_manual_enable") REQUIRES_MANUAL_ENABLE,
}

@Serializable
data class McpAuthSpec(
  val credentialRef: String,
  val headerName: String = "Authorization",
) {
  init {
    require(credentialRef.isNotBlank()) { "McpAuthSpec credentialRef must not be blank." }
    require(headerName.isNotBlank()) { "McpAuthSpec headerName must not be blank." }
  }
}

@Serializable
sealed class McpTransportDescriptor {
  @Serializable
  @SerialName("local_stdio")
  data class LocalStdio(
    val command: String,
    val args: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val startupTimeoutMs: Long = 15_000L,
  ) : McpTransportDescriptor() {
    init {
      require(command.isNotBlank()) { "MCP local stdio command must not be blank." }
      require(startupTimeoutMs > 0) { "MCP local stdio startupTimeoutMs must be > 0." }
    }
  }

  @Serializable
  @SerialName("remote_http")
  data class RemoteHttp(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val protocolVersion: String = "2025-11-05",
    val requestTimeoutMs: Long = 30_000L,
  ) : McpTransportDescriptor() {
    init {
      require(url.isHttpOrHttps()) { "MCP remote http url must start with http:// or https://" }
      require(requestTimeoutMs > 0) { "MCP remote http requestTimeoutMs must be > 0." }
    }
  }

  @Serializable
  @SerialName("remote_sse")
  data class RemoteSse(
    val eventsUrl: String,
    val postUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val protocolVersion: String = "2025-11-05",
    val requestTimeoutMs: Long = 30_000L,
  ) : McpTransportDescriptor() {
    init {
      require(eventsUrl.isHttpOrHttps()) {
        "MCP remote sse eventsUrl must start with http:// or https://"
      }
      require(postUrl.isHttpOrHttps()) {
        "MCP remote sse postUrl must start with http:// or https://"
      }
      require(requestTimeoutMs > 0) { "MCP remote sse requestTimeoutMs must be > 0." }
    }
  }
}

@Serializable
data class McpServerSpec(
  val id: String,
  val displayName: String,
  val transport: McpTransportDescriptor,
  val trustState: McpServerTrustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
  val auth: McpAuthSpec? = null,
  val metadata: Map<String, String> = emptyMap(),
  val schemaVersion: Int = ContractSchemaVersion.CURRENT,
) {
  init {
    require(id.isNotBlank()) { "MCP server id must not be blank." }
    require(displayName.isNotBlank()) { "MCP server displayName must not be blank." }
  }
}

private fun String.isHttpOrHttps(): Boolean = startsWith("http://") || startsWith("https://")
