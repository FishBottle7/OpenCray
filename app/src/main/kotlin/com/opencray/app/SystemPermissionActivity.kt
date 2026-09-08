package com.opencray.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline activity that fronts the system runtime-permission
 * dialogs for agent system abilities. It exists so the runtime service
 * process can request permissions on demand (service contexts cannot show
 * permission dialogs), and so the user always sees the grant purpose text
 * next to the system prompt. It renders no UI of its own beyond the system
 * dialog and finishes immediately afterwards.
 */
internal class SystemPermissionActivity : LocalizedActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val permissionIds = intent.getStringArrayListExtra(EXTRA_PERMISSION_IDS).orEmpty()
    val specs = permissionIds.mapNotNull { SystemPermissionRegistry.specById(it) }
      .filter { it.requestSupported }
    val androidPermissions = specs
      .flatMap { it.androidPermissions }
      .filter { requiresRuntimeRequest(it) }
      .distinct()
    if (androidPermissions.isEmpty()) {
      reportResultAndFinish()
      return
    }
    val missing = androidPermissions.filter {
      ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }
    if (missing.isEmpty()) {
      reportResultAndFinish()
      return
    }
    ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQUEST_CODE) {
      return
    }
    val grantedPermissions = permissions
      .filterIndexed { index, _ -> grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED }
    SystemPermissionRequestReporter.record(grantedPermissions)
    SystemPermissionRequestGate.clearActive(
      permissions.mapNotNull(SystemPermissionRegistry::specForAndroidPermission)
        .map { it.id }
        .distinct(),
    )
    finish()
  }

  private fun reportResultAndFinish() {
    SystemPermissionRequestReporter.record(emptyList())
    SystemPermissionRequestGate.clearActive(
      intent.getStringArrayListExtra(EXTRA_PERMISSION_IDS).orEmpty(),
    )
    finish()
  }

  private fun requiresRuntimeRequest(permission: String): Boolean {
    if (permission == android.Manifest.permission.POST_NOTIFICATIONS) {
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    return true
  }

  companion object {
    private const val REQUEST_CODE = 4_112
    internal const val EXTRA_PERMISSION_IDS: String =
      "com.opencray.app.SystemPermissionActivity.extra.PERMISSION_IDS"

    fun launch(
      context: Context,
      permissionIds: List<String>,
    ) {
      val intent = Intent(context, SystemPermissionActivity::class.java).apply {
        putStringArrayListExtra(EXTRA_PERMISSION_IDS, ArrayList(permissionIds))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }
}

/**
 * Last grant outcome of SystemPermissionActivity. The host bridge polls this
 * when Flutter asks for permission state after the user returns from the
 * grant flow, so no event plumbing is required for the first cut.
 */
internal object SystemPermissionRequestReporter {
  private val lock = Any()
  private var lastGrantedPermissions: List<String> = emptyList()
  private var lastRequestCompletedAtEpochMs: Long = 0L

  fun record(grantedPermissions: List<String>) {
    synchronized(lock) {
      lastGrantedPermissions = grantedPermissions
      lastRequestCompletedAtEpochMs = System.currentTimeMillis()
    }
  }

  fun lastOutcome(): Map<String, Any?> = synchronized(lock) {
    mapOf(
      "grantedPermissions" to lastGrantedPermissions.toList(),
      "requestCompletedAtEpochMs" to lastRequestCompletedAtEpochMs,
    )
  }
}

/**
 * True while the permission activity is being shown, so the chat UI can
 * avoid re-launching the same request (OmniBot's no-duplicate-prompt rule).
 */
internal object SystemPermissionRequestGate {
  private val lock = Any()
  private val activePermissionIds = mutableSetOf<String>()

  fun markActive(permissionIds: List<String>) {
    synchronized(lock) {
      activePermissionIds.clear()
      activePermissionIds.addAll(permissionIds)
    }
  }

  fun clearActive(permissionIds: List<String>) {
    synchronized(lock) {
      activePermissionIds.removeAll(permissionIds.toSet())
    }
  }

  fun isActive(permissionIds: List<String>): Boolean = synchronized(lock) {
    val requested = permissionIds.toSet()
    requested.isNotEmpty() && activePermissionIds.containsAll(requested)
  }
}
