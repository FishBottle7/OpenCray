package com.opencray.app

import android.content.Context
import android.content.pm.ApplicationInfo
import com.opencray.app.e2b.E2BResponse
import com.opencray.persistence.store.DurableTextStorage
import com.opencray.persistence.store.file.DirectoryDurableTextStorage
import java.io.File
import java.net.URI
import java.net.URL

internal class RuntimeIsolationE2BEnvdEndpointOverrideStore(
  private val storage: DurableTextStorage,
) {
  fun load(): String? = normalizedLoopbackBaseUrl(storage.readText(FILE_NAME))

  fun save(baseUrl: String) {
    val normalized = requireNotNull(normalizedLoopbackBaseUrl(baseUrl)) {
      "Runtime-isolation envd endpoint must be an HTTP loopback URL with an explicit port."
    }
    storage.writeText(FILE_NAME, normalized)
  }

  fun clear(): Boolean = storage.delete(FILE_NAME)

  companion object {
    private const val FILE_NAME: String = "runtime-isolation-e2b-envd-endpoint.txt"

    fun fromContext(context: Context): RuntimeIsolationE2BEnvdEndpointOverrideStore {
      val appContext = context.applicationContext
      return RuntimeIsolationE2BEnvdEndpointOverrideStore(
        storage = DirectoryDurableTextStorage(
          File(
            appContext.filesDir,
            FileBackedAgentQueueSnapshotStoreFactory.DIRECTORY_NAME,
          ),
        ),
      )
    }
  }
}

internal class RuntimeIsolationEndpointOverrideE2BEnvdCommandTransport(
  private val delegate: E2BEnvdCommandTransport,
  private val endpointOverrideProvider: () -> String?,
) : E2BEnvdCommandTransport {
  override fun stream(
    request: E2BEnvdCommandTransportRequest,
    onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
  ): E2BResponse = delegate.stream(
    request = request.withEndpointOverride(),
    onEnvelope = onEnvelope,
  )

  override fun unary(request: E2BEnvdCommandTransportRequest): E2BResponse =
    delegate.unary(request.withEndpointOverride())

  private fun E2BEnvdCommandTransportRequest.withEndpointOverride():
    E2BEnvdCommandTransportRequest {
    val endpoint = normalizedLoopbackBaseUrl(endpointOverrideProvider()) ?: return this
    val originalUrl = runCatching { URL(url) }.getOrNull() ?: return this
    if (
      !originalUrl.protocol.equals("https", ignoreCase = true) ||
      !originalUrl.path.startsWith("/process.Process/")
    ) {
      return this
    }
    return copy(url = endpoint + originalUrl.file)
  }
}

internal fun runtimeE2BEnvdCommandTransport(
  context: Context,
  delegate: E2BEnvdCommandTransport = UrlConnectionE2BEnvdCommandTransport(),
): E2BEnvdCommandTransport {
  val appContext = context.applicationContext
  val debuggable =
    (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  if (!debuggable) {
    return delegate
  }
  val overrideStore = RuntimeIsolationE2BEnvdEndpointOverrideStore.fromContext(appContext)
  return RuntimeIsolationEndpointOverrideE2BEnvdCommandTransport(
    delegate = delegate,
    endpointOverrideProvider = overrideStore::load,
  )
}

private fun normalizedLoopbackBaseUrl(raw: String?): String? {
  val candidate = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
  val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
  if (
    !uri.scheme.equals("http", ignoreCase = true) ||
    uri.host?.lowercase() !in LOOPBACK_HOSTS ||
    uri.port !in 1..65_535 ||
    uri.rawUserInfo != null ||
    uri.rawQuery != null ||
    uri.rawFragment != null ||
    (uri.rawPath.isNotEmpty() && uri.rawPath != "/")
  ) {
    return null
  }
  return "http://${uri.host.lowercase()}:${uri.port}"
}

private val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost")
