package com.opencray.app

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.opencray.app.ipc.IRuntimeServiceController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeServiceProcessIsolationTest {
  private val bindings = mutableListOf<ServiceBinding>()

  @After
  fun tearDown() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    bindings.toList().asReversed().forEach { binding ->
      runCatching { context.unbindService(binding) }
    }
    bindings.clear()
  }

  @Test(timeout = 90_000L)
  fun targetServicesExposeV2RemoteControllersFromIndependentProcesses() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val interactive = bindController(context, RuntimeServiceTarget.INTERACTIVE)
    val detached = bindController(context, RuntimeServiceTarget.DETACHED_BACKGROUND)

    assertRemoteController(interactive, RuntimeServiceTarget.INTERACTIVE)
    assertRemoteController(detached, RuntimeServiceTarget.DETACHED_BACKGROUND)

    val interactiveSnapshot = loadProjection(interactive.controller)
    val detachedSnapshot = loadProjection(detached.controller)
    val interactiveProcessName = "${context.packageName}:runtime"
    val detachedProcessName = "${context.packageName}:runtime_controller"
    val interactivePid = awaitProcessPid(context, interactiveProcessName)
    val detachedPid = awaitProcessPid(context, detachedProcessName)

    assertEquals(
      interactiveProcessName,
      interactiveSnapshot.serviceLifecycle.serviceProcess?.processName,
    )
    assertEquals(
      detachedProcessName,
      detachedSnapshot.serviceLifecycle.serviceProcess?.processName,
    )
    assertTrue(interactivePid != detachedPid)
    assertTrue(interactivePid != Process.myPid())
    assertTrue(detachedPid != Process.myPid())

    val writeResponse = interactive.controller.dispatchWriteCommandJson(
      encodeRuntimeServiceWriteCommand(
        runtimeServiceWriteCommandEnvelope(
          OpenCrayChatWriteCommand.RefreshSandboxSessionInfo,
        ),
      ),
    )
    assertEquals(
      OpenCrayChatWriteDispatchResult.Completed,
      decodeRuntimeServiceChatWriteResult(writeResponse),
    )
  }

  @Test(timeout = 120_000L)
  fun detachedControllerRebindsAfterProcessDeathAndOwnerLeaseExpiry() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val processName = "${context.packageName}:runtime_controller"
    val first = bindController(context, target)
    val firstSnapshot = loadProjection(first.controller)
    val firstPid = awaitProcessPid(context, processName)

    Process.killProcess(firstPid)
    unbind(context, first.binding)
    awaitProcessExit(context, processName, firstPid)

    val leaseStore = FileBackedRuntimeServiceOwnerLeaseStore.fromContext(context)
    val staleLease = awaitLease(
      store = leaseStore,
      target = target,
      processStartId = firstSnapshot.runtimeOwnerLifecycle.processStartId,
    )
    val waitMs = (staleLease.expiresAtEpochMs - System.currentTimeMillis() + 1_000L)
      .coerceAtLeast(0L)
    if (waitMs > 0L) {
      Thread.sleep(waitMs)
    }

    assertTrue(
      openCrayRuntimeServiceEnvironment(context)
        .runtimeServiceAccessGateway
        .resumeInterruptedRuns(
          context = context,
          repairReason = ScheduledTaskRepairReasons.OWNER_LEASE_EXPIRED,
          target = target,
        ),
    )

    val second = bindController(context, target)
    val secondSnapshot = loadProjection(second.controller)
    val secondPid = awaitProcessPid(context, processName)

    assertTrue(secondPid != firstPid)
    assertTrue(
      secondSnapshot.runtimeOwnerLifecycle.processStartId !=
        firstSnapshot.runtimeOwnerLifecycle.processStartId,
    )
    assertNotNull(firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId)
    assertEquals(
      firstSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
      secondSnapshot.runtimeOwnerLifecycle.durableRuntimeControllerId,
    )
    assertEquals(target.wireValue, second.controller.runtimeTarget)
  }

  @Test(timeout = 90_000L)
  fun detachedScheduleMutationRoutesWorkToMainProcessWorkManager() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val target = RuntimeServiceTarget.DETACHED_BACKGROUND
    val scheduleId = "runtime-isolation-${System.nanoTime()}"
    val sessionId = "runtime-isolation-session"
    val nowEpochMs = System.currentTimeMillis()
    val specStore = FileBackedScheduledTaskSpecStoreFactory.fromContext(context).create()
    val workManager = WorkManager.getInstance(context)
    specStore.upsert(
      ScheduledTaskSpec(
        scheduleId = scheduleId,
        sessionId = sessionId,
        title = "Runtime isolation schedule",
        enabled = true,
        trigger = ScheduledTrigger.At(atEpochMs = nowEpochMs + TimeUnit.HOURS.toMillis(1L)),
        payload = ScheduledTaskPayload(prompt = "Verify detached scheduler routing"),
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs,
      ),
    )

    try {
      val detached = bindController(context, target)
      assertRemoteController(detached, target)
      assertNotNull(
        context.startForegroundService(
          RuntimeServiceIntentFactory().scheduleNotificationActionIntent(
            context = context,
            action = RuntimeNotificationIntentActions.ACTION_SNOOZE_SCHEDULE,
            scheduleId = scheduleId,
            sessionId = sessionId,
            target = target,
          ),
        ),
      )

      val snoozedSpec = awaitSnoozedSpec(specStore, scheduleId)
      assertTrue(requireNotNull(snoozedSpec.snoozedUntilEpochMs) > nowEpochMs)
      val workInfo = awaitScheduledWakeWork(workManager, scheduleId)
      assertEquals(WorkInfo.State.ENQUEUED, workInfo.state)
      Thread.sleep(500L)
      assertEquals(
        WorkInfo.State.ENQUEUED,
        latestScheduledWakeWork(workManager, scheduleId)?.state,
      )
    } finally {
      specStore.remove(scheduleId)
      runCatching { resyncEnabledScheduledTasksFromContext(context) }
      runCatching {
        workManager.cancelUniqueWork(scheduleWakeWorkName(scheduleId))
          .result
          .get(5L, TimeUnit.SECONDS)
      }
      context.stopService(RuntimeServiceIntentFactory().baseIntent(context, target))
    }
  }

  private fun bindController(
    context: Context,
    target: RuntimeServiceTarget,
  ): BoundController {
    val binding = ServiceBinding()
    val bound = context.bindService(
      RuntimeServiceIntentFactory().baseIntent(context, target),
      binding,
      Context.BIND_AUTO_CREATE,
    )
    assertTrue("bindService returned false for ${target.wireValue}", bound)
    bindings += binding
    return BoundController(
      binding = binding,
      binder = binding.awaitBinder(),
    )
  }

  private fun unbind(context: Context, binding: ServiceBinding) {
    runCatching { context.unbindService(binding) }
    bindings.remove(binding)
  }

  private fun assertRemoteController(
    bound: BoundController,
    target: RuntimeServiceTarget,
  ) {
    assertNull(
      bound.binder.queryLocalInterface("com.opencray.app.ipc.IRuntimeServiceController"),
    )
    assertEquals(RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION, bound.controller.protocolVersion)
    assertEquals(target.wireValue, bound.controller.runtimeTarget)
    assertEquals(RuntimeServiceControllerCapabilities.ALL, bound.controller.capabilities)
  }

  private fun loadProjection(
    controller: IRuntimeServiceController,
  ): RuntimeServiceProjectionSnapshot = requireNotNull(
    decodeRuntimeServiceProjectionSnapshot(controller.loadProjectionSnapshotJson()),
  )

  private fun awaitProcessPid(
    context: Context,
    processName: String,
    timeoutMs: Long = 30_000L,
  ): Int {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val pid = activityManager.runningAppProcesses
        .orEmpty()
        .firstOrNull { process -> process.processName == processName }
        ?.pid
      if (pid != null && pid > 0) {
        return pid
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for process '$processName'.")
  }

  private fun awaitProcessExit(
    context: Context,
    processName: String,
    previousPid: Int,
    timeoutMs: Long = 15_000L,
  ) {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      val previousProcessAlive = activityManager.runningAppProcesses
        .orEmpty()
        .any { process -> process.processName == processName && process.pid == previousPid }
      if (!previousProcessAlive) {
        return
      }
      Thread.sleep(100L)
    }
    error("Timed out waiting for process '$processName' pid=$previousPid to exit.")
  }

  private fun awaitLease(
    store: RuntimeServiceOwnerLeaseStore,
    target: RuntimeServiceTarget,
    processStartId: String,
    timeoutMs: Long = 10_000L,
  ): RuntimeServiceOwnerLease {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      store.load(target)
        ?.takeIf { lease -> lease.processStartId == processStartId }
        ?.let { lease -> return lease }
      Thread.sleep(100L)
    }
    error(
      "Timed out waiting for ${target.wireValue} owner lease " +
        "from processStartId=$processStartId.",
    )
  }

  private fun awaitSnoozedSpec(
    store: ScheduledTaskSpecStore,
    scheduleId: String,
    timeoutMs: Long = 20_000L,
  ): ScheduledTaskSpec {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      store.get(scheduleId)
        ?.takeIf { spec -> spec.snoozedUntilEpochMs != null }
        ?.let { spec -> return spec }
      Thread.sleep(100L)
    }
    error("Timed out waiting for schedule '$scheduleId' to be snoozed.")
  }

  private fun awaitScheduledWakeWork(
    workManager: WorkManager,
    scheduleId: String,
    timeoutMs: Long = 20_000L,
  ): WorkInfo {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
      latestScheduledWakeWork(workManager, scheduleId)
        ?.takeIf { workInfo -> workInfo.state == WorkInfo.State.ENQUEUED }
        ?.let { workInfo -> return workInfo }
      Thread.sleep(100L)
    }
    error("Timed out waiting for main-process WorkManager wake for '$scheduleId'.")
  }

  private fun latestScheduledWakeWork(
    workManager: WorkManager,
    scheduleId: String,
  ): WorkInfo? = workManager
    .getWorkInfosForUniqueWork(scheduleWakeWorkName(scheduleId))
    .get(5L, TimeUnit.SECONDS)
    .lastOrNull()

  private data class BoundController(
    val binding: ServiceBinding,
    val binder: IBinder,
  ) {
    val controller: IRuntimeServiceController = requireNotNull(
      IRuntimeServiceController.Stub.asInterface(binder),
    )
  }

  private class ServiceBinding : ServiceConnection {
    private val connected = CountDownLatch(1)

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var failure: String? = null

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
      binder = service
      connected.countDown()
    }

    override fun onNullBinding(name: ComponentName) {
      failure = "null_binding"
      connected.countDown()
    }

    override fun onServiceDisconnected(name: ComponentName) {
      failure = "service_disconnected"
      connected.countDown()
    }

    override fun onBindingDied(name: ComponentName) {
      failure = "binding_died"
      connected.countDown()
    }

    fun awaitBinder(timeoutSeconds: Long = 60L): IBinder {
      assertTrue(
        "Timed out waiting for runtime service binding.",
        connected.await(timeoutSeconds, TimeUnit.SECONDS),
      )
      assertNull("Runtime service binding failed: $failure", failure)
      assertNotNull(binder)
      return requireNotNull(binder)
    }
  }
}
