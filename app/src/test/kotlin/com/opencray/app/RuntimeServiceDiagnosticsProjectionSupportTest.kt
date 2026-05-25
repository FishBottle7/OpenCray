package com.opencray.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeServiceDiagnosticsProjectionSupportTest {
  @Test
  fun includesNullRuntimeServiceFieldsWhenRequested() {
    val snapshot = buildMap<String, Any?> {
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = HostRuntimeLifecycleDescriptor(hostInstanceId = "host-a"),
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
          controllerInstanceId = "controller-a",
        ),
        runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(hostInstanceId = "owner-a"),
        runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(activeRunCount = 2),
        includeNullRuntimeServiceFields = true,
      )
    }

    assertTrue(snapshot.containsKey("runtimeServiceLifecycle"))
    assertEquals(null, snapshot["runtimeServiceLifecycle"])
    assertTrue(snapshot.containsKey("runtimeServiceWorkState"))
    assertEquals(null, snapshot["runtimeServiceWorkState"])
    assertTrue(snapshot.containsKey("runtimeServiceKeepAliveState"))
    assertEquals(null, snapshot["runtimeServiceKeepAliveState"])
    assertTrue(snapshot.containsKey("runtimeServiceConnectionState"))
    assertEquals(null, snapshot["runtimeServiceConnectionState"])
    assertEquals("host-a", (snapshot["hostLifecycle"] as Map<*, *>)["hostInstanceId"])
    assertEquals(
      "controller-a",
      (snapshot["runtimeControllerLifecycle"] as Map<*, *>)["controllerInstanceId"],
    )
    assertEquals("owner-a", (snapshot["runtimeOwnerLifecycle"] as Map<*, *>)["hostInstanceId"])
  }

  @Test
  fun omitsMissingRuntimeServiceFieldsByDefault() {
    val snapshot = buildMap<String, Any?> {
      putRuntimeServiceDiagnosticsSnapshot(
        localRuntimeServerState = LocalRuntimeServerState(
          bindAddress = "127.0.0.1",
          requestedPort = 42617,
        ),
        hostLifecycle = HostRuntimeLifecycleDescriptor(hostInstanceId = "host-b"),
        runtimeOwnerWorkSummary = RuntimeOwnerWorkSummary(activeRunCount = 1),
        runtimeServiceConnectionState = RuntimeServiceConnectionState.bindingPending(),
      )
    }

    assertTrue(snapshot.containsKey("localRuntimeServerState"))
    assertTrue(snapshot.containsKey("runtimeServiceConnectionState"))
    assertFalse(snapshot.containsKey("runtimeServiceLifecycle"))
    assertFalse(snapshot.containsKey("runtimeServiceWorkState"))
    assertFalse(snapshot.containsKey("runtimeServiceKeepAliveState"))
    assertEquals("host-b", (snapshot["hostLifecycle"] as Map<*, *>)["hostInstanceId"])
  }
}
