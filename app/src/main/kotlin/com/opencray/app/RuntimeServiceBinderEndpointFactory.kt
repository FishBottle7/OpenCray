package com.opencray.app

import android.os.Binder
import android.os.IBinder

internal interface RuntimeServiceBinderEndpoint : IBinder, OpenCrayRuntimeServiceBinderAccess

internal fun interface RuntimeServiceBinderEndpointFactory {
  fun create(
    binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    shellStateAccess: RuntimeServiceShellStateAccess,
    projectionCoordinator: RuntimeServiceProjectionCoordinator,
  ): RuntimeServiceBinderEndpoint
}

internal object DefaultRuntimeServiceBinderEndpointFactory :
  RuntimeServiceBinderEndpointFactory {
  override fun create(
    binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
    gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
    shellStateAccess: RuntimeServiceShellStateAccess,
    projectionCoordinator: RuntimeServiceProjectionCoordinator,
  ): RuntimeServiceBinderEndpoint = DefaultRuntimeServiceBinderEndpoint(
    binderEndpointDependencies = binderEndpointDependencies,
    gatewayBundle = gatewayBundle,
    shellStateAccess = shellStateAccess,
    projectionCoordinator = projectionCoordinator,
  )
}

internal data class RuntimeServiceBinderEndpointDependencies(
  val bridgeSnapshotDependencies: RuntimeServiceBridgeSnapshotDependencies,
  val runtimeTarget: RuntimeServiceTarget,
  val chatWriteTargetResolver: ChatRuntimeWriteTargetResolver,
  val targetScopedServiceClientProvider: (RuntimeServiceTarget) -> OpenCrayRuntimeServiceClient,
  val approvalDecisionAccess: RuntimeServiceApprovalDecisionAccess,
  val refreshServiceWorkState: () -> RuntimeServiceWorkState,
)

internal class DefaultRuntimeServiceBinderEndpoint(
  private val binderEndpointDependencies: RuntimeServiceBinderEndpointDependencies,
  private val gatewayBundle: OpenCrayRuntimeServiceGatewayBundle,
  private val shellStateAccess: RuntimeServiceShellStateAccess,
  private val projectionCoordinator: RuntimeServiceProjectionCoordinator,
) : Binder(), RuntimeServiceBinderEndpoint {
  override fun loadSnapshot(): OpenCrayRuntimeServiceBridgeSnapshot =
    binderEndpointDependencies.bridgeSnapshotDependencies.toBridgeSnapshot(
      serviceWorkState = binderEndpointDependencies.refreshServiceWorkState(),
      serviceKeepAliveState = shellStateAccess.currentKeepAliveState(),
    )

  override fun loadShellGateway(): OpenCrayShellGateway = gatewayBundle.shellGateway

  override fun loadChatRuntimeGateway(): OpenCrayChatRuntimeGateway =
    gatewayBundle.chatRuntimeGateway

  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult {
    requireOwnerLease()
    return try {
      val target = binderEndpointDependencies.chatWriteTargetResolver.targetFor(command)
      if (target != binderEndpointDependencies.runtimeTarget) {
        dispatchForwardedChatWriteCommand(
          target = target,
          command = command,
        )
      } else {
        dispatchLocalChatWriteCommand(command)
      }
    } finally {
      refreshWorkState()
      persistProjectionSnapshot()
    }
  }

  private fun dispatchLocalChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = when (command) {
    is OpenCrayChatWriteCommand.ApproveChatApproval -> {
      binderEndpointDependencies.approvalDecisionAccess.approve(command.taskIdOrRunId)
      gatewayBundle.notifyChatSnapshotsChanged()
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.ApproveChatApprovalForSession -> {
      binderEndpointDependencies.approvalDecisionAccess.approveForSession(command.taskIdOrRunId)
      gatewayBundle.notifyChatSnapshotsChanged()
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.ApproveChatApprovalAsBatch -> {
      binderEndpointDependencies.approvalDecisionAccess.approveForBatch(command.taskIdOrRunId)
      gatewayBundle.notifyChatSnapshotsChanged()
      OpenCrayChatWriteDispatchResult.Completed
    }

    is OpenCrayChatWriteCommand.RejectChatApproval -> {
      binderEndpointDependencies.approvalDecisionAccess.reject(command.taskIdOrRunId)
      gatewayBundle.notifyChatSnapshotsChanged()
      OpenCrayChatWriteDispatchResult.Completed
    }

    else -> gatewayBundle.dispatchChatWriteCommand(command)
  }

  private fun dispatchForwardedChatWriteCommand(
    target: RuntimeServiceTarget,
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult = requireNotNull(
    binderEndpointDependencies.targetScopedServiceClientProvider(target)
      .dispatchChatWriteCommand(command),
  ) {
    "Unable to dispatch chat write command to runtime service target '${target.wireValue}'."
  }

  override fun loadSkillsGateway(): OpenCraySkillsGateway = gatewayBundle.skillsGateway

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult {
    requireOwnerLease()
    return try {
      gatewayBundle.dispatchSkillsWriteCommand(command)
    } finally {
      refreshWorkState()
    }
  }

  override fun loadSettingsGateway(): OpenCraySettingsGateway = gatewayBundle.settingsGateway

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult {
    requireOwnerLease()
    return try {
      gatewayBundle.dispatchSettingsWriteCommand(command)
    } finally {
      refreshWorkState()
    }
  }

  fun peekRuntimeOwnerLifecycle(): Map<String, Any?> =
    loadSnapshot()
      .runtimeOwnerLifecycle
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
    shellStateAccess.currentForegroundState().snapshotMap()

  private fun refreshWorkState() {
    binderEndpointDependencies.refreshServiceWorkState()
  }

  private fun persistProjectionSnapshot() {
    projectionCoordinator.persistProjectionSnapshot()
  }

  private fun requireOwnerLease() {
    check(projectionCoordinator.tryAcquireOwnerLease()) {
      "Runtime service target '${binderEndpointDependencies.runtimeTarget.wireValue}' " +
        "does not hold the active owner lease."
    }
  }
}
