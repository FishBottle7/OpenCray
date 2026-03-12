package com.opencray.app

import android.content.Context
import com.opencray.ui.files.WorkspacePickerScreen
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal const val FILES_WORKBENCH_SCENARIO_NO_GRANT = "no_grant"
internal const val FILES_WORKBENCH_SCENARIO_ACTIVE_GRANT = "active_grant"
internal const val FILES_WORKBENCH_SCENARIO_REVOKED_GRANT = "revoked_grant"
internal const val FILES_WORKBENCH_SCENARIO_OUTSIDE_ROOT_DENIAL = "outside_root_denial"

private const val WORKSPACE_ID = "workspace-saf-ui"
private const val TREE_URI =
  "content://com.android.externalstorage.documents/tree/primary%3AOpenCray%2Fprojects%2Fdemo"
private const val GRANTED_ROOT_PATH = "projects/demo"
private const val IN_ROOT_REQUEST_PATH = "projects/demo/docs/report.md"
private const val OUTSIDE_ROOT_REQUEST_PATH = "projects/other/docs/report.md"
private const val PERSISTED_AT_EPOCH_MILLIS = 1_000L
private const val REVOKED_AT_EPOCH_MILLIS = 2_000L
private const val LOCAL_WORKBENCH_FOLDER = "opencray-files-workbench"

private const val DEFAULT_SAF_WORKSPACE_BRIDGE_CLASS_NAME =
  "com.opencray.filesystem.DefaultSafWorkspaceBridge"
private const val IN_MEMORY_SAF_WORKSPACE_GRANT_STORE_CLASS_NAME =
  "com.opencray.filesystem.InMemorySafWorkspaceGrantStore"
private const val PERSISTED_SAF_GRANT_SNAPSHOT_CLASS_NAME =
  "com.opencray.filesystem.PersistedSafGrantSnapshot"
private const val SAF_ACCESS_REQUEST_CLASS_NAME = "com.opencray.filesystem.SafAccessRequest"
private const val SAF_ACCESS_REQUEST_RELATIVE_PATH_CLASS_NAME =
  "com.opencray.filesystem.SafAccessRequest\$RelativePath"
private const val SAF_WORKSPACE_BRIDGE_CLASS_NAME = "com.opencray.filesystem.SafWorkspaceBridge"
private const val SAF_WORKSPACE_GRANT_STORE_CLASS_NAME =
  "com.opencray.filesystem.SafWorkspaceGrantStore"
private const val WORKSPACE_PICKER_SCREEN_STATE_CLASS_NAME =
  "com.opencray.ui.files.WorkspacePickerScreenState"

internal enum class FilesWorkbenchSeedScenario(
  val rawValue: String,
  val requestPath: String,
) {
  NO_GRANT(
    rawValue = FILES_WORKBENCH_SCENARIO_NO_GRANT,
    requestPath = IN_ROOT_REQUEST_PATH,
  ),
  ACTIVE_GRANT(
    rawValue = FILES_WORKBENCH_SCENARIO_ACTIVE_GRANT,
    requestPath = IN_ROOT_REQUEST_PATH,
  ),
  REVOKED_GRANT(
    rawValue = FILES_WORKBENCH_SCENARIO_REVOKED_GRANT,
    requestPath = IN_ROOT_REQUEST_PATH,
  ),
  OUTSIDE_ROOT_DENIAL(
    rawValue = FILES_WORKBENCH_SCENARIO_OUTSIDE_ROOT_DENIAL,
    requestPath = OUTSIDE_ROOT_REQUEST_PATH,
  ),
}

internal fun parseFilesWorkbenchScenario(rawValue: String?): FilesWorkbenchSeedScenario {
  val normalized = rawValue?.trim()?.lowercase(Locale.ROOT)
  return FilesWorkbenchSeedScenario.entries.firstOrNull { it.rawValue == normalized }
    ?: FilesWorkbenchSeedScenario.NO_GRANT
}

internal fun buildFilesWorkbenchState(
  context: Context,
  seedScenario: FilesWorkbenchSeedScenario,
): Any {
  val initialGrants = when (seedScenario) {
    FilesWorkbenchSeedScenario.NO_GRANT -> emptyList()
    FilesWorkbenchSeedScenario.ACTIVE_GRANT,
    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> listOf(grantedSnapshot())
    FilesWorkbenchSeedScenario.REVOKED_GRANT -> listOf(revokedSnapshot())
  }

  val bridge = defaultSafWorkspaceBridge(
    inMemorySafWorkspaceGrantStore(initialGrants = initialGrants),
  )

  return workspacePickerState(
    context = context,
    bridge = bridge,
    request = relativePathRequest(seedScenario.requestPath),
    subtitle = scenarioSubtitle(context, seedScenario),
  )
}

internal fun resolveAgentWorkspaceRoot(
  context: Context,
  seedScenario: FilesWorkbenchSeedScenario,
): Path {
  val rootPath = when (seedScenario) {
    FilesWorkbenchSeedScenario.ACTIVE_GRANT,
    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL,
    -> {
      context.cacheDir.toPath()
        .resolve(LOCAL_WORKBENCH_FOLDER)
        .resolve(WORKSPACE_ID)
        .resolve(GRANTED_ROOT_PATH.replace('/', '_'))
    }

    FilesWorkbenchSeedScenario.NO_GRANT,
    FilesWorkbenchSeedScenario.REVOKED_GRANT,
    -> context.filesDir.toPath().resolve("opencray-agent-workspace")
  }
  Files.createDirectories(rootPath)
  return rootPath
}

private fun scenarioSubtitle(
  context: Context,
  seedScenario: FilesWorkbenchSeedScenario,
): String {
  val isChinese = context.resources.configuration.locales[0].language == "zh"
  return when (seedScenario) {
    FilesWorkbenchSeedScenario.NO_GRANT -> if (isChinese) {
      "先在设置里选择一个工作区，文件页才会解锁。"
    } else {
      "Pick a workspace in Settings before Files unlocks."
    }

    FilesWorkbenchSeedScenario.ACTIVE_GRANT -> if (isChinese) {
      "当前根目录已可用，你可以直接像文件管理器一样浏览和编辑。"
    } else {
      "The current root is active, so Files can browse and edit directly."
    }

    FilesWorkbenchSeedScenario.REVOKED_GRANT -> if (isChinese) {
      "已保存根目录需要重新授权，恢复后才能继续浏览和编辑。"
    } else {
      "Re-authorize the saved root before browsing or editing continues."
    }

    FilesWorkbenchSeedScenario.OUTSIDE_ROOT_DENIAL -> if (isChinese) {
      "当前请求超出已保存根目录。需要时请去设置里切换工作区。"
    } else {
      "The current request is outside the saved root. Change workspace access in Settings if needed."
    }
  }
}

internal fun WorkspacePickerScreen.submitReflectiveState(state: Any) {
  javaClass
    .getMethod("submitState", loadClass(WORKSPACE_PICKER_SCREEN_STATE_CLASS_NAME))
    .invoke(this, state)
}

private fun workspacePickerState(
  context: Context,
  bridge: Any,
  request: Any,
  subtitle: String,
): Any {
  val stateClass = loadClass(WORKSPACE_PICKER_SCREEN_STATE_CLASS_NAME)
  val bridgeClass = loadClass(SAF_WORKSPACE_BRIDGE_CLASS_NAME)
  val requestClass = loadClass(SAF_ACCESS_REQUEST_CLASS_NAME)

  return invokeCompanion(
    ownerClass = stateClass,
    methodName = "fromBridge",
    parameterTypes = arrayOf(
      bridgeClass,
      String::class.java,
      requestClass,
      String::class.java,
      String::class.java,
    ),
    args = arrayOf(
      bridge,
      WORKSPACE_ID,
      request,
      context.getString(org.opencray.ui.R.string.workspace_picker_title),
      subtitle,
    ),
  )
}

private fun grantedSnapshot(): Any = invokeCompanion(
  ownerClass = loadClass(PERSISTED_SAF_GRANT_SNAPSHOT_CLASS_NAME),
  methodName = "fromTreeUri",
  parameterTypes = arrayOf(
    String::class.java,
    String::class.java,
    Long::class.javaPrimitiveType!!,
    String::class.java,
  ),
  args = arrayOf(
    WORKSPACE_ID,
    TREE_URI,
    PERSISTED_AT_EPOCH_MILLIS,
    GRANTED_ROOT_PATH,
  ),
)

private fun revokedSnapshot(): Any {
  val snapshot = grantedSnapshot()
  return checkNotNull(
    snapshot.javaClass
      .getMethod("asRevoked", Long::class.javaPrimitiveType!!)
      .invoke(snapshot, REVOKED_AT_EPOCH_MILLIS),
  ) { "Expected PersistedSafGrantSnapshot.asRevoked() to return a snapshot." }
}

private fun relativePathRequest(rawValue: String): Any = loadClass(
  SAF_ACCESS_REQUEST_RELATIVE_PATH_CLASS_NAME,
).getConstructor(String::class.java).newInstance(rawValue)

private fun inMemorySafWorkspaceGrantStore(initialGrants: List<Any>): Any = loadClass(
  IN_MEMORY_SAF_WORKSPACE_GRANT_STORE_CLASS_NAME,
).getConstructor(Iterable::class.java).newInstance(initialGrants)

private fun defaultSafWorkspaceBridge(store: Any): Any = loadClass(
  DEFAULT_SAF_WORKSPACE_BRIDGE_CLASS_NAME,
).getConstructor(loadClass(SAF_WORKSPACE_GRANT_STORE_CLASS_NAME)).newInstance(store)

private fun invokeCompanion(
  ownerClass: Class<*>,
  methodName: String,
  parameterTypes: Array<Class<*>>,
  args: Array<Any>,
): Any {
  val companion = ownerClass.getDeclaredField("Companion").get(null)
  return checkNotNull(
    companion.javaClass.getMethod(methodName, *parameterTypes).invoke(companion, *args),
  ) { "Expected $methodName on ${ownerClass.name} companion to return a value." }
}

private fun loadClass(className: String): Class<*> = Class.forName(className)

// Learning: Scenario subtitles now describe the bounded workbench directly, so the Files tab no longer reads like a future host placeholder.
