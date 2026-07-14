package com.opencray.app

import android.os.IBinder
import com.opencray.app.ipc.IRuntimeServiceController

internal const val RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION: Int = 1
internal const val RUNTIME_SERVICE_CONTROLLER_MAX_SNAPSHOT_CHARS: Int = 256_000

internal interface RuntimeServiceControllerWireAccess {
  fun protocolVersion(): Int

  fun runtimeTarget(): String?

  fun loadProjectionSnapshotJson(): String?
}

internal fun runtimeServiceBinderAccessForBinder(
  binder: IBinder,
  expectedTarget: RuntimeServiceTarget,
): OpenCrayRuntimeServiceBinderAccess? {
  (binder as? OpenCrayRuntimeServiceBinderAccess)?.let { localAccess ->
    return localAccess
  }
  val controller = runCatching {
    IRuntimeServiceController.Stub.asInterface(binder)
  }.getOrNull() ?: return null
  return versionedRuntimeServiceBinderAccess(
    wireAccess = AidlRuntimeServiceControllerWireAccess(controller),
    expectedTarget = expectedTarget,
  )
}

internal fun versionedRuntimeServiceBinderEndpoint(
  target: RuntimeServiceTarget,
  endpointProvider: () -> RuntimeServiceBinderEndpoint,
): RuntimeServiceBinderEndpoint = VersionedRuntimeServiceBinderEndpoint(
  target = target,
  endpointProvider = endpointProvider,
)

private class VersionedRuntimeServiceBinderEndpoint(
  private val target: RuntimeServiceTarget,
  private val endpointProvider: () -> RuntimeServiceBinderEndpoint,
) : IRuntimeServiceController.Stub(), RuntimeServiceBinderEndpoint {
  private fun currentEndpoint(): RuntimeServiceBinderEndpoint = endpointProvider()

  override fun getProtocolVersion(): Int = RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION

  override fun getRuntimeTarget(): String = target.wireValue

  override fun loadProjectionSnapshotJson(): String {
    val payload = encodeRuntimeServiceProjectionSnapshot(
      currentEndpoint().loadSnapshot().toProjectionSnapshot(),
    )
    check(payload.length <= RUNTIME_SERVICE_CONTROLLER_MAX_SNAPSHOT_CHARS) {
      "Runtime service controller projection snapshot exceeds the wire limit."
    }
    return payload
  }

  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    currentEndpoint().loadSnapshot()

  override fun loadShellGateway(): OpenCrayShellGateway? =
    currentEndpoint().loadShellGateway()

  override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway? =
    currentEndpoint().loadChatRuntimeGateway()

  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? =
    currentEndpoint().dispatchChatWriteCommand(command)

  override fun loadSkillsGateway(): OpenCraySkillsGateway? =
    currentEndpoint().loadSkillsGateway()

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? =
    currentEndpoint().dispatchSkillsWriteCommand(command)

  override fun loadSettingsGateway(): OpenCraySettingsGateway? =
    currentEndpoint().loadSettingsGateway()

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? =
    currentEndpoint().dispatchSettingsWriteCommand(command)
}

internal fun versionedRuntimeServiceBinderAccess(
  wireAccess: RuntimeServiceControllerWireAccess,
  expectedTarget: RuntimeServiceTarget,
): OpenCrayRuntimeServiceBinderAccess? {
  val handshake = runCatching {
    wireAccess.protocolVersion() to RuntimeServiceTarget.fromWireValue(
      wireAccess.runtimeTarget(),
    )
  }.getOrNull() ?: return null
  if (handshake.first != RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION ||
    handshake.second != expectedTarget
  ) {
    return null
  }
  return RemoteRuntimeServiceBinderAccess(wireAccess)
}

private class AidlRuntimeServiceControllerWireAccess(
  private val controller: IRuntimeServiceController,
) : RuntimeServiceControllerWireAccess {
  override fun protocolVersion(): Int = controller.getProtocolVersion()

  override fun runtimeTarget(): String? = controller.getRuntimeTarget()

  override fun loadProjectionSnapshotJson(): String? =
    controller.loadProjectionSnapshotJson()
}

private class RemoteRuntimeServiceBinderAccess(
  private val wireAccess: RuntimeServiceControllerWireAccess,
) : OpenCrayRuntimeServiceBinderAccess {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot {
    val payload = requireNotNull(wireAccess.loadProjectionSnapshotJson()) {
      "Runtime service controller returned no projection snapshot."
    }
    require(payload.length <= RUNTIME_SERVICE_CONTROLLER_MAX_SNAPSHOT_CHARS) {
      "Runtime service controller projection snapshot exceeds the wire limit."
    }
    return requireNotNull(decodeRuntimeServiceProjectionSnapshot(payload)) {
      "Runtime service controller returned an invalid projection snapshot."
    }.toBridgeSnapshot()
  }
}
