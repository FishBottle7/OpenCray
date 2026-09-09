package com.opencray.app.facade.mcp

import com.opencray.app.AppSecretManager
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import com.opencray.mcp.InMemoryMcpRegistryStore
import com.opencray.mcp.McpRegistry
import com.opencray.mcp.McpServerAuthStatus
import com.opencray.persistence.security.CredentialRef
import com.opencray.persistence.security.SecretValue
import com.opencray.persistence.security.SecretVault
import com.opencray.persistence.security.SecretVaultStorageClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpServerRegistryEditorTest {
  @Test
  fun addServerRegistersUntrustedRemoteHttpServerAndKeepsTokenInVaultOnly() {
    val vault = RecordingVault()
    val registry = newRegistry()
    val editor = editor(registry, vault)

    editor.addServer(
      serverId = "search-demo",
      displayName = "Search demo",
      url = "https://demo.example.com/mcp",
      authHeaderName = null,
      authToken = "plain-secret-token",
    )

    val record = registry.get("search-demo")
    assertTrue(record != null)
    record!!
    assertEquals("Search demo", record.spec.displayName)
    val transport = record.spec.transport
    assertTrue(transport is McpTransportDescriptor.RemoteHttp)
    assertEquals("https://demo.example.com/mcp", (transport as McpTransportDescriptor.RemoteHttp).url)
    assertEquals(McpServerTrustState.REQUIRES_MANUAL_ENABLE, record.trustState)
    assertEquals(McpServerAuthStatus.CONFIGURED, record.authState.status)
    assertEquals("secret://mcp/search-demo/token", record.authState.credentialRef?.uri)
    assertEquals("Authorization", record.authState.headerName)

    // Plaintext token only lives inside the vault; the registry keeps the ref.
    val ref = record.authState.credentialRef
    assertEquals("plain-secret-token", vault.stored[ref]?.revealUtf8())
    val persistedRecord = registry.list().first { it.id == "search-demo" }
    assertTrue(persistedRecord.toString().contains("secret://mcp/search-demo/token"))
    assertFalse(persistedRecord.toString().contains("plain-secret-token"))
  }

  @Test
  fun addServerWithoutTokenKeepsAuthNotRequired() {
    val registry = newRegistry()
    val editor = editor(registry, RecordingVault())

    editor.addServer(
      serverId = "open-server",
      displayName = "Open server",
      url = "https://open.example.com/mcp",
      authHeaderName = null,
      authToken = null,
    )

    val record = registry.get("open-server")!!
    assertEquals(McpServerAuthStatus.NOT_REQUIRED, record.authState.status)
    assertNull(record.authState.credentialRef)
  }

  @Test
  fun addServerRejectsDuplicateIdAndInvalidUrl() {
    val registry = newRegistry()
    val editor = editor(registry, RecordingVault())

    editor.addServer(
      serverId = "dup",
      displayName = "First",
      url = "https://one.example.com/mcp",
      authHeaderName = null,
      authToken = null,
    )

    try {
      editor.addServer(
        serverId = "dup",
        displayName = "Second",
        url = "https://two.example.com/mcp",
        authHeaderName = null,
        authToken = null,
      )
      fail("Expected duplicate server id to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message!!.contains("already registered"))
    }

    try {
      editor.addServer(
        serverId = "bad-url",
        displayName = "Bad url",
        url = "ftp://example.com/mcp",
        authHeaderName = null,
        authToken = null,
      )
      fail("Expected non-http URL to be rejected.")
    } catch (expected: IllegalArgumentException) {
      assertTrue(expected.message!!.contains("valid http(s) URL"))
    }
  }

  @Test
  fun removeServerDropsRegistryRecordAndVaultSecret() {
    val vault = RecordingVault()
    val registry = newRegistry()
    val editor = editor(registry, vault)

    editor.addServer(
      serverId = "temp",
      displayName = "Temp",
      url = "https://temp.example.com/mcp",
      authHeaderName = null,
      authToken = "temp-token",
    )
    editor.removeServer("temp")

    assertNull(registry.get("temp"))
    assertNull(vault.stored[CredentialRef("secret://mcp/temp/token")])
    assertTrue(vault.deleted.contains(CredentialRef("secret://mcp/temp/token")))
  }

  @Test
  fun setServerCredentialRotatesVaultSecretWithoutTouchingTrustState() {
    val vault = RecordingVault()
    val registry = newRegistry()
    val editor = editor(registry, vault)

    editor.addServer(
      serverId = "auth-server",
      displayName = "Auth server",
      url = "https://auth.example.com/mcp",
      authHeaderName = null,
      authToken = "first-token",
    )
    registry.manualEnable("auth-server")
    assertEquals(McpServerTrustState.ENABLED, registry.get("auth-server")!!.trustState)

    editor.setServerCredential(
      serverId = "auth-server",
      authHeaderName = "X-Api-Key",
      authToken = "second-token",
    )

    val record = registry.get("auth-server")!!
    assertEquals(McpServerTrustState.ENABLED, record.trustState)
    val transport = record.spec.transport
    assertTrue(transport is McpTransportDescriptor.RemoteHttp)
    assertEquals("https://auth.example.com/mcp", (transport as McpTransportDescriptor.RemoteHttp).url)
    assertEquals(McpServerAuthStatus.CONFIGURED, record.authState.status)
    assertEquals("X-Api-Key", record.authState.headerName)
    val ref = record.authState.credentialRef!!
    assertEquals("second-token", vault.stored[ref]?.revealUtf8())
    // One write from addServer plus one from the rotation, all to the same ref.
    assertEquals(2, vault.writes.size)
    assertTrue(vault.writes.all { it == ref })
  }

  @Test
  fun setServerCredentialWithBlankTokenClearsAuth() {
    val registry = newRegistry()
    val editor = editor(registry, RecordingVault())

    editor.addServer(
      serverId = "clear-auth",
      displayName = "Clear auth",
      url = "https://clear.example.com/mcp",
      authHeaderName = null,
      authToken = "old-token",
    )
    editor.setServerCredential(
      serverId = "clear-auth",
      authHeaderName = "Authorization",
      authToken = null,
    )

    val record = registry.get("clear-auth")!!
    assertEquals(McpServerAuthStatus.NOT_REQUIRED, record.authState.status)
    assertNull(record.authState.credentialRef)
  }

  private fun newRegistry(): McpRegistry = McpRegistry(InMemoryMcpRegistryStore()) { 1_710_000_000_000L }

  private fun editor(
    registry: McpRegistry,
    vault: RecordingVault,
  ): McpServerRegistryEditor =
    McpServerRegistryEditor(
      registryProvider = { registry },
      secretManager = AppSecretManager(vault = vault),
    )

  private class RecordingVault : SecretVault {
    val stored = mutableMapOf<CredentialRef, SecretValue>()
    val writes = mutableListOf<CredentialRef>()
    val deleted = mutableListOf<CredentialRef>()

    override val storageClass: SecretVaultStorageClass = SecretVaultStorageClass.TEST_IN_MEMORY

    override fun put(ref: CredentialRef, secret: SecretValue) {
      stored[ref] = secret
      writes += ref
    }

    override fun get(ref: CredentialRef): SecretValue? = stored[ref]

    override fun delete(ref: CredentialRef): Boolean {
      deleted += ref
      return stored.remove(ref) != null
    }
  }
}
