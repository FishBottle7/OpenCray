package com.opencray.app

import android.content.Context
import android.content.res.AssetManager
import com.opencray.runtime.PythonRuntimeManifestSnapshot
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class PythonRuntimeManifestAssetProvider private constructor(
  private val assets: AssetManager,
  private val json: Json = Json { ignoreUnknownKeys = true },
) {
  private val cachedManifest = AtomicReference<PythonRuntimeManifestSnapshot?>(null)

  fun currentManifest(): PythonRuntimeManifestSnapshot? {
    cachedManifest.get()?.let { manifest -> return manifest }
    val loadedManifest = runCatching {
      assets.open(PYTHON_RUNTIME_MANIFEST_ASSET_PATH).use { input ->
        json.decodeFromString<PythonRuntimeManifestSnapshot>(
          String(input.readBytes(), StandardCharsets.UTF_8),
        )
      }
    }.getOrNull()
    if (loadedManifest != null) {
      cachedManifest.compareAndSet(null, loadedManifest)
    }
    return cachedManifest.get()
  }

  companion object {
    private const val PYTHON_RUNTIME_MANIFEST_ASSET_PATH = "python-runtime/python-runtime-manifest.json"

    fun fromContext(context: Context): PythonRuntimeManifestAssetProvider = PythonRuntimeManifestAssetProvider(
      assets = context.applicationContext.assets,
    )
  }
}
