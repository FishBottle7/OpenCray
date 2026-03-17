package com.opencray.runtime

import com.opencray.core.contracts.ExecutionResult

/**
 * Executes workspace-local Python scripts through a pluggable runtime backend.
 *
 * The host-process backend remains the default for non-Android environments, while Android can
 * swap in an embedded runtime implementation without changing agent tool semantics.
 */
interface PythonScriptRuntime {
  fun exec(request: PythonExecRequest): ExecutionResult
}

/**
 * Optional capability for runtime backends that can cooperatively cancel an in-flight Python
 * execution identified by a stable request id.
 */
interface CancellablePythonScriptRuntime {
  fun requestCancellation(requestId: String): Boolean
}
