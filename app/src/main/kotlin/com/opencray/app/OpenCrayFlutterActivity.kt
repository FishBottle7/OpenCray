package com.opencray.app

import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class OpenCrayFlutterActivity : FlutterActivity() {
  private var hostBridge: OpenCrayFlutterHostBridge? = null

  companion object {
    private const val EXTRA_DESTINATION =
      "com.opencray.app.OpenCrayFlutterActivity.extra.DESTINATION"

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
}
