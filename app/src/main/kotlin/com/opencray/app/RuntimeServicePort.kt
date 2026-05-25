package com.opencray.app

internal data class RuntimeServicePort(
  val lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  val ownerObservationAccess: RuntimeOwnerObservationAccess,
  val notificationHostAccess: RuntimeNotificationHostAccess,
  val approvalDecisionHostAccess: RuntimeApprovalDecisionHostAccess,
  val chatMutationAccess: RuntimeChatMutationAccess,
  val chatSubmissionHostAccess: RuntimeChatSubmissionHostAccess,
  val replayAccess: OpenCrayRuntimeReplayAccess,
)

internal fun runtimeServicePort(
  lifecycleDescriptor: HostRuntimeLifecycleDescriptor,
  ownerObservationAccess: RuntimeOwnerObservationAccess,
  notificationHostAccess: RuntimeNotificationHostAccess,
  approvalDecisionHostAccess: RuntimeApprovalDecisionHostAccess,
  chatMutationAccess: RuntimeChatMutationAccess,
  chatSubmissionHostAccess: RuntimeChatSubmissionHostAccess,
  runtimeReplayAccess: OpenCrayRuntimeReplayAccess,
): RuntimeServicePort = RuntimeServicePort(
  lifecycleDescriptor = lifecycleDescriptor,
  ownerObservationAccess = ownerObservationAccess,
  notificationHostAccess = notificationHostAccess,
  approvalDecisionHostAccess = approvalDecisionHostAccess,
  chatMutationAccess = chatMutationAccess,
  chatSubmissionHostAccess = chatSubmissionHostAccess,
  replayAccess = runtimeReplayAccess,
)
