package com.opencray.app

import android.os.IBinder
import com.opencray.app.ipc.IRuntimeServiceController

internal const val RUNTIME_SERVICE_CONTROLLER_MIN_PROTOCOL_VERSION: Int = 1
internal const val RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION: Int = 2
internal const val RUNTIME_SERVICE_CONTROLLER_MAX_SNAPSHOT_CHARS: Int = 256_000

internal object RuntimeServiceControllerCapabilities {
  const val PROJECTION_READ: Long = 1L
  const val CHAT_WRITE: Long = 1L shl 1
  const val SKILLS_WRITE: Long = 1L shl 2
  const val SETTINGS_WRITE: Long = 1L shl 3

  const val ALL: Long = PROJECTION_READ or CHAT_WRITE or SKILLS_WRITE or SETTINGS_WRITE
}

internal interface RuntimeServiceControllerWireAccess {
  fun protocolVersion(): Int

  fun runtimeTarget(): String?

  fun capabilities(): Long = RuntimeServiceControllerCapabilities.PROJECTION_READ

  fun loadProjectionSnapshotJson(): String?

  fun dispatchWriteCommandJson(commandJson: String): String? = null
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

  override fun getCapabilities(): Long = RuntimeServiceControllerCapabilities.ALL

  override fun loadProjectionSnapshotJson(): String {
    val payload = encodeRuntimeServiceProjectionSnapshot(
      currentEndpoint().loadSnapshot().toProjectionSnapshot(),
    )
    check(payload.length <= RUNTIME_SERVICE_CONTROLLER_MAX_SNAPSHOT_CHARS) {
      "Runtime service controller projection snapshot exceeds the wire limit."
    }
    return payload
  }

  override fun dispatchWriteCommandJson(commandJson: String): String =
    dispatchRuntimeServiceWriteCommandJson(
      endpoint = currentEndpoint(),
      commandJson = commandJson,
    )

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
  val protocolSupported = handshake.first in RUNTIME_SERVICE_CONTROLLER_MIN_PROTOCOL_VERSION..
    RUNTIME_SERVICE_CONTROLLER_PROTOCOL_VERSION
  if (!protocolSupported || handshake.second != expectedTarget) {
    return null
  }
  val capabilities = if (handshake.first >= 2) {
    runCatching { wireAccess.capabilities() }.getOrNull() ?: return null
  } else {
    RuntimeServiceControllerCapabilities.PROJECTION_READ
  }
  if (!capabilities.includes(RuntimeServiceControllerCapabilities.PROJECTION_READ)) {
    return null
  }
  return RemoteRuntimeServiceBinderAccess(
    wireAccess = wireAccess,
    capabilities = capabilities,
  )
}

private class AidlRuntimeServiceControllerWireAccess(
  private val controller: IRuntimeServiceController,
) : RuntimeServiceControllerWireAccess {
  override fun protocolVersion(): Int = controller.getProtocolVersion()

  override fun runtimeTarget(): String? = controller.getRuntimeTarget()

  override fun capabilities(): Long = controller.getCapabilities()

  override fun loadProjectionSnapshotJson(): String? =
    controller.loadProjectionSnapshotJson()

  override fun dispatchWriteCommandJson(commandJson: String): String? =
    controller.dispatchWriteCommandJson(commandJson)
}

private class RemoteRuntimeServiceBinderAccess(
  private val wireAccess: RuntimeServiceControllerWireAccess,
  private val capabilities: Long,
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

  override fun dispatchChatWriteCommand(
    command: OpenCrayChatWriteCommand,
  ): OpenCrayChatWriteDispatchResult? {
    if (!capabilities.includes(RuntimeServiceControllerCapabilities.CHAT_WRITE)) {
      return null
    }
    return requireNotNull(
      decodeRuntimeServiceChatWriteResult(
        dispatchWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      ),
    ) {
      "Runtime service controller returned an invalid chat write result."
    }
  }

  override fun dispatchSkillsWriteCommand(
    command: OpenCraySkillsWriteCommand,
  ): OpenCraySkillsWriteDispatchResult? {
    if (!capabilities.includes(RuntimeServiceControllerCapabilities.SKILLS_WRITE)) {
      return null
    }
    return requireNotNull(
      decodeRuntimeServiceSkillsWriteResult(
        dispatchWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      ),
    ) {
      "Runtime service controller returned an invalid skills write result."
    }
  }

  override fun dispatchSettingsWriteCommand(
    command: OpenCraySettingsWriteCommand,
  ): OpenCraySettingsWriteDispatchResult? {
    if (!capabilities.includes(RuntimeServiceControllerCapabilities.SETTINGS_WRITE)) {
      return null
    }
    return requireNotNull(
      decodeRuntimeServiceSettingsWriteResult(
        dispatchWriteCommand(runtimeServiceWriteCommandEnvelope(command)),
      ),
    ) {
      "Runtime service controller returned an invalid settings write result."
    }
  }

  private fun dispatchWriteCommand(
    envelope: RuntimeServiceWriteCommandEnvelope,
  ): String {
    val commandJson = encodeRuntimeServiceWriteCommand(envelope)
    require(commandJson.length <= RUNTIME_SERVICE_WRITE_COMMAND_MAX_CHARS) {
      "Runtime service controller write command exceeds the wire limit."
    }
    val resultJson = requireNotNull(wireAccess.dispatchWriteCommandJson(commandJson)) {
      "Runtime service controller returned no write result."
    }
    require(resultJson.length <= RUNTIME_SERVICE_WRITE_RESULT_MAX_CHARS) {
      "Runtime service controller write result exceeds the wire limit."
    }
    return resultJson
  }
}

internal fun dispatchRuntimeServiceWriteCommandJson(
  endpoint: RuntimeServiceBinderEndpoint,
  commandJson: String,
): String {
  require(commandJson.length <= RUNTIME_SERVICE_WRITE_COMMAND_MAX_CHARS) {
    "Runtime service controller write command exceeds the wire limit."
  }
  val resultJson = when (
    val decoded = requireNotNull(decodeRuntimeServiceWriteCommand(commandJson)) {
      "Runtime service controller received an invalid write command."
    }
  ) {
    is DecodedRuntimeServiceWriteCommand.Chat -> encodeRuntimeServiceWriteResult(
      requireNotNull(endpoint.dispatchChatWriteCommand(decoded.command)) {
        "Runtime service endpoint did not dispatch the chat write command."
      },
    )

    is DecodedRuntimeServiceWriteCommand.Skills -> encodeRuntimeServiceWriteResult(
      requireNotNull(endpoint.dispatchSkillsWriteCommand(decoded.command)) {
        "Runtime service endpoint did not dispatch the skills write command."
      },
    )

    is DecodedRuntimeServiceWriteCommand.Settings -> encodeRuntimeServiceWriteResult(
      requireNotNull(endpoint.dispatchSettingsWriteCommand(decoded.command)) {
        "Runtime service endpoint did not dispatch the settings write command."
      },
    )
  }
  check(resultJson.length <= RUNTIME_SERVICE_WRITE_RESULT_MAX_CHARS) {
    "Runtime service controller write result exceeds the wire limit."
  }
  return resultJson
}

private fun Long.includes(capability: Long): Boolean = this and capability == capability
