package com.opencray.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

internal interface ExternalAccessPermissionRequestHost {
  fun requestExternalAccessPermissions(
    permissions: Array<String>,
    callback: (Boolean) -> Unit,
  )
}

internal interface ChatAttachmentPickerHost {
  fun pickChatAttachments(
    requestedKind: String,
    callback: (Result<List<String>>) -> Unit,
  )
}

class OpenCrayFlutterActivity :
  FlutterActivity(),
  ExternalAccessPermissionRequestHost,
  ChatAttachmentPickerHost {
  private var hostBridge: OpenCrayFlutterHostBridge? = null
  private var pendingPermissionCallback: ((Boolean) -> Unit)? = null
  private var pendingChatAttachmentCallback: ((Result<List<String>>) -> Unit)? = null

  companion object {
    private const val EXTRA_DESTINATION =
      "com.opencray.app.OpenCrayFlutterActivity.extra.DESTINATION"
    private const val PERMISSION_REQUEST_CODE: Int = 2_401
    private const val CHAT_ATTACHMENT_PICKER_REQUEST_CODE: Int = 2_402

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
    SETTINGS_PERSONALIZATION("/settings/personalization"),
    SETTINGS_AGENTS("/settings/agents"),
    SETTINGS_ABOUT("/settings/about"),
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

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != CHAT_ATTACHMENT_PICKER_REQUEST_CODE) {
      return
    }
    val callback = pendingChatAttachmentCallback
    pendingChatAttachmentCallback = null
    if (callback == null) {
      return
    }
    if (resultCode != RESULT_OK) {
      callback.invoke(Result.success(emptyList()))
      return
    }
    val uris = buildList {
      data?.data?.let { uri -> add(uri) }
      val clipData = data?.clipData
      if (clipData != null) {
        repeat(clipData.itemCount) { index ->
          clipData.getItemAt(index)?.uri?.let { uri -> add(uri) }
        }
      }
    }.distinct().map(Uri::toString)
    callback.invoke(Result.success(uris))
  }

  override fun pickChatAttachments(
    requestedKind: String,
    callback: (Result<List<String>>) -> Unit,
  ) {
    val normalizedKind = requestedKind.trim().lowercase()
    val mimeTypes = when (normalizedKind) {
      "image" -> arrayOf("image/*")
      "file" -> arrayOf("*/*")
      else -> {
        callback.invoke(
          Result.failure(
            IllegalArgumentException("Unsupported chat attachment kind: $requestedKind"),
          ),
        )
        return
      }
    }
    pendingChatAttachmentCallback?.invoke(Result.success(emptyList()))
    pendingChatAttachmentCallback = callback
    startActivityForResult(
      Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = if (normalizedKind == "image") "image/*" else "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, intent?.data)
      },
      CHAT_ATTACHMENT_PICKER_REQUEST_CODE,
    )
  }
}
