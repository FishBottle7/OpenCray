package com.opencray.mcp

import com.opencray.core.contracts.McpAuthSpec
import com.opencray.core.contracts.McpServerSpec
import com.opencray.core.contracts.McpServerTrustState
import com.opencray.core.contracts.McpTransportDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagerTest {
  private val clientFactory = McpClientFactory()

  @Test
  fun RuntimeSupport_isExplicitlyExposureOnly() {
    assertEquals("exposure_only", McpRuntimeSupport.BRIDGE_STATUS_EXPOSURE_ONLY)
    assertFalse(McpRuntimeSupport.REMOTE_TOOL_BRIDGE_AVAILABLE)
    assertEquals(setOf("mcp_list_servers"), McpRuntimeSupport.SUPPORTED_AGENT_TOOL_NAMES)
    assertTrue(McpRuntimeSupport.bridgeSummary().contains("not callable yet"))
  }

  @Test
  fun ManualEnablePersistence_persistsAcrossRegistryReload() {
    var now = 1_710_000_000_000L
    val store = InMemoryMcpRegistryStore()
    val registry = McpRegistry(store) { now++ }
    val server = fixtureServer(id = "community-local")

    registry.add(server)

    val initiallyBlocked = clientFactory.load(registry)
    assertEquals(McpToolExposure.BLOCKED, initiallyBlocked.toolExposure(server.id))
    assertNull(initiallyBlocked.findActiveClient(server.id))

    val enabled = registry.manualEnable(server.id)
    assertTrue(enabled.manuallyEnabled)
    assertEquals(McpServerTrustState.ENABLED, enabled.trustState)

    val reloadedRegistry = McpRegistry(store) { now++ }
    val reloadedServer = requireNotNull(reloadedRegistry.get(server.id))
    assertTrue(reloadedServer.manuallyEnabled)
    assertEquals(McpServerTrustState.ENABLED, reloadedServer.trustState)

    val reloadedReport = clientFactory.load(reloadedRegistry)
    val activeClient = requireNotNull(reloadedReport.findActiveClient(server.id))
    assertEquals(McpToolExposure.ACTIVE, reloadedReport.toolExposure(server.id))
    assertNull(reloadedReport.findBlockedClient(server.id))
    assertEquals(server.id, activeClient.id)
    assertTrue(activeClient.manuallyEnabled)
    assertEquals(McpServerTrustState.ENABLED, activeClient.trustState)

    println(
      "MCP_EVIDENCE_HAPPY " +
        "serverId=${server.id} " +
        "declaredTrustState=${enabled.declaredTrustState} " +
        "initialExposure=${initiallyBlocked.toolExposure(server.id)} " +
        "persistedTrustState=${reloadedServer.trustState} " +
        "persistedManuallyEnabled=${reloadedServer.manuallyEnabled} " +
        "reloadedExposure=${reloadedReport.toolExposure(server.id)} " +
        "activeClientId=${activeClient.id} " +
        "authStatus=${activeClient.auth.status} " +
        "authReady=${activeClient.auth.isReady}",
    )
  }

  @Test
  fun UnknownServerNeedsManualEnable_staysBlockedWithoutPlaintextSecretField() {
    val store = InMemoryMcpRegistryStore()
    val registry = McpRegistry(store) { 1_710_000_100_000L }
    val server = fixtureServer(
      id = "unknown-local",
      auth = McpAuthSpec(credentialRef = "secret://mcp/unknown-token"),
    )

    registry.add(server)

    val report = clientFactory.load(registry)
    val blockedClient = requireNotNull(report.findBlockedClient(server.id))
    val authFieldNames = McpClientAuthDescriptor::class.java.declaredFields
      .filterNot { it.isSynthetic }
      .map { it.name }
      .toSet()

    assertNull(report.findActiveClient(server.id))
    assertEquals(McpToolExposure.BLOCKED, report.toolExposure(server.id))
    assertEquals(McpClientBlockReason.REQUIRES_MANUAL_ENABLE, blockedClient.blockReason)
    assertEquals(McpServerTrustState.REQUIRES_MANUAL_ENABLE, blockedClient.trustState)
    assertFalse(blockedClient.manuallyEnabled)
    assertEquals("secret://mcp/unknown-token", blockedClient.auth.credentialRef?.uri)
    assertEquals(
      setOf("status", "isReady", "credentialRef", "headerName", "errorCode"),
      authFieldNames,
    )
    assertTrue(authFieldNames.none { it.contains("secret", ignoreCase = true) })

    println(
      "MCP_EVIDENCE_BLOCKED " +
        "serverId=${server.id} " +
        "declaredTrustState=${blockedClient.declaredTrustState} " +
        "trustState=${blockedClient.trustState} " +
        "manuallyEnabled=${blockedClient.manuallyEnabled} " +
        "exposure=${report.toolExposure(server.id)} " +
        "blockReason=${blockedClient.blockReason} " +
        "authStatus=${blockedClient.auth.status} " +
        "authReady=${blockedClient.auth.isReady} " +
        "headerName=${blockedClient.auth.headerName} " +
        "credentialRef=${blockedClient.auth.credentialRef?.uri} " +
        "authFields=${authFieldNames.sorted()}",
    )
  }

  @Test
  fun RegistryMutations_mergeAgainstCurrentStoreRecord() {
    var now = 1_710_000_200_000L
    val store = InMemoryMcpRegistryStore()
    val firstRegistry = McpRegistry(store) { now++ }
    firstRegistry.add(fixtureServer(id = "first-local"))

    val secondRegistry = McpRegistry(store) { now++ }
    secondRegistry.add(fixtureServer(id = "second-local"))

    firstRegistry.add(fixtureServer(id = "third-local"))
    assertTrue(firstRegistry.remove("first-local"))

    val reloadedRegistry = McpRegistry(store) { now++ }
    assertEquals(
      listOf("second-local", "third-local"),
      reloadedRegistry.list().map(McpRegistryServerRecord::id),
    )
  }

  private fun fixtureServer(
    id: String,
    auth: McpAuthSpec? = null,
  ): McpServerSpec = McpServerSpec(
    id = id,
    displayName = "Fixture $id",
    transport = McpTransportDescriptor.LocalStdio(
      command = "fixture-mcp",
      args = listOf("--serve", id),
      environment = mapOf("FIXTURE_MODE" to "true"),
      workingDirectory = "/fixtures/mcp",
    ),
    trustState = McpServerTrustState.REQUIRES_MANUAL_ENABLE,
    auth = auth,
  )
}

// Learning: Reloading a fresh McpRegistry over the same in-memory store is enough to prove manual-enable persistence.
// Issue: The module has no concrete McpManager type yet, so this test covers the registry-factory contract directly.
// Learning: Evidence stays easy to audit when each scenario prints one tagged summary line with stable field order.
// Issue: Passing test stdout is hidden by default here until unit-test standard stream logging is enabled.
