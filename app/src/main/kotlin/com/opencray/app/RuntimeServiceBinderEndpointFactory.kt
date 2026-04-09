package com.opencray.app

import android.os.Binder
import android.os.IBinder

internal interface RuntimeServiceBinderEndpoint : IBinder, OpenCrayRuntimeServiceBinderAccess

internal fun interface RuntimeServiceBinderEndpointFactory {
  fun create(
    binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceBinderEndpoint
}

internal object DefaultRuntimeServiceBinderEndpointFactory :
  RuntimeServiceBinderEndpointFactory {
  override fun create(
    binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
  ): RuntimeServiceBinderEndpoint = DefaultRuntimeServiceBinderEndpoint(
    binderEndpointDependencies = binderEndpointDependencies,
    gatewayBundle = gatewayBundle,
    serviceExecutionCoordinator = serviceExecutionCoordinator,
  )
}

internal data class RuntimeServiceBinderEndpointDependencies(
  val bridgeSnapshotDependencies: RuntimeServiceBridgeSnapshotDependencies,
  val approvePendingApproval: (String) -> Unit,
  val approvePendingApprovalForSession: (String) -> Unit,
  val rejectPendingApproval: (String) -> Unit,
  val refreshServiceWorkState: () -> RuntimeServiceWorkState,
)

internal class DefaultRuntimeServiceBinderEndpoint(
  private val binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
  private val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  private val serviceExecutionCoordinator: RuntimeServiceExecutionCoordinator,
) : Binder(), RuntimeServiceBinderEndpoint {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    binderEndpointDependencies.bridgeSnapshotDependencies.toBridgeSnapshot(
      serviceWorkState = binderEndpointDependencies.refreshServiceWorkState(),
      serviceKeepAliveState = serviceExecutionCoordinator.currentKeepAliveState(),
    )

  override fun loadShellGateway(): OpenCrayShellGateway = gatewayBundle.shellGateway

  override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway =
    gatewayBundle.chatRuntimeGateway

  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = try {
    when (command) {
      is OpenCrayChatWriteCommand.ApproveChatApproval -> {
        binderEndpointDependencies.approvePendingApproval(command.taskIdOrRunId)
        gatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> {
        binderEndpointDependencies.approvePendingApprovalForSession(command.taskIdOrRunId)
        gatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      is OpenCrayChatWriteCommand.RejectChatApproval -> {
        binderEndpointDependencies.rejectPendingApproval(command.taskIdOrRunId)
        gatewayBundle.notifyChatSnapshotsChanged()
        OpenCrayChatWriteDispatchResult.Completed
      }

      else -> gatewayBundle.dispatchChatWriteCommand(command)
    }
  } finally {
    refreshWorkState()
    persistProjectionSnapshot()
  }

  override fun loadSkillsGateway(): OpenCraySkillsGateway = gatewayBundle.skillsGateway

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult = try {
    gatewayBundle.dispatchSkillsWriteCommand(command)
  } finally {
    refreshWorkState()
  }

  override fun loadSettingsGateway(): OpenCraySettingsGateway = gatewayBundle.settingsGateway

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult = try {
    gatewayBundle.dispatchSettingsWriteCommand(command)
  } finally {
    refreshWorkState()
  }

  fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
    loadSnapshot()
      .runtimeAccess
      .lifecycleDescriptor
      .snapshotMap()

  fun peekRuntimeServiceLifecycle(): Map<String, Any?> =
    loadSnapshot()
      .serviceLifecycle
      .snapshotMap()

  fun peekRuntimeServiceWorkState(): Map<String, Any?> =
    loadSnapshot()
      .serviceWorkState
      .snapshotMap()

  fun peekRuntimeServiceKeepAliveState(): Map<String, Any?> =
    loadSnapshot()
      .serviceKeepAliveState
      .snapshotMap()

  fun peekRuntimeForegroundState(): Map<String, Any?> =
    serviceExecutionCoordinator.currentForegroundState().snapshotMap()

  private fun refreshWorkState() {
    binderEndpointDependencies.refreshServiceWorkState()
  }

  private fun persistProjectionSnapshot() {
    serviceExecutionCoordinator.persistProjectionSnapshot()
  }
}
