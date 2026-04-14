package com.opencray.app

import java.io.File

internal fun LiteRtOnDeviceModelInstallStore.hasReadyModel(modelId: String): Boolean {
  val selectedModelId = modelId.trim().lowercase().takeIf(String::isNotBlank) ?: return false
  if (!OnDeviceLlmCatalog.hasModel(selectedModelId)) {
    return false
  }
  val installRecord = load(selectedModelId) ?: return false
  if (OnDeviceLlmDownloadStates.normalize(installRecord.installState) != OnDeviceLlmDownloadStates.READY) {
    return false
  }
  val localFilePath = installRecord.localFilePath.trim().takeIf(String::isNotBlank) ?: return false
  return runCatching { File(localFilePath).isFile }.getOrDefault(false)
}

internal fun LlmSettingsState.isOperationallyConfigured(
  onDeviceModelInstallStore: LiteRtOnDeviceModelInstallStore,
): Boolean = if (isOnDeviceProviderMode()) {
  isConfigured() && onDeviceModelInstallStore.hasReadyModel(selectedOnDeviceModelId)
} else {
  isConfigured()
}
