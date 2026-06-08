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
        hostLifecycle = HostRuntimeLifecycleDescriptor(
          hostInstanceId = "host-a",
          durableRuntimeControllerId = "durable-controller-a",
        ),
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
          controllerInstanceId = "controller-a",
          durableControllerId = "durable-controller-a",
        ),
        runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
          hostInstanceId = "owner-a",
          durableRuntimeControllerId = "durable-controller-a",
        ),
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
    assertEquals(
      "durable-controller-a",
      (snapshot["runtimeControllerLifecycle"] as Map<*, *>)["durableControllerId"],
    )
    assertEquals("owner-a", (snapshot["runtimeOwnerLifecycle"] as Map<*, *>)["hostInstanceId"])
    assertEquals(
      "durable-controller-a",
      (snapshot["runtimeOwnerLifecycle"] as Map<*, *>)["durableRuntimeControllerId"],
    )
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

  @Test
  fun lifecycleMetadataCarriesDurableRuntimeControllerIdSeparately() {
    val lifecycle = HostRuntimeLifecycleDescriptor(
      processStartId = "process-a",
      hostInstanceId = "host-a",
      runtimeOwnerId = "owner-a",
      runtimeControllerId = "controller-instance-a",
      durableRuntimeControllerId = "controller-durable-a",
    )

    val metadata = lifecycle.taskMetadata(submissionSource = "test_submit")
    val diagnostics = runLifecycleDiagnosticsFrom(metadata)

    assertEquals(
      "controller-instance-a",
      metadata[RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_ID],
    )
    assertEquals(
      "controller-durable-a",
      metadata[RunLifecycleMetadataKeys.DURABLE_RUNTIME_CONTROLLER_ID],
    )
    assertEquals("controller-instance-a", diagnostics.runtimeControllerId)
    assertEquals("controller-durable-a", diagnostics.durableRuntimeControllerId)
    assertEquals("controller-durable-a", diagnostics.toMap()["durableRuntimeControllerId"])
  }
}
