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
    assertTrue(snapshot.containsKey("runtimeServiceOwnerLease"))
    assertEquals(null, snapshot["runtimeServiceOwnerLease"])
    assertTrue(snapshot.containsKey("runtimeServiceInterruptedRunRepair"))
    assertEquals(null, snapshot["runtimeServiceInterruptedRunRepair"])
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
  fun includesRuntimeExecutionOwnershipFromLifecycleAndServiceProcess() {
    val snapshot = buildMap<String, Any?> {
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = HostRuntimeLifecycleDescriptor(
          processStartId = "main-process",
          hostInstanceId = "host-main",
        ),
        runtimeControllerLifecycle = RuntimeControllerLifecycleDescriptor(
          processStartId = "runtime-process",
          controllerInstanceId = "controller-runtime",
          durableControllerId = "durable-runtime",
        ),
        runtimeOwnerLifecycle = HostRuntimeLifecycleDescriptor(
          processStartId = "runtime-process",
          hostInstanceId = "owner-runtime",
          runtimeOwnerId = "owner-runtime",
          runtimeControllerId = "controller-runtime",
          durableRuntimeControllerId = "durable-runtime",
        ),
        runtimeServiceLifecycle = RuntimeServiceLifecycleDescriptor(
          processStartId = "runtime-process",
          serviceInstanceId = "service-runtime",
          serviceProcess = RuntimeServiceProcessDescriptor(
            packageName = "org.opencray.app",
            processName = "org.opencray.app:runtime",
            expectedProcessName = "org.opencray.app:runtime",
            isDedicatedRuntimeProcess = true,
          ),
        ),
      )
    }

    val ownership = snapshot["runtimeExecutionOwnership"] as Map<*, *>
    assertEquals("runtime_process", ownership["ownershipTier"])
    assertEquals(false, ownership["controllerProcessSeparate"])
    assertEquals("runtime-process", ownership["executionProcessStartId"])
    assertEquals("runtime-process", ownership["runtimeOwnerProcessStartId"])
    assertEquals("runtime-process", ownership["runtimeControllerProcessStartId"])
    assertEquals("runtime-process", ownership["runtimeServiceProcessStartId"])
    assertEquals("org.opencray.app:runtime", ownership["runtimeServiceProcessName"])
    assertEquals("org.opencray.app:runtime", ownership["expectedRuntimeServiceProcessName"])
    assertEquals(true, ownership["dedicatedRuntimeServiceProcess"])
    assertFalse(ownership.containsKey("runtimeServiceProcessMismatchReason"))
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
    assertFalse(snapshot.containsKey("runtimeServiceOwnerLease"))
    assertFalse(snapshot.containsKey("runtimeServiceInterruptedRunRepair"))
    assertEquals("host-b", (snapshot["hostLifecycle"] as Map<*, *>)["hostInstanceId"])
  }

  @Test
  fun includesRuntimeServiceOwnerLeaseWhenProvided() {
    val snapshot = buildMap<String, Any?> {
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = HostRuntimeLifecycleDescriptor(hostInstanceId = "host-lease"),
        runtimeServiceOwnerLease = RuntimeServiceOwnerLease(
          target = RuntimeServiceTarget.DETACHED_BACKGROUND,
          processStartId = "process-lease",
          processStartedAtEpochMs = 1_000L,
          controllerInstanceId = "controller-lease",
          durableControllerId = "durable-controller-lease",
          runtimeOwnerId = "owner-lease",
          runtimeControllerId = "controller-lease",
          durableRuntimeControllerId = "durable-controller-lease",
          serviceInstanceId = "service-lease",
          serviceProcessName = "org.opencray.app:runtime",
          acquiredAtEpochMs = 2_000L,
          heartbeatAtEpochMs = 2_500L,
          expiresAtEpochMs = 32_500L,
          lastAcquireFailure = RuntimeServiceOwnerLeaseAcquireFailure(
            target = RuntimeServiceTarget.DETACHED_BACKGROUND,
            attemptedAtEpochMs = 2_750L,
            attemptedProcessStartId = "process-contender",
            attemptedControllerInstanceId = "controller-contender",
            attemptedDurableControllerId = "durable-controller-lease",
            attemptedRuntimeOwnerId = "owner-contender",
            attemptedRuntimeControllerId = "controller-contender",
            attemptedDurableRuntimeControllerId = "durable-controller-lease",
            attemptedServiceInstanceId = "service-contender",
            attemptedServiceProcessName = "org.opencray.app:runtime",
            holderRuntimeOwnerId = "owner-lease",
            holderControllerInstanceId = "controller-lease",
            holderDurableControllerId = "durable-controller-lease",
            holderServiceInstanceId = "service-lease",
            holderHeartbeatAtEpochMs = 2_500L,
            holderExpiresAtEpochMs = 32_500L,
          ),
        ),
      )
    }

    val lease = snapshot["runtimeServiceOwnerLease"] as Map<*, *>
    assertEquals("detached_background", lease["target"])
    assertEquals("held", lease["phase"])
    assertEquals("owner-lease", lease["runtimeOwnerId"])
    assertEquals("durable-controller-lease", lease["durableControllerId"])
    assertEquals("service-lease", lease["serviceInstanceId"])
    assertEquals(2_500L, lease["heartbeatAtEpochMs"])
    assertEquals(32_500L, lease["expiresAtEpochMs"])
    val failure = lease["lastAcquireFailure"] as Map<*, *>
    assertEquals("owner_lease_held", failure["reason"])
    assertEquals("owner-contender", failure["attemptedRuntimeOwnerId"])
    assertEquals("owner-lease", failure["holderRuntimeOwnerId"])
    assertEquals("service-contender", failure["attemptedServiceInstanceId"])
    assertEquals(2_750L, failure["attemptedAtEpochMs"])
  }

  @Test
  fun includesInterruptedRunRepairProjectionWhenProvided() {
    val snapshot = buildMap<String, Any?> {
      putRuntimeServiceDiagnosticsSnapshot(
        hostLifecycle = HostRuntimeLifecycleDescriptor(hostInstanceId = "host-repair"),
        runtimeServiceInterruptedRunRepair = RuntimeServiceInterruptedRunRepairProjection(
          scannedSessionIds = listOf("session-a"),
          resumedSessionIds = listOf("session-a"),
          repairedSessionIds = listOf("session-a"),
          repairEvidenceBySession = mapOf(
            "session-a" to listOf(
              InterruptedRunRepairEvidence(
                sessionId = "session-a",
                kind = InterruptedRunRepairEvidenceKind.PROMPT_CHECKPOINT,
                target = RuntimeServiceTarget.INTERACTIVE,
                runId = "run-a",
                taskId = "task-a",
                detailId = "checkpoint-a",
                repairAfterEpochMs = 9_500L,
              ),
            ),
          ),
          nextRepairAfterEpochMs = 9_500L,
          recordedAtEpochMs = 9_000L,
        ),
      )
    }

    val repair = snapshot["runtimeServiceInterruptedRunRepair"] as Map<*, *>
    assertEquals(9_000L, repair["recordedAtEpochMs"])
    assertEquals(listOf("session-a"), repair["scannedSessionIds"])
    assertEquals(listOf("session-a"), repair["resumedSessionIds"])
    assertEquals(9_500L, repair["nextRepairAfterEpochMs"])
    val evidenceBySession = repair["repairEvidenceBySession"] as Map<*, *>
    val evidence = (evidenceBySession["session-a"] as List<*>).single() as Map<*, *>
    assertEquals("prompt_checkpoint", evidence["kind"])
    assertEquals("interactive", evidence["target"])
    assertEquals("checkpoint-a", evidence["detailId"])
    assertEquals(9_500L, evidence["repairAfterEpochMs"])
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
    assertEquals(
      "runtime_process",
      metadata[RunLifecycleMetadataKeys.RUNTIME_EXECUTION_OWNERSHIP_TIER],
    )
    assertEquals(
      false.toString(),
      metadata[RunLifecycleMetadataKeys.RUNTIME_CONTROLLER_PROCESS_SEPARATE],
    )
    assertEquals("controller-instance-a", diagnostics.runtimeControllerId)
    assertEquals("controller-durable-a", diagnostics.durableRuntimeControllerId)
    assertEquals("runtime_process", diagnostics.runtimeExecutionOwnershipTier)
    assertFalse(requireNotNull(diagnostics.runtimeControllerProcessSeparate))
    assertEquals("controller-durable-a", diagnostics.toMap()["durableRuntimeControllerId"])
    assertEquals("runtime_process", diagnostics.toMap()["runtimeExecutionOwnershipTier"])
    assertEquals(false, diagnostics.toMap()["runtimeControllerProcessSeparate"])
  }
}
