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
