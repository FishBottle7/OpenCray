package com.opencray.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

internal object AppExternalUriOpener {
  fun openUri(
    appContext: Context,
    uri: String,
  ) {
    val normalizedUri = uri.trim()
    require(normalizedUri.isNotEmpty()) {
      "External links are unavailable."
    }
    val targetUri = Uri.parse(normalizedUri)
    val scheme = targetUri.scheme?.trim()?.lowercase(Locale.US).orEmpty()
    require(scheme == "http" || scheme == "https") {
      "Only http and https links are supported."
    }
    val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    require(intent.resolveActivity(appContext.packageManager) != null) {
      "No application can open this link."
    }
    runCatching {
      appContext.startActivity(intent)
    }.getOrElse { throwable ->
      throw IllegalStateException("Failed to open the external link.", throwable)
    }
  }
}
