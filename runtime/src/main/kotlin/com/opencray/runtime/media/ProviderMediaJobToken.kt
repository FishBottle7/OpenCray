package com.opencray.runtime.media

import com.opencray.runtime.OpenCrayMediaJobReceipt
import com.opencray.runtime.OpenCrayMediaJobSnapshot
import com.opencray.runtime.OpenCrayMediaJobStatus
import com.opencray.runtime.OpenCrayToolDispatcher
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val PROVIDER_MEDIA_JOB_ID_PREFIX: String = "provider_media_job:"
internal val ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS: Set<String> = setOf(
  "providerPollUrl",
  "providerCancelUrl",
)
private const val PROVIDER_MEDIA_JOB_TOKEN_SECRET_FILE_NAME = "provider-media-job-token-secret.bin"
private const val PROVIDER_MEDIA_JOB_TOKEN_SECRET_BYTES = 32
private const val PROVIDER_MEDIA_JOB_TOKEN_MAC_ALGORITHM = "HmacSHA256"

internal sealed interface ProviderMediaJobTokenDecoding {
  data class Valid(val snapshot: OpenCrayMediaJobSnapshot) : ProviderMediaJobTokenDecoding
  object NotProviderToken : ProviderMediaJobTokenDecoding
  data class Invalid(val reason: String) : ProviderMediaJobTokenDecoding
}

internal class ProviderMediaJobTokenSigner private constructor(private val secret: ByteArray) {
  init {
    require(secret.size >= PROVIDER_MEDIA_JOB_TOKEN_SECRET_BYTES) {
      "Provider media job token secret must be at least $PROVIDER_MEDIA_JOB_TOKEN_SECRET_BYTES bytes."
    }
  }

  fun sign(payload: ByteArray): ByteArray {
    val mac = Mac.getInstance(PROVIDER_MEDIA_JOB_TOKEN_MAC_ALGORITHM)
    mac.init(SecretKeySpec(secret, PROVIDER_MEDIA_JOB_TOKEN_MAC_ALGORITHM))
    return mac.doFinal(payload)
  }

  fun verify(
    payload: ByteArray,
    signature: ByteArray,
  ): Boolean = MessageDigest.isEqual(sign(payload), signature)

  companion object {
    @Volatile
    private var globalInstance: ProviderMediaJobTokenSigner? = null

    fun global(secretDirectory: Path?): ProviderMediaJobTokenSigner =
      globalInstance ?: synchronized(this) {
        globalInstance ?: ProviderMediaJobTokenSigner(loadOrCreateSecret(secretDirectory))
          .also { signer -> globalInstance = signer }
      }

    internal fun forSecret(secret: ByteArray): ProviderMediaJobTokenSigner =
      ProviderMediaJobTokenSigner(secret.copyOf())

    internal fun resetGlobalForTest() {
      synchronized(this) {
        globalInstance = null
      }
    }

    private fun loadOrCreateSecret(directory: Path?): ByteArray {
      if (directory == null) {
        return randomSecret()
      }
      val secretFile = directory.resolve(PROVIDER_MEDIA_JOB_TOKEN_SECRET_FILE_NAME)
      runCatching {
        if (Files.isRegularFile(secretFile)) {
          val stored = Files.readAllBytes(secretFile)
          if (stored.size == PROVIDER_MEDIA_JOB_TOKEN_SECRET_BYTES) {
            return stored
          }
        }
      }
      val secret = randomSecret()
      runCatching {
        Files.createDirectories(directory)
        val temporaryFile = Files.createTempFile(directory, PROVIDER_MEDIA_JOB_TOKEN_SECRET_FILE_NAME, ".tmp")
        try {
          Files.write(temporaryFile, secret)
          restrictToOwner(temporaryFile)
          runCatching {
            Files.move(
              temporaryFile,
              secretFile,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.ATOMIC_MOVE,
            )
          }.getOrElse {
            Files.move(temporaryFile, secretFile, StandardCopyOption.REPLACE_EXISTING)
          }
        } finally {
          Files.deleteIfExists(temporaryFile)
        }
      }
      return secret
    }

    private fun restrictToOwner(path: Path) {
      runCatching {
        val posixView = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posixView != null) {
          posixView.setPermissions(PosixFilePermissions.fromString("rw-------"))
        }
      }
    }

    private fun randomSecret(): ByteArray = ByteArray(PROVIDER_MEDIA_JOB_TOKEN_SECRET_BYTES).also { bytes ->
      SecureRandom().nextBytes(bytes)
    }
  }
}

internal fun OpenCrayToolDispatcher.providerMediaJobTokenSigner(): ProviderMediaJobTokenSigner =
  ProviderMediaJobTokenSigner.global(config.fileMutationLockDirectory?.parent)

internal fun OpenCrayToolDispatcher.encodeProviderMediaJobId(snapshot: OpenCrayMediaJobSnapshot): String {
  val payload = buildJsonObject {
    put("v", 2)
    put("toolName", snapshot.receipt.toolName)
    put("providerJobId", snapshot.receipt.jobId)
    put("status", snapshot.receipt.status.name.lowercase(Locale.US))
    put("pollAfterMs", snapshot.receipt.pollAfterMs)
    put("issuedAtEpochMs", System.currentTimeMillis())
    snapshot.providerRequestId?.let { put("providerRequestId", it) }
    put(
      "metadata",
      buildJsonObject {
        snapshot.metadata
          .filterKeys { key -> key in ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS }
          .toSortedMap()
          .forEach { (key, value) ->
            put(key, value)
          }
      },
    )
  }
  val payloadBytes = config.json.encodeToString(JsonObject.serializer(), payload).toByteArray(StandardCharsets.UTF_8)
  val signatureBytes = providerMediaJobTokenSigner().sign(payloadBytes)
  val encoder = Base64.getUrlEncoder().withoutPadding()
  val encodedPayload = encoder.encodeToString(payloadBytes)
  val encodedSignature = encoder.encodeToString(signatureBytes)
  return "$PROVIDER_MEDIA_JOB_ID_PREFIX$encodedPayload.$encodedSignature"
}

internal fun OpenCrayToolDispatcher.decodeProviderMediaJobId(jobId: String): ProviderMediaJobTokenDecoding {
  if (!jobId.startsWith(PROVIDER_MEDIA_JOB_ID_PREFIX)) {
    return ProviderMediaJobTokenDecoding.NotProviderToken
  }
  val encodedToken = jobId.removePrefix(PROVIDER_MEDIA_JOB_ID_PREFIX)
  val separatorIndex = encodedToken.lastIndexOf('.')
  if (separatorIndex <= 0 || separatorIndex == encodedToken.length - 1) {
    return ProviderMediaJobTokenDecoding.Invalid(
      "job_id is not a signed provider media job token; unsigned legacy job ids are no longer accepted.",
    )
  }
  val decoder = Base64.getUrlDecoder()
  val payloadBytes = runCatching {
    decoder.decode(encodedToken.substring(0, separatorIndex))
  }.getOrNull() ?: return ProviderMediaJobTokenDecoding.Invalid(
    "job_id payload is not valid base64url data.",
  )
  val signatureBytes = runCatching {
    decoder.decode(encodedToken.substring(separatorIndex + 1))
  }.getOrNull() ?: return ProviderMediaJobTokenDecoding.Invalid(
    "job_id signature is not valid base64url data.",
  )
  if (!providerMediaJobTokenSigner().verify(payloadBytes, signatureBytes)) {
    return ProviderMediaJobTokenDecoding.Invalid(
      "job_id signature verification failed; the token may be forged or corrupted.",
    )
  }
  val decoded = runCatching {
    String(payloadBytes, StandardCharsets.UTF_8)
  }.getOrNull() ?: return ProviderMediaJobTokenDecoding.Invalid(
    "job_id payload is not valid UTF-8 data.",
  )
  val payload = runCatching { config.json.parseToJsonElement(decoded) }
    .getOrNull()
    ?.let { it as? JsonObject }
    ?: return ProviderMediaJobTokenDecoding.Invalid(
      "job_id payload is not a valid JSON object.",
    )
  val toolName = (payload["toolName"] as? JsonPrimitive)?.content.orEmpty()
    .takeIf(String::isNotBlank)
    ?: return ProviderMediaJobTokenDecoding.Invalid("job_id payload is missing toolName.")
  val providerJobId = (payload["providerJobId"] as? JsonPrimitive)?.content.orEmpty()
    .takeIf(String::isNotBlank)
    ?: return ProviderMediaJobTokenDecoding.Invalid("job_id payload is missing providerJobId.")
  val status = (payload["status"] as? JsonPrimitive)?.content
    ?.trim()
    ?.uppercase(Locale.US)
    ?.let { raw -> OpenCrayMediaJobStatus.entries.firstOrNull { entry -> entry.name == raw } }
    ?: OpenCrayMediaJobStatus.PENDING
  val pollAfterMs = (payload["pollAfterMs"] as? JsonPrimitive)?.content
    ?.toLongOrNull()
    ?.takeIf { it > 0L }
    ?: 1_000L
  val providerRequestId = (payload["providerRequestId"] as? JsonPrimitive)?.content
    ?.takeIf(String::isNotBlank)
  val metadata = (payload["metadata"] as? JsonObject)
    ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
    .orEmpty()
  return ProviderMediaJobTokenDecoding.Valid(
    OpenCrayMediaJobSnapshot(
      receipt = OpenCrayMediaJobReceipt(
        jobId = providerJobId,
        toolName = toolName,
        status = status,
        pollAfterMs = pollAfterMs,
      ),
      providerRequestId = providerRequestId,
      metadata = metadata.filterKeys { key -> key in ENCODED_PROVIDER_MEDIA_JOB_METADATA_KEYS },
    ),
  )
}

internal fun endpointOriginOrNull(url: String?): String? {
  val normalized = url?.trim()?.takeIf(String::isNotBlank) ?: return null
  return runCatching {
    val parsed = java.net.URL(normalized)
    val scheme = parsed.protocol.trim().lowercase(Locale.US)
    val host = parsed.host.trim().lowercase(Locale.US)
    if (scheme.isBlank() || host.isBlank()) {
      null
    } else {
      val port = when {
        parsed.port >= 0 -> parsed.port
        parsed.defaultPort >= 0 -> parsed.defaultPort
        else -> -1
      }
      if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
    }
  }.getOrNull()
}
