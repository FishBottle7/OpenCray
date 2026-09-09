package com.opencray.app.facade.mcp

import com.opencray.app.AppSecretManager
import com.opencray.core.contracts.McpAuthSpec
import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.mcp.McpRegistry
import com.opencray.mcp.McpRegistryServerRecord
import com.opencray.persistence.security.CredentialRef
import java.net.URI

/**
 * Registry mutations for the add-server settings surface: register a remote
 * HTTP server, remove it, or rotate its credential.
 *
 * Pure Kotlin (no Android Context) so the credential round trip is unit
 * testable on the JVM. Tokens are stored through the vault and only the
 * credential reference is ever written into the registry.
 */
internal class McpServerRegistryEditor(
  private val registryProvider: () -> McpRegistry,
  private val secretManager: AppSecretManager?,
) {
  fun addServer(
    serverId: String,
    displayName: String,
    url: String,
    authHeaderName: String?,
    authToken: String?,
  ): McpRegistryServerRecord {
    val registry = registryProvider()
    require(registry.get(serverId) == null) {
      "MCP server id '$serverId' is already registered."
    }
    require(serverId.isNotBlank()) { "MCP server id must not be blank." }
    val trimmedName = displayName.trim()
    require(trimmedName.isNotEmpty()) { "MCP server display name must not be blank." }
    return registry.add(
      McpServerSpec(
        id = serverId,
        displayName = trimmedName,
        transport = McpTransportDescriptor.RemoteHttp(
          url = requireHttpUrl(url),
        ),
        // Newly added servers always start untrusted; the user enables them
        // explicitly from the server card.
        trustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
        auth = persistCredential(serverId, authHeaderName, authToken),
      ),
    )
  }

  fun removeServer(serverId: String) {
    val registry = registryProvider()
    val record = registry.get(serverId)
      ?: error("Unknown MCP server '$serverId'.")
    record.authState.credentialRef?.let { ref ->
      val vault = secretManager ?: error("Secret vault is unavailable in this environment.")
      vault.delete(ref)
    }
    check(registry.remove(serverId)) { "Unknown MCP server '$serverId'." }
  }

  fun setServerCredential(
    serverId: String,
    authHeaderName: String,
    authToken: String?,
  ): McpRegistryServerRecord {
    val registry = registryProvider()
    val record = registry.get(serverId)
      ?: error("Unknown MCP server '$serverId'.")
    val nextAuth = persistCredential(serverId, authHeaderName, authToken)
    // refreshSpec keeps transport and declared trust state; a previously
    // manually-enabled server stays enabled across credential rotation.
    return registry.add(record.spec.copy(auth = nextAuth))
  }

  /**
   * Stores the token in the vault when present and returns the auth spec
   * carrying only the credential reference. Blank tokens keep auth unset so
   * the server connects without credentials.
   */
  private fun persistCredential(
    serverId: String,
    authHeaderName: String?,
    authToken: String?,
  ): McpAuthSpec? {
    val token = authToken?.trim().orEmpty()
    if (token.isEmpty()) {
      return null
    }
    val vault = secretManager ?: error("Secret vault is unavailable in this environment.")
    vault.storeUtf8(credentialRefFor(serverId), token)
    return McpAuthSpec(
      credentialRef = credentialRefFor(serverId).uri,
      // McpAuthSpec defaults the header to Authorization when blank.
      headerName = authHeaderName?.trim()?.takeIf { it.isNotEmpty() } ?: "Authorization",
    )
  }

  private fun credentialRefFor(serverId: String): CredentialRef =
    CredentialRef("secret://mcp/$serverId/token")

  private fun requireHttpUrl(url: String): String {
    val trimmed = url.trim()
    val parsed = runCatching { URI(trimmed) }.getOrNull()
    val valid = parsed?.scheme in listOf("http", "https") && !parsed?.host.isNullOrBlank()
    require(valid) { "MCP server URL must be a valid http(s) URL." }
    return trimmed
  }
}
