package com.opencray.app

import android.content.Context

internal data class OpenCrayProjectionGatewayBundle(
  val shellGateway: OpenCrayShellGateway,
  val chatRuntimeGateway: OpenCrayChatRuntimeGateway,
  val skillsGateway: OpenCraySkillsGateway,
  val settingsGateway: OpenCraySettingsGateway,
)

internal fun interface OpenCrayProjectionGatewayBundleFactory {
  fun create(
    context: Context,
    serviceClient: OpenCrayRuntimeServiceClient,
  ): OpenCrayProjectionGatewayBundle
}

internal fun openCrayProjectionGatewayBundleFactory(
  hostLifecycleDescriptor: HostRuntimeLifecycleDescriptor,
): OpenCrayProjectionGatewayBundleFactory =
  OpenCrayProjectionGatewayBundleFactory {
      context: Context,
      serviceClient: OpenCrayRuntimeServiceClient,
    ->
    val appContext = context.applicationContext
    OpenCrayProjectionGatewayBundle(
      shellGateway = projectionOnlyOpenCrayShellGateway(
        context = appContext,
        serviceClient = serviceClient,
        hostLifecycleDescriptor = hostLifecycleDescriptor,
      ),
      chatRuntimeGateway = projectionOnlyOpenCrayChatRuntimeGateway(
        context = appContext,
        connectionStateProvider = serviceClient::peekConnectionState,
        projectionSnapshotProvider = serviceClient::peekProjectionSnapshot,
        hostLifecycleDescriptor = hostLifecycleDescriptor,
      ),
      skillsGateway = projectionOnlyOpenCraySkillsGateway(
        context = appContext,
        connectionStateProvider = serviceClient::peekConnectionState,
      ),
      settingsGateway = projectionOnlyOpenCraySettingsGateway(
        context = appContext,
        connectionStateProvider = serviceClient::peekConnectionState,
      ),
    )
  }
