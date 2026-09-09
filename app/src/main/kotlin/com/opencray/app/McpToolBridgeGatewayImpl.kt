package com.opencray.app

import com.opencray.mcp.JvmMcpHttpEndpoint
import com.opencray.mcp.McpConnectionManager
import com.opencray.mcp.McpConnectionState
import com.opencray.mcp.McpConnectionSummary
import com.opencray.mcp.McpRegistryServerRecord
import com.opencray.mcp.McpRegistry
import com.opencray.mcp.McpToolBridgeGateway
import com.opencray.mcp.McpToolBridgeResult
import com.opencray.mcp.McpToolDescriptor
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.SecretVault
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject

/**
 * App-side MCP tool bridge: lazily connects ENABLED RemoteHttp servers through
 * McpConnectionManager and reuses the live connection for every proxy call.
 *
 * Registry snapshots come from the provider so trust-state flips (manual
 * enable/disable in settings) take effect without rebuilding the gateway.
 * Credentials resolve from the keystore-backed vault at connect time only and
 * are materialized exclusively inside the transport request headers.
 *
 * Learning: All failures are returned as error codes instead of thrown so
 * the runtime tool layer can map them to registered user-facing codes.
 */
internal class McpToolBridgeGatewayImpl(
  private val registryProvider: () -> McpRegistry,
  private val vault: SecretVault,
) : McpToolBridgeGateway {
  private data class LiveConnection(
    val manager: McpConnectionManager,
    var discoveredToolCount: Int = 0,
  )

  private val requestIdCounter = AtomicLong(1)
  private val connections = mutableMapOf<String, LiveConnection>()
  private val lastErrors = mutableMapOf<String, String>()
  private val lock = Any()

  override fun listTools(serverId: String): List<McpToolDescriptor> {
    val connection = connect(serverId) ?: return emptyList()
    return runCatching { connection.manager.listTools() }
      .getOrElse { throwable ->
        recordError(serverId, throwable.message)
        closeQuietly(serverId)
        return emptyList()
      }
      .also { tools ->
        connection.discoveredToolCount = tools.size
      }
  }

  override fun callTool(
    serverId: String,
    remoteToolName: String,
    arguments: JsonObject,
  ): McpToolBridgeResult {
    val connection = connect(serverId)
      ?: return McpToolBridgeResult(
        success = false,
        content = failureMessage(serverId, "MCP server '$serverId' is not available."),
        errorCode = "MCP_SERVER_UNAVAILABLE",
      )
    return try {
      val result = connection.manager.callTool(
        toolName = remoteToolName,
        arguments = arguments,
      )
      if (result.isError) {
        McpToolBridgeResult(
          success = false,
          content = result.primaryText(),
          errorCode = "MCP_TOOL_CALL_FAILED",
        )
      } else {
        McpToolBridgeResult(
          success = true,
          content = result.primaryText(),
        )
      }
    } catch (rejected: com.opencray.mcp.McpConnectionRejectedException) {
      closeQuietly(serverId)
      McpToolBridgeResult(
        success = false,
        content = rejected.message,
        errorCode = if (rejected.reason.startsWith("trust_state_")) {
          "MCP_SERVER_NOT_ENABLED"
        } else {
          "MCP_CONNECTION_REJECTED"
        },
      )
    } catch (transport: com.opencray.mcp.McpHttpException) {
      recordError(serverId, transport.message)
      closeQuietly(serverId)
      McpToolBridgeResult(
        success = false,
        content = transport.message,
        errorCode = "MCP_TOOL_CALL_FAILED",
      )
    } catch (error: Throwable) {
      recordError(serverId, error.message)
      closeQuietly(serverId)
      McpToolBridgeResult(
        success = false,
        content = error.message ?: "MCP tool call failed.",
        errorCode = "MCP_TOOL_CALL_FAILED",
      )
    }
  }

  override fun connectionSummaries(): List<McpConnectionSummary> {
    val records = enabledRemoteHttpRecords()
    return records.map { record ->
      val live = synchronized(lock) { connections[record.id] }
      McpConnectionSummary(
        serverId = record.id,
        state = if (live != null) McpConnectionState.CONNECTED else McpConnectionState.DISCONNECTED,
        connectedToolCount = live?.discoveredToolCount ?: 0,
        lastError = synchronized(lock) { lastErrors[record.id] },
      )
    }
  }

  private fun connect(serverId: String): LiveConnection? {
    synchronized(lock) { connections[serverId] }?.let { return it }
    val record = enabledRemoteHttpRecords().firstOrNull { it.id == serverId }
      ?: run {
        recordError(serverId, "MCP server '$serverId' is not enabled or has no remote HTTP transport.")
        return null
      }
    val manager = McpConnectionManager(
      serverId = serverId,
      credentialResolver = { ref -> vault.resolveSecret(ref) },
      nextRequestId = requestIdCounter::incrementAndGet,
    )
    return try {
      val snapshot = manager.connect(
        record = record,
        endpoint = JvmMcpHttpEndpoint(),
      )
      val live = LiveConnection(manager = manager, discoveredToolCount = snapshot.discoveredTools.size)
      synchronized(lock) {
        connections[serverId] = live
        lastErrors.remove(serverId)
      }
      live
    } catch (rejected: com.opencray.mcp.McpConnectionRejectedException) {
      recordError(serverId, rejected.message)
      null
    } catch (failure: Throwable) {
      recordError(serverId, failure.message)
      null
    }
  }

  private fun enabledRemoteHttpRecords(): List<McpRegistryServerRecord> =
    registryProvider().list().filter { server ->
      server.trustState == com.opencray.core.contracts.McpServerTrustState.ENABLED &&
        server.spec.transport is com.opencray.core.contracts.McpTransportDescriptor.RemoteHttp
    }

  private fun recordError(serverId: String, message: String?) {
    val trimmed = message?.trim()?.takeIf(String::isNotBlank) ?: "Unknown MCP bridge failure."
    synchronized(lock) {
      lastErrors[serverId] = trimmed
      connections.remove(serverId)
    }
  }

  private fun closeQuietly(serverId: String) {
    val live = synchronized(lock) { connections.remove(serverId) }
    runCatching { live?.manager?.close() }
  }

  private fun failureMessage(serverId: String, fallback: String): String =
    synchronized(lock) { lastErrors[serverId] }?.let { "$fallback Last error: $it" } ?: fallback
}

private fun SecretVault.resolveSecret(ref: CredentialRef): String? =
  get(ref)?.revealUtf8()
