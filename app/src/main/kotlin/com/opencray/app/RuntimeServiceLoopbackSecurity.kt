package com.opencray.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal class RuntimeServiceLoopbackCredentials private constructor(
  val epoch: String,
  secret: ByteArray,
) {
  private val secret: ByteArray = secret.copyOf()

  internal fun sign(payload: ByteArray): String {
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
    return mac.doFinal(payload).toLowerHex()
  }

  internal fun encodedSecret(): String = secret.toLowerHex()

  companion object {
    private const val HMAC_ALGORITHM: String = "HmacSHA256"
    private const val EPOCH_BYTES: Int = 16
    private const val SECRET_BYTES: Int = 32

    fun create(random: SecureRandom = SecureRandom()): RuntimeServiceLoopbackCredentials =
      RuntimeServiceLoopbackCredentials(
        epoch = randomBytes(random, EPOCH_BYTES).toLowerHex(),
        secret = randomBytes(random, SECRET_BYTES),
      )

    fun decode(
      epoch: String,
      encodedSecret: String,
    ): RuntimeServiceLoopbackCredentials? {
      val normalizedEpoch = epoch.trim().lowercase()
      val secret = encodedSecret.decodeLowerHexOrNull() ?: return null
      if (
        normalizedEpoch.length != EPOCH_BYTES * 2 ||
        normalizedEpoch.decodeLowerHexOrNull() == null ||
        secret.size != SECRET_BYTES
      ) {
        return null
      }
      return RuntimeServiceLoopbackCredentials(epoch = normalizedEpoch, secret = secret)
    }

    private fun randomBytes(
      random: SecureRandom,
      size: Int,
    ): ByteArray = ByteArray(size).also(random::nextBytes)
  }
}

internal class RuntimeServiceLoopbackDescriptor(
  val target: RuntimeServiceTarget,
  val port: Int,
  val credentials: RuntimeServiceLoopbackCredentials,
  val publishedAtEpochMs: Long,
) {
  init {
    require(port in 1..65_535) { "Loopback descriptor port is invalid." }
  }

  fun baseUrl(): String = "http://127.0.0.1:$port/"
}

internal class RuntimeServiceLoopbackDescriptorStore(
  private val directory: File,
) {
  fun publish(descriptor: RuntimeServiceLoopbackDescriptor) {
    withTargetLock(descriptor.target) {
      val descriptorFile = descriptorFile(descriptor.target)
      val bytes = JSONObject()
        .put("version", DESCRIPTOR_VERSION)
        .put("target", descriptor.target.wireValue)
        .put("host", LOOPBACK_HOST)
        .put("port", descriptor.port)
        .put("epoch", descriptor.credentials.epoch)
        .put("secret", descriptor.credentials.encodedSecret())
        .put("publishedAtEpochMs", descriptor.publishedAtEpochMs)
        .toString()
        .toByteArray(Charsets.UTF_8)
      require(bytes.size <= MAX_DESCRIPTOR_BYTES) {
        "Loopback descriptor exceeds the size limit."
      }
      val temporaryFile = File.createTempFile("${descriptorFile.name}.", ".tmp", directory)
      try {
        FileOutputStream(temporaryFile).use { output ->
          output.write(bytes)
          output.fd.sync()
        }
        try {
          Files.move(
            temporaryFile.toPath(),
            descriptorFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: AtomicMoveNotSupportedException) {
          Files.move(
            temporaryFile.toPath(),
            descriptorFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
          )
        }
      } finally {
        Files.deleteIfExists(temporaryFile.toPath())
      }
    }
  }

  fun read(target: RuntimeServiceTarget): RuntimeServiceLoopbackDescriptor? =
    withTargetLock(target) {
      val descriptorFile = descriptorFile(target)
      if (!descriptorFile.isFile) {
        return@withTargetLock null
      }
      runCatching {
        val bytes = descriptorFile.inputStream().use { input ->
          val buffer = ByteArray(MAX_DESCRIPTOR_BYTES + 1)
          var offset = 0
          while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) {
              break
            }
            offset += count
          }
          require(offset <= MAX_DESCRIPTOR_BYTES) {
            "Loopback descriptor exceeds the size limit."
          }
          buffer.copyOf(offset)
        }
        decodeDescriptor(target = target, bytes = bytes)
      }.getOrNull()
    }

  fun revoke(
    target: RuntimeServiceTarget,
    expectedEpoch: String? = null,
  ): Boolean = withTargetLock(target) {
    val descriptorFile = descriptorFile(target)
    if (!descriptorFile.isFile) {
      return@withTargetLock false
    }
    if (expectedEpoch != null) {
      val currentEpoch = runCatching {
        val raw = descriptorFile.bufferedReader(Charsets.UTF_8).use { reader ->
          reader.readText(MAX_DESCRIPTOR_BYTES)
        }
        JSONObject(raw).optString("epoch")
      }.getOrNull()
      if (currentEpoch != expectedEpoch) {
        return@withTargetLock false
      }
    }
    Files.deleteIfExists(descriptorFile.toPath())
  }

  private fun descriptorFile(target: RuntimeServiceTarget): File =
    File(directory, "runtime-service-${target.wireValue}.json")

  private inline fun <T> withTargetLock(
    target: RuntimeServiceTarget,
    action: () -> T,
  ): T {
    val normalizedDirectory = directory.absoluteFile.normalize()
    if (normalizedDirectory.exists()) {
      check(normalizedDirectory.isDirectory) {
        "Loopback descriptor path is not a directory."
      }
    } else {
      check(normalizedDirectory.mkdirs()) {
        "Unable to create loopback descriptor directory."
      }
    }
    val lockFile = File(normalizedDirectory, "runtime-service-${target.wireValue}.lock")
    val processLock = PROCESS_LOCKS.computeIfAbsent(lockFile.absolutePath) { Any() }
    return synchronized(processLock) {
      RandomAccessFile(lockFile, "rw").channel.use { channel ->
        channel.lock().use {
          action()
        }
      }
    }
  }

  private fun decodeDescriptor(
    target: RuntimeServiceTarget,
    bytes: ByteArray,
  ): RuntimeServiceLoopbackDescriptor {
    val payload = JSONObject(String(bytes, Charsets.UTF_8))
    require(payload.optInt("version") == DESCRIPTOR_VERSION)
    require(payload.optString("target") == target.wireValue)
    require(payload.optString("host") == LOOPBACK_HOST)
    val credentials = RuntimeServiceLoopbackCredentials.decode(
      epoch = payload.optString("epoch"),
      encodedSecret = payload.optString("secret"),
    ) ?: error("Loopback descriptor credentials are invalid.")
    return RuntimeServiceLoopbackDescriptor(
      target = target,
      port = payload.optInt("port"),
      credentials = credentials,
      publishedAtEpochMs = payload.optLong("publishedAtEpochMs"),
    )
  }

  companion object {
    private const val DESCRIPTOR_VERSION: Int = 1
    private const val MAX_DESCRIPTOR_BYTES: Int = 8 * 1024
    private const val LOOPBACK_HOST: String = "127.0.0.1"
    private val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()

    fun fromContext(context: Context): RuntimeServiceLoopbackDescriptorStore =
      RuntimeServiceLoopbackDescriptorStore(
        File(
          requireNotNull(context.noBackupFilesDir) {
            "Android no-backup directory is unavailable."
          },
          "runtime-loopback",
        ),
      )
  }
}

internal data class RuntimeServiceLoopbackAuthenticatedExchange(
  val epoch: String,
  val requestTimestampEpochMs: Long,
  val nonce: String,
  val method: String,
  val requestTarget: String,
)

internal class RuntimeServiceLoopbackServerSecurity(
  private val credentials: RuntimeServiceLoopbackCredentials,
  private val clock: () -> Long = System::currentTimeMillis,
  private val allowedClockSkewMs: Long = DEFAULT_ALLOWED_CLOCK_SKEW_MS,
  private val maxRememberedNonces: Int = DEFAULT_MAX_REMEMBERED_NONCES,
) {
  private val nonceLock = Any()
  private val acceptedNonces = linkedMapOf<String, Long>()

  fun authenticate(
    headers: Map<String, String>,
    method: String,
    requestTarget: String,
    body: ByteArray,
  ): RuntimeServiceLoopbackAuthenticatedExchange? {
    val epoch = headers[RuntimeServiceLoopbackHttpAuth.HEADER_EPOCH].orEmpty()
    val timestamp = headers[RuntimeServiceLoopbackHttpAuth.HEADER_TIMESTAMP]
      ?.toLongOrNull()
      ?: return null
    val nonce = headers[RuntimeServiceLoopbackHttpAuth.HEADER_NONCE].orEmpty().lowercase()
    val signature = headers[RuntimeServiceLoopbackHttpAuth.HEADER_SIGNATURE].orEmpty().lowercase()
    val now = clock()
    if (
      epoch != credentials.epoch ||
      nonce.length != RuntimeServiceLoopbackHttpAuth.NONCE_HEX_LENGTH ||
      nonce.decodeLowerHexOrNull() == null ||
      !timestamp.isFresh(now = now, allowedClockSkewMs = allowedClockSkewMs)
    ) {
      return null
    }
    val expectedSignature = RuntimeServiceLoopbackHttpAuth.requestSignature(
      credentials = credentials,
      timestampEpochMs = timestamp,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
      body = body,
    )
    if (!constantTimeHexEquals(expectedSignature, signature)) {
      return null
    }
    synchronized(nonceLock) {
      val oldestAllowedTimestamp = now - allowedClockSkewMs
      val iterator = acceptedNonces.entries.iterator()
      while (iterator.hasNext()) {
        if (iterator.next().value < oldestAllowedTimestamp) {
          iterator.remove()
        }
      }
      if (acceptedNonces.containsKey(nonce) || acceptedNonces.size >= maxRememberedNonces) {
        return null
      }
      acceptedNonces[nonce] = timestamp
    }
    return RuntimeServiceLoopbackAuthenticatedExchange(
      epoch = epoch,
      requestTimestampEpochMs = timestamp,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
    )
  }

  fun responseHeaders(
    exchange: RuntimeServiceLoopbackAuthenticatedExchange,
    statusCode: Int,
    body: ByteArray,
  ): Map<String, String> {
    val timestamp = clock()
    val signature = RuntimeServiceLoopbackHttpAuth.responseSignature(
      credentials = credentials,
      timestampEpochMs = timestamp,
      nonce = exchange.nonce,
      method = exchange.method,
      requestTarget = exchange.requestTarget,
      statusCode = statusCode,
      body = body,
    )
    return mapOf(
      RuntimeServiceLoopbackHttpAuth.HEADER_EPOCH_WIRE to credentials.epoch,
      RuntimeServiceLoopbackHttpAuth.HEADER_TIMESTAMP_WIRE to timestamp.toString(),
      RuntimeServiceLoopbackHttpAuth.HEADER_NONCE_WIRE to exchange.nonce,
      RuntimeServiceLoopbackHttpAuth.HEADER_SIGNATURE_WIRE to signature,
    )
  }

  companion object {
    internal const val DEFAULT_ALLOWED_CLOCK_SKEW_MS: Long = 60_000L
    private const val DEFAULT_MAX_REMEMBERED_NONCES: Int = 16_384
  }
}

internal object RuntimeServiceLoopbackHttpAuth {
  const val HEADER_EPOCH_WIRE: String = "X-OpenCray-Epoch"
  const val HEADER_TIMESTAMP_WIRE: String = "X-OpenCray-Timestamp"
  const val HEADER_NONCE_WIRE: String = "X-OpenCray-Nonce"
  const val HEADER_SIGNATURE_WIRE: String = "X-OpenCray-Signature"
  const val HEADER_EPOCH: String = "x-opencray-epoch"
  const val HEADER_TIMESTAMP: String = "x-opencray-timestamp"
  const val HEADER_NONCE: String = "x-opencray-nonce"
  const val HEADER_SIGNATURE: String = "x-opencray-signature"
  const val NONCE_HEX_LENGTH: Int = 32
  private const val NONCE_BYTES: Int = NONCE_HEX_LENGTH / 2
  private const val PROTOCOL_VERSION: String = "opencray-loopback-v1"
  private val NONCE_RANDOM = SecureRandom()

  fun requestHeaders(
    credentials: RuntimeServiceLoopbackCredentials,
    timestampEpochMs: Long,
    nonce: String = newNonce(),
    method: String,
    requestTarget: String,
    body: ByteArray,
  ): Map<String, String> = mapOf(
    HEADER_EPOCH_WIRE to credentials.epoch,
    HEADER_TIMESTAMP_WIRE to timestampEpochMs.toString(),
    HEADER_NONCE_WIRE to nonce,
    HEADER_SIGNATURE_WIRE to requestSignature(
      credentials = credentials,
      timestampEpochMs = timestampEpochMs,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
      body = body,
    ),
  )

  fun verifyResponse(
    credentials: RuntimeServiceLoopbackCredentials,
    requestTimestampEpochMs: Long,
    requestNonce: String,
    method: String,
    requestTarget: String,
    statusCode: Int,
    body: ByteArray,
    headers: Map<String, String>,
    nowEpochMs: Long = System.currentTimeMillis(),
    allowedClockSkewMs: Long = RuntimeServiceLoopbackServerSecurity.DEFAULT_ALLOWED_CLOCK_SKEW_MS,
  ): Boolean {
    val epoch = headers[HEADER_EPOCH].orEmpty()
    val timestamp = headers[HEADER_TIMESTAMP]?.toLongOrNull() ?: return false
    val nonce = headers[HEADER_NONCE].orEmpty().lowercase()
    val signature = headers[HEADER_SIGNATURE].orEmpty().lowercase()
    if (
      epoch != credentials.epoch ||
      nonce != requestNonce ||
      timestamp < requestTimestampEpochMs - allowedClockSkewMs ||
      !timestamp.isFresh(now = nowEpochMs, allowedClockSkewMs = allowedClockSkewMs)
    ) {
      return false
    }
    val expectedSignature = responseSignature(
      credentials = credentials,
      timestampEpochMs = timestamp,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
      statusCode = statusCode,
      body = body,
    )
    return constantTimeHexEquals(expectedSignature, signature)
  }

  internal fun requestSignature(
    credentials: RuntimeServiceLoopbackCredentials,
    timestampEpochMs: Long,
    nonce: String,
    method: String,
    requestTarget: String,
    body: ByteArray,
  ): String = credentials.sign(
    canonicalPayload(
      direction = "request",
      epoch = credentials.epoch,
      timestampEpochMs = timestampEpochMs,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
      statusCode = null,
      body = body,
    ),
  )

  internal fun responseSignature(
    credentials: RuntimeServiceLoopbackCredentials,
    timestampEpochMs: Long,
    nonce: String,
    method: String,
    requestTarget: String,
    statusCode: Int,
    body: ByteArray,
  ): String = credentials.sign(
    canonicalPayload(
      direction = "response",
      epoch = credentials.epoch,
      timestampEpochMs = timestampEpochMs,
      nonce = nonce,
      method = method,
      requestTarget = requestTarget,
      statusCode = statusCode,
      body = body,
    ),
  )

  private fun canonicalPayload(
    direction: String,
    epoch: String,
    timestampEpochMs: Long,
    nonce: String,
    method: String,
    requestTarget: String,
    statusCode: Int?,
    body: ByteArray,
  ): ByteArray = listOf(
    PROTOCOL_VERSION,
    direction,
    epoch,
    timestampEpochMs.toString(),
    nonce,
    method.uppercase(),
    requestTarget,
    statusCode?.toString().orEmpty(),
    MessageDigest.getInstance("SHA-256").digest(body).toLowerHex(),
  ).joinToString(separator = "\n") { value ->
    "${value.toByteArray(Charsets.UTF_8).size}:$value"
  }.toByteArray(Charsets.UTF_8)

  private fun newNonce(): String = ByteArray(NONCE_BYTES)
    .also(NONCE_RANDOM::nextBytes)
    .toLowerHex()
}

private fun Long.isFresh(
  now: Long,
  allowedClockSkewMs: Long,
): Boolean = this >= now - allowedClockSkewMs && this <= now + allowedClockSkewMs

private fun constantTimeHexEquals(
  expected: String,
  actual: String,
): Boolean {
  val expectedBytes = expected.decodeLowerHexOrNull() ?: return false
  val actualBytes = actual.decodeLowerHexOrNull() ?: return false
  return MessageDigest.isEqual(expectedBytes, actualBytes)
}

private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
  for (byte in this@toLowerHex) {
    val value = byte.toInt() and 0xff
    append(HEX_DIGITS[value ushr 4])
    append(HEX_DIGITS[value and 0x0f])
  }
}

private fun String.decodeLowerHexOrNull(): ByteArray? {
  if (length % 2 != 0) {
    return null
  }
  val result = ByteArray(length / 2)
  for (index in result.indices) {
    val high = this[index * 2].digitToIntOrNull(16) ?: return null
    val low = this[index * 2 + 1].digitToIntOrNull(16) ?: return null
    result[index] = ((high shl 4) or low).toByte()
  }
  return result
}

private fun java.io.Reader.readText(maxChars: Int): String {
  val buffer = CharArray(maxChars + 1)
  var offset = 0
  while (offset < buffer.size) {
    val count = read(buffer, offset, buffer.size - offset)
    if (count < 0) {
      break
    }
    offset += count
  }
  if (offset > maxChars) {
    throw IOException("Loopback descriptor exceeds the size limit.")
  }
  return String(buffer, 0, offset)
}

private const val HEX_DIGITS: String = "0123456789abcdef"
