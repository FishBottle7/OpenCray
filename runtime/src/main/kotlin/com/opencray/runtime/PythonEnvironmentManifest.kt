package com.opencray.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class PythonEnvironmentManifest(
  @SerialName("schema_version")
  val schemaVersion: Int = 1,
  @SerialName("python_version")
  val pythonVersion: String? = null,
  val requested: List<String> = emptyList(),
  val packages: Map<String, String> = emptyMap(),
  @SerialName("unparsed_freeze")
  val unparsedFreeze: List<String> = emptyList(),
)

object PythonEnvironmentPaths {
  fun openCrayPythonDir(workspaceRoot: Path): Path =
    workspaceRoot.resolve(".opencray").resolve("python")

  fun venvDir(workspaceRoot: Path): Path = openCrayPythonDir(workspaceRoot).resolve("venv")

  fun wheelhouseDir(workspaceRoot: Path): Path =
    openCrayPythonDir(workspaceRoot).resolve("wheelhouse")

  fun manifestPath(workspaceRoot: Path): Path =
    openCrayPythonDir(workspaceRoot).resolve("manifest.json")
}
