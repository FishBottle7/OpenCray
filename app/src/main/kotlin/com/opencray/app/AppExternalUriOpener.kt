package com.opencray.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ActivityNotFoundException
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
    val viewIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
      addCategory(Intent.CATEGORY_BROWSABLE)
    }
    val chooserIntent = Intent.createChooser(viewIntent, null).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
      appContext.startActivity(chooserIntent)
    } catch (_: ActivityNotFoundException) {
      throw IllegalStateException("No application can open this link.")
    } catch (throwable: Throwable) {
      throw IllegalStateException("Failed to open the external link.", throwable)
    }
  }
}
