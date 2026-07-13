package com.opencray.app

import android.content.Context

internal const val RUNTIME_SERVICE_PROCESS_SUFFIX: String = ":runtime"
internal const val DETACHED_RUNTIME_SERVICE_PROCESS_SUFFIX: String = ":runtime_controller"

internal fun runtimeServiceProcessSuffixForTarget(
  target: RuntimeServiceTarget,
): String = when (target) {
  RuntimeServiceTarget.INTERACTIVE -> RUNTIME_SERVICE_PROCESS_SUFFIX
  RuntimeServiceTarget.DETACHED_BACKGROUND -> DETACHED_RUNTIME_SERVICE_PROCESS_SUFFIX
}

internal data class RuntimeServiceProcessDescriptor(
  val packageName: String? = null,
  val processName: String? = null,
  val expectedProcessName: String? = null,
  val expectedProcessSuffix: String = RUNTIME_SERVICE_PROCESS_SUFFIX,
  val isDedicatedRuntimeProcess: Boolean = false,
  val mismatchReason: String? = null,
) {
  fun snapshotMap(): Map<String, Any?> = buildMap {
    packageName?.let { put("packageName", it) }
    processName?.let { put("processName", it) }
    expectedProcessName?.let { put("expectedProcessName", it) }
    put("expectedProcessSuffix", expectedProcessSuffix)
    put("isDedicatedRuntimeProcess", isDedicatedRuntimeProcess)
    mismatchReason?.let { put("mismatchReason", it) }
  }
}

internal fun runtimeServiceProcessDescriptorForContext(
  context: Context,
  processName: String? = currentProcessNameOrNull(),
  expectedProcessSuffix: String = RUNTIME_SERVICE_PROCESS_SUFFIX,
): RuntimeServiceProcessDescriptor = runtimeServiceProcessDescriptor(
  packageName = context.packageName,
  processName = processName,
  expectedProcessSuffix = expectedProcessSuffix,
)

internal fun runtimeServiceProcessDescriptor(
  packageName: String?,
  processName: String?,
  expectedProcessSuffix: String = RUNTIME_SERVICE_PROCESS_SUFFIX,
): RuntimeServiceProcessDescriptor {
  val normalizedPackageName = packageName.normalizedOrNull()
  val normalizedProcessName = processName.normalizedOrNull()
  val normalizedExpectedSuffix = expectedProcessSuffix
    .trim()
    .takeIf(String::isNotBlank)
    ?.let { suffix -> if (suffix.startsWith(":")) suffix else ":$suffix" }
    ?: RUNTIME_SERVICE_PROCESS_SUFFIX
  val expectedProcessName = normalizedPackageName?.let { name ->
    "$name$normalizedExpectedSuffix"
  }
  val isDedicatedRuntimeProcess = expectedProcessName != null &&
    normalizedProcessName == expectedProcessName
  val mismatchReason = when {
    isDedicatedRuntimeProcess -> null
    expectedProcessName == null -> "missing_expected_process_name"
    normalizedProcessName == null -> "missing_current_process_name"
    normalizedProcessName == normalizedPackageName -> "main_process"
    normalizedPackageName != null &&
      normalizedProcessName.startsWith("$normalizedPackageName:") -> "secondary_process_mismatch"
    else -> "foreign_process"
  }
  return RuntimeServiceProcessDescriptor(
    packageName = normalizedPackageName,
    processName = normalizedProcessName,
    expectedProcessName = expectedProcessName,
    expectedProcessSuffix = normalizedExpectedSuffix,
    isDedicatedRuntimeProcess = isDedicatedRuntimeProcess,
    mismatchReason = mismatchReason,
  )
}

private fun String?.normalizedOrNull(): String? =
  this?.trim()?.takeIf(String::isNotBlank)

internal fun requireDedicatedRuntimeServiceProcess(
  descriptor: RuntimeServiceProcessDescriptor,
): RuntimeServiceProcessDescriptor {
  if (descriptor.isDedicatedRuntimeProcess) {
    return descriptor
  }
  val expectedProcessName = descriptor.expectedProcessName ?: "<unknown>"
  val currentProcessName = descriptor.processName ?: "<unknown>"
  val mismatchReason = descriptor.mismatchReason ?: "unknown"
  error(
    "Runtime service must run in $expectedProcessName; " +
      "current process is $currentProcessName ($mismatchReason).",
  )
}
