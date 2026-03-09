package com.opencray.app

import android.app.Activity
import android.os.Bundle
import com.opencray.ui.files.WorkspacePickerScreen
import java.util.Locale

private const val WORKSPACE_PICKER_TITLE = "Workspace access"
private const val WORKSPACE_ID = "workspace-saf-ui"
private const val TREE_URI =
  "content://com.android.externalstorage.documents/tree/primary%3AOpenCray%2Fprojects%2Fdemo"
private const val GRANTED_ROOT_PATH = "projects/demo"
private const val IN_ROOT_REQUEST_PATH = "projects/demo/docs/report.md"
private const val OUTSIDE_ROOT_REQUEST_PATH = "projects/other/docs/report.md"
private const val PERSISTED_AT_EPOCH_MILLIS = 1_000L
private const val REVOKED_AT_EPOCH_MILLIS = 2_000L

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

class WorkspaceSettingsActivity : Activity(), WorkspacePickerScreen.Listener {
  companion object {
    const val EXTRA_SCENARIO = "com.opencray.app.WorkspaceSettingsActivity.extra.SCENARIO"
    const val SCENARIO_NO_GRANT = "no_grant"
    const val SCENARIO_ACTIVE_GRANT = "active_grant"
    const val SCENARIO_REVOKED_GRANT = "revoked_grant"
    const val SCENARIO_OUTSIDE_ROOT_DENIAL = "outside_root_denial"
  }

  private lateinit var workspacePickerScreen: WorkspacePickerScreen
  private var seedScenario: SeedScenario = SeedScenario.NO_GRANT

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    title = "Workspace Settings"
    seedScenario = scenarioFromIntent()

    workspacePickerScreen = WorkspacePickerScreen(this).apply {
      setListener(this@WorkspaceSettingsActivity)
    }
    renderSeededState()
    setContentView(workspacePickerScreen)
  }

  override fun onPickWorkspaceRequested(workspaceId: String) {
    seedScenario = SeedScenario.ACTIVE_GRANT
    renderSeededState()
  }

  override fun onReauthorizeWorkspaceRequested(workspaceId: String) {
    seedScenario = SeedScenario.ACTIVE_GRANT
    renderSeededState()
  }

  override fun onClearGrantRequested(workspaceId: String) {
    seedScenario = SeedScenario.NO_GRANT
    renderSeededState()
  }

  private fun renderSeededState() {
    workspacePickerScreen.submitStateReflectively(buildStateForScenario(seedScenario))
  }

  private fun scenarioFromIntent(): SeedScenario = when (
    intent.getStringExtra(EXTRA_SCENARIO)
      ?.trim()
      ?.lowercase(Locale.ROOT)
  ) {
    SCENARIO_ACTIVE_GRANT -> SeedScenario.ACTIVE_GRANT
    SCENARIO_REVOKED_GRANT -> SeedScenario.REVOKED_GRANT
    SCENARIO_OUTSIDE_ROOT_DENIAL -> SeedScenario.OUTSIDE_ROOT_DENIAL
    SCENARIO_NO_GRANT -> SeedScenario.NO_GRANT
    else -> SeedScenario.NO_GRANT
  }

  private fun buildStateForScenario(seedScenario: SeedScenario): Any {
    val initialGrants = when (seedScenario) {
      SeedScenario.NO_GRANT -> emptyList()
      SeedScenario.ACTIVE_GRANT,
      SeedScenario.OUTSIDE_ROOT_DENIAL -> listOf(grantedSnapshot())
      SeedScenario.REVOKED_GRANT -> listOf(revokedSnapshot())
    }

    val bridge = defaultSafWorkspaceBridge(
      inMemorySafWorkspaceGrantStore(initialGrants = initialGrants),
    )

    return workspacePickerState(
      bridge = bridge,
      request = relativePathRequest(seedScenario.requestPath),
      subtitle = seedScenario.subtitle,
    )
  }

  private fun workspacePickerState(
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
        WORKSPACE_PICKER_TITLE,
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

  private fun WorkspacePickerScreen.submitStateReflectively(state: Any) {
    javaClass
      .getMethod("submitState", loadClass(WORKSPACE_PICKER_SCREEN_STATE_CLASS_NAME))
      .invoke(this, state)
  }

  private enum class SeedScenario(
    val requestPath: String,
    val subtitle: String,
  ) {
    NO_GRANT(
      requestPath = IN_ROOT_REQUEST_PATH,
      subtitle = "Seeded preview: no SAF grant is stored for this workspace yet.",
    ),
    ACTIVE_GRANT(
      requestPath = IN_ROOT_REQUEST_PATH,
      subtitle = "Seeded preview: the persisted SAF grant is active for an in-root request.",
    ),
    REVOKED_GRANT(
      requestPath = IN_ROOT_REQUEST_PATH,
      subtitle = "Seeded preview: the persisted SAF grant exists, but Android revoked it.",
    ),
    OUTSIDE_ROOT_DENIAL(
      requestPath = OUTSIDE_ROOT_REQUEST_PATH,
      subtitle = "Seeded preview: the SAF grant is active, but this request sits outside the granted root.",
    ),
  }
}

// Learning: This host instantiates the real picker view directly so the app APK keeps a production reference to WorkspacePickerScreen.
// Issue: Manifest registration still needs a later slice before instrumentation can launch this Activity directly.
