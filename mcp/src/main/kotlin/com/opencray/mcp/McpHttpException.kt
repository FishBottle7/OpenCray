package com.opencray.mcp

/**
 * Normalized failure for the MCP client HTTP layer.
 *
 * Learning: One exception type across timeout / IO / HTTP status failures keeps
 * the connection manager free of transport-specific catch branches.
 */
class McpHttpException(
  val statusCode: Int? = null,
  override val message: String,
  val causeCode: String = statusCode?.let { "http_$it" } ?: "transport",
) : Exception(message) {
  companion object {
    const val TRANSPORT_TIMEOUT: String = "transport_timeout"
    const val TRANSPORT_IO: String = "transport_io"
  }

  constructor(
    message: String,
    cause: Throwable,
    causeCode: String = TRANSPORT_IO,
  ) : this(statusCode = null, message = message, causeCode = causeCode) {
    initCause(cause)
  }
}
