package com.opencray.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

internal interface ExternalAccessPermissionRequestHost {
  fun requestExternalAccessPermissions(
    permissions: Array<String>,
    callback: (Boolean) -> Unit,
  )
}

class OpenCrayFlutterActivity : FlutterActivity(), ExternalAccessPermissionRequestHost {
  private var hostBridge: OpenCrayFlutterHostBridge? = null
  private var pendingPermissionCallback: ((Boolean) -> Unit)? = null

  companion object {
    private const val EXTRA_DESTINATION =
      "com.opencray.app.OpenCrayFlutterActivity.extra.DESTINATION"
    private const val PERMISSION_REQUEST_CODE: Int = 2_401

    fun intent(
      context: Context,
      destination: Destination = Destination.SHELL,
    ): Intent = Intent(context, OpenCrayFlutterActivity::class.java).apply {
      putExtra(EXTRA_DESTINATION, destination.route)
    }
  }

  enum class Destination(val route: String) {
    SHELL("/"),
    CHAT("/chat"),
    SKILLS("/skills"),
    FILES("/files"),
    SETTINGS("/settings"),
    SETTINGS_WORKSPACE("/settings/workspace"),
    SETTINGS_LLM("/settings/llm"),
    SETTINGS_MCP("/settings/mcp"),
    SETTINGS_PRIVACY("/settings/privacy"),
    SETTINGS_SAFETY("/settings/safety"),
    SETTINGS_ABOUT("/settings/about"),
    SETTINGS_PERSONALIZATION("/settings/personalization"),
  }

  override fun attachBaseContext(newBase: Context) {
    super.attachBaseContext(OpenCrayLocaleManager.wrap(newBase))
  }

  override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    val bridge = hostBridge ?: OpenCrayFlutterHostBridge(this).also { created ->
      hostBridge = created
    }
    bridge.attach(flutterEngine)
  }

  override fun getInitialRoute(): String =
    intent.getStringExtra(EXTRA_DESTINATION) ?: Destination.SHELL.route

  override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
    hostBridge?.detach(flutterEngine)
    super.cleanUpFlutterEngine(flutterEngine)
  }

  override fun requestExternalAccessPermissions(
    permissions: Array<String>,
    callback: (Boolean) -> Unit,
  ) {
    if (permissions.isEmpty()) {
      callback(true)
      return
    }
    pendingPermissionCallback?.invoke(false)
    pendingPermissionCallback = callback
    ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != PERMISSION_REQUEST_CODE) {
      return
    }
    val callback = pendingPermissionCallback
    pendingPermissionCallback = null
    callback?.invoke(grantResults.isNotEmpty() && grantResults.all { result ->
      result == PackageManager.PERMISSION_GRANTED
    })
  }
}
