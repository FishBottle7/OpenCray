package com.opencray.runtime

/**
 * Compatibility wrapper around [HostProcessPythonRuntime].
 *
 * New code should depend on [PythonScriptRuntime] and instantiate [HostProcessPythonRuntime]
 * directly so Android can swap in an embedded backend without inheriting the host-process name.
 */
@Deprecated(
  message = "Use PythonScriptRuntime with HostProcessPythonRuntime or a platform-specific backend.",
  replaceWith = ReplaceWith("HostProcessPythonRuntime"),
)
class PythonRuntimeAdapter(
  private val delegate: HostProcessPythonRuntime = HostProcessPythonRuntime(),
) : PythonScriptRuntime by delegate {
  companion object {
    internal fun commandFor(request: PythonExecRequest): List<String> =
      HostProcessPythonRuntime.commandFor(request)
  }
}
