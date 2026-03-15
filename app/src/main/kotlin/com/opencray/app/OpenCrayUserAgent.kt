package com.opencray.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal object OpenCrayUserAgent {
  fun fromContext(context: Context): String = providerApi(installedVersionName(context))

  fun providerApi(versionName: String): String {
    val sanitizedVersion = versionName.trim().ifBlank { "0" }
    return "OpenCray/$sanitizedVersion (Android; host-runtime)"
  }

  private fun installedVersionName(context: Context): String {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.PackageInfoFlags.of(0),
      )
    } else {
      @Suppress("DEPRECATION")
      context.packageManager.getPackageInfo(context.packageName, 0)
    }
    return packageInfo.versionName?.trim().orEmpty().ifBlank { "0" }
  }
}
