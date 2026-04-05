package com.opencray.app

import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandApprovalToken
import com.opencray.runtime.CommandExecutionConfig
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.CommandProcessRunner
import com.opencray.runtime.CommandSpawnResult
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessDeliveredObservationState
import com.opencray.runtime.process.ManagedProcessObservationState
import com.opencray.runtime.process.ManagedProcessReconnectSeed
import com.opencray.runtime.process.ManagedProcessReconnectState
import com.opencray.runtime.process.ManagedProcessRemoteHandle
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ManagedProcessStatus
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import com.opencray.runtime.process.normalizedDeliveredObservationState
import com.opencray.runtime.process.normalizedObservationState
import com.opencray.runtime.process.normalizedReconnectState
import com.opencray.runtime.process.normalizedRemoteHandle
import com.opencray.runtime.process.withNormalizedRemoteState
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val E2B_ENVD_PORT: Int = 49983
private const val E2B_CONNECT_PROTOCOL_VERSION: String = "1"
private const val E2B_CONNECT_CONTENT_TYPE_PROTO: String = "application/connect+proto"
private const val E2B_ENVD_KEEPALIVE_PING_INTERVAL: String = "50"
private const val E2B_ENVD_DEFAULT_USER: String = "user"
private const val E2B_NATIVE_COMMAND_RUNTIME_BACKEND: String = "e2b_envd_native_command"
private const val E2B_NATIVE_COMMAND_TRANSPORT: String = "connect_proto_minimal"
private const val E2B_NATIVE_COMMAND_API: String = "envd_process_start"
private const val E2B_NATIVE_COMMAND_CONNECT_API: String = "envd_process_connect"
private const val E2B_NATIVE_COMMAND_SIGNAL_API: String = "envd_process_send_signal"
private const val E2B_NATIVE_COMMAND_PROTOCOL: String = "envd_connect_process_v1"
private const val E2B_NATIVE_COMMAND_TERMINATION_SUPPORT: String = "provider_native_signal"
private const val E2B_NATIVE_COMMAND_OBSERVATION_MODE: String = "host_managed_snapshot"
private const val E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_MODE: String =
  "provider_event_stream_host_buffered"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_CONTRACT: String =
  "host_buffered_seed_then_live_attach"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_BLOCKER: String =
  "envd_connect_request_selector_only"
private const val E2B_NATIVE_COMMAND_PROVIDER_HANDLE_KIND: String = "envd_process"
private const val E2B_NATIVE_COMMAND_HANDLE_ID_KIND: String = "tag"
private const val E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG: String = "tag"
private const val E2B_NATIVE_COMMAND_SELECTOR_KIND_PID: String = "pid"
private const val E2B_NATIVE_COMMAND_RECONNECT_SELECTOR_SOURCE_SNAPSHOT_PID: String = "snapshot_pid"
private const val E2B_NATIVE_COMMAND_RECONNECT_SELECTOR_SOURCE_STABLE_TAG: String = "stable_tag"
private const val E2B_NATIVE_COMMAND_OBSERVATION_CURSOR_PREFIX: String = "host_seq_"
private const val E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_CURSOR_PREFIX: String = "envd_seq_"
private const val E2B_NATIVE_COMMAND_RECONNECT_SOURCE_DURABLE: String = "durable_registry_restore"
private const val E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_SNAPSHOT_METADATA: String =
  "durable_snapshot_metadata"
private const val E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_DELIVERED_OBSERVATION: String =
  "durable_delivered_observation_state"
private const val E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_OBSERVATION_SNAPSHOT: String =
  "observation_snapshot_metadata"
private const val E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_PENDING_LIVE_ATTACH: String =
  "pending_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_CONSUMED_LIVE_ATTACH: String =
  "consumed_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_RETRY_SCHEDULED_BEFORE_LIVE_ATTACH:
  String = "retry_scheduled_before_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_FAILED_TERMINAL_BEFORE_LIVE_ATTACH:
  String = "failed_terminal_before_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_CONNECT_END_STREAM_BEFORE_LIVE_ATTACH:
  String = "connect_end_stream_before_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_MISSING_PROCESS_END_BEFORE_LIVE_ATTACH:
  String = "missing_process_end_event_before_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_MISSING_PROCESS_END_AFTER_LIVE_ATTACH:
  String = "missing_process_end_event_after_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_TRANSPORT_EXCEPTION_BEFORE_LIVE_ATTACH:
  String = "transport_exception_before_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_TRANSPORT_EXCEPTION_AFTER_LIVE_ATTACH:
  String = "transport_exception_after_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_DELIVERED_SEED_THEN_LIVE_ATTACH: String =
  "delivered_seed_then_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_SNAPSHOT_SEED_THEN_LIVE_ATTACH: String =
  "seed_snapshot_then_live_attach"
private const val E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_OBSERVATION_SNAPSHOT_THEN_LIVE_ATTACH:
  String = "observation_snapshot_then_live_attach"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_REASON_PROTOCOL_CURSOR_UNSUPPORTED: String =
  "protocol_cursor_resume_unsupported"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_REASON_LIVE_ATTACH_ONLY: String =
  "live_attach_only"
private const val E2B_NATIVE_COMMAND_RECONNECT_RETRY_BACKOFF_MS: Long = 1_000L
private const val E2B_NATIVE_COMMAND_OUTPUT_BYTE_LIMIT: Int = 64_000
private const val CONNECT_ENVELOPE_FLAG_COMPRESSED: Int = 0x01
private const val CONNECT_ENVELOPE_FLAG_END_STREAM: Int = 0x02

internal class E2BMinimalProtocolSandboxCommandExecutionBackend(
  private val workspaceRootProvider: () -> Path,
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
  private val pythonRuntime: PythonScriptRuntime,
  private val transport: E2BEnvdCommandTransport = UrlConnectionE2BEnvdCommandTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SandboxCommandExecutionBackend {
  override val capabilities: SandboxCommandBackendCapabilities = SandboxCommandBackendCapabilities(
    backendKind = "provider_native",
    providerNative = true,
    supportsStreamingLogs = false,
    supportsReconnect = true,
    supportsManagedProcessLiveObservation = true,
    supportsManagedProcessObservationCursorResume = false,
    supportsManagedProcessObservationBackfill = false,
    providerObservationResumeContract = E2B_NATIVE_COMMAND_PROVIDER_RESUME_CONTRACT,
    providerObservationResumeBlocker = E2B_NATIVE_COMMAND_PROVIDER_RESUME_BLOCKER,
  )

  override fun createCommandExecutor(): CommandExecutor {
    val delegate = CommandExecutor(
      runner = E2BMinimalNativeForegroundCommandProcessRunner(
        workspaceRootProvider = workspaceRootProvider,
        settingsProvider = settingsProvider,
        sessionStore = sessionStore,
        activeSessionProvider = activeSessionProvider,
        fallbackRunnerProvider = {
          PythonBackedCommandProcessRunner(
            workspaceRoot = workspaceRootProvider(),
            pythonRuntime = pythonRuntime,
            json = json,
          )
        },
        transport = transport,
        capabilities = capabilities,
        json = json,
      ),
    )
    return object : CommandExecutor() {
      override fun execute(
        request: CommandExecutionRequest,
        policyDecision: PolicyDecision,
        approvalToken: CommandApprovalToken?,
        hooks: RuntimeExecutionHooks,
      ): ExecutionResult {
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = request.metadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerStartMetadata(
          request.metadata + backendTraceMetadata,
        )
        val tracedRequest = request.copy(
          metadata = request.metadata + backendTraceMetadata + providerTraceMetadata,
        )
        val result = delegate.execute(
          request = tracedRequest,
          policyDecision = policyDecision,
          approvalToken = approvalToken,
          hooks = hooks,
        )
        return result.copy(
          metadata = result.metadata + backendTraceMetadata + providerTraceMetadata,
        )
      }
    }
  }

  override fun createManagedProcessControllerFactory(): ManagedProcessControllerFactory {
    val delegate: ReconnectableManagedProcessControllerFactory =
      E2BMinimalProtocolManagedProcessControllerFactory(
        workspaceRootProvider = workspaceRootProvider,
        settingsProvider = settingsProvider,
        sessionStore = sessionStore,
        activeSessionProvider = activeSessionProvider,
        fallbackFactoryProvider = {
          PythonBackedSandboxCommandExecutionBackend(
            workspaceRootProvider = workspaceRootProvider,
            pythonRuntime = pythonRuntime,
            json = json,
          ).createManagedProcessControllerFactory()
        },
        transport = transport,
        capabilities = capabilities,
        json = json,
      )
    return object : ReconnectableManagedProcessControllerFactory {
      override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = request.metadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerStartMetadata(
          request.metadata + backendTraceMetadata,
        )
        val controller = delegate.start(
          request.copy(
            metadata = request.metadata + backendTraceMetadata + providerTraceMetadata,
          ),
        )
        return decorateController(
          controller = controller,
          decorationMetadata = backendTraceMetadata + providerTraceMetadata,
        )
      }

      override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
        val reconnectTraceMetadata = SandboxExecutionTraceMetadata.reconnectMetadata(snapshot.metadata)
        val backendTraceMetadata = SandboxExecutionTraceMetadata.backendMetadata(
          metadata = snapshot.metadata + reconnectTraceMetadata,
          backendKind = capabilities.backendKind,
        )
        val providerTraceMetadata = SandboxExecutionTraceMetadata.providerConnectMetadata(
          snapshot.metadata + reconnectTraceMetadata + backendTraceMetadata,
        )
        val controller = delegate.reconnect(
          snapshot.copy(
            metadata =
              snapshot.metadata +
                reconnectTraceMetadata +
                backendTraceMetadata +
                providerTraceMetadata,
          ),
        ) ?: return null
        return decorateController(
          controller = controller,
          decorationMetadata =
            reconnectTraceMetadata +
              backendTraceMetadata +
              providerTraceMetadata,
        )
      }

      private fun decorateController(
        controller: ManagedProcessController,
        decorationMetadata: Map<String, String>,
      ): ManagedProcessController {
        return object : ManagedProcessController {
          override fun snapshot(): ManagedProcessSnapshot = controller.snapshot().withDecorationMetadata()

          override fun await(timeoutMs: Long): ManagedProcessSnapshot =
            controller.await(timeoutMs).withDecorationMetadata()

          override fun terminate(): ManagedProcessSnapshot =
            controller.terminate().withDecorationMetadata()

          private fun ManagedProcessSnapshot.withDecorationMetadata(): ManagedProcessSnapshot =
            copy(metadata = metadata + decorationMetadata)
        }
      }
    }
  }
}

internal interface E2BEnvdCommandTransport {
  fun stream(
    request: E2BEnvdCommandTransportRequest,
    onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
  ): E2BResponse

  fun unary(
    request: E2BEnvdCommandTransportRequest,
  ): E2BResponse = error("Unary E2B envd transport is not implemented.")
}

internal data class E2BEnvdCommandTransportRequest(
  val method: String,
  val url: String,
  val headers: Map<String, String>,
  val bodyBytes: ByteArray,
  val connectTimeoutMs: Int,
  val readTimeoutMs: Int,
)

internal class UrlConnectionE2BEnvdCommandTransport : E2BEnvdCommandTransport {
  override fun stream(
    request: E2BEnvdCommandTransportRequest,
    onEnvelope: (flags: Int, payload: ByteArray) -> Unit,
  ): E2BResponse {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      connectTimeout = request.connectTimeoutMs
      readTimeout = request.readTimeoutMs
      instanceFollowRedirects = true
      doInput = true
      doOutput = request.bodyBytes.isNotEmpty()
      useCaches = false
      request.headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      if (request.bodyBytes.isNotEmpty()) {
        connection.outputStream.use { output ->
          output.write(request.bodyBytes)
        }
      }
      val statusCode = connection.responseCode
      if (statusCode in 200..299) {
        connection.inputStream.use { input ->
          while (true) {
            val header = ByteArray(5)
            val firstByte = input.read()
            if (firstByte < 0) {
              break
            }
            header[0] = firstByte.toByte()
            readExact(input, header, offset = 1, length = 4)
            val flags = header[0].toInt() and 0xFF
            val payloadLength = (
              ((header[1].toInt() and 0xFF) shl 24) or
                ((header[2].toInt() and 0xFF) shl 16) or
                ((header[3].toInt() and 0xFF) shl 8) or
                (header[4].toInt() and 0xFF)
              )
            val payload = ByteArray(payloadLength)
            readExact(input, payload, offset = 0, length = payloadLength)
            onEnvelope(flags, payload)
          }
        }
        E2BResponse(statusCode = statusCode)
      } else {
        E2BResponse(
          statusCode = statusCode,
          body = readFully(connection.errorStream),
        )
      }
    } finally {
      connection.disconnect()
    }
  }

  override fun unary(
    request: E2BEnvdCommandTransportRequest,
  ): E2BResponse {
    val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
      requestMethod = request.method
      connectTimeout = request.connectTimeoutMs
      readTimeout = request.readTimeoutMs
      instanceFollowRedirects = true
      doInput = true
      doOutput = request.bodyBytes.isNotEmpty()
      useCaches = false
      request.headers.forEach { (name, value) ->
        if (name.isNotBlank() && value.isNotBlank()) {
          setRequestProperty(name, value)
        }
      }
    }
    return try {
      if (request.bodyBytes.isNotEmpty()) {
        connection.outputStream.use { output ->
          output.write(request.bodyBytes)
        }
      }
      val statusCode = connection.responseCode
      val body = if (statusCode in 200..299) {
        readFully(connection.inputStream)
      } else {
        readFully(connection.errorStream)
      }
      E2BResponse(statusCode = statusCode, body = body)
    } finally {
      connection.disconnect()
    }
  }

  private fun readExact(
    input: InputStream,
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ) {
    var remaining = length
    var currentOffset = offset
    while (remaining > 0) {
      val read = input.read(buffer, currentOffset, remaining)
      if (read < 0) {
        throw EOFException("Unexpected end of E2B envd stream.")
      }
      remaining -= read
      currentOffset += read
    }
  }

  private fun readFully(input: InputStream?): String {
    if (input == null) {
      return ""
    }
    input.use { stream ->
      val output = ByteArrayOutputStream()
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) {
          break
        }
        output.write(buffer, 0, read)
      }
      return output.toString(StandardCharsets.UTF_8.name())
    }
  }
}

internal data class E2BEnvdStartRequest(
  val process: E2BEnvdProcessConfig,
  val tag: String? = null,
  val stdin: Boolean? = null,
)

internal data class E2BEnvdProcessConfig(
  val cmd: String,
  val args: List<String> = emptyList(),
  val envs: Map<String, String> = emptyMap(),
  val cwd: String? = null,
)

internal data class E2BEnvdProcessSelector(
  val pid: Int? = null,
  val tag: String? = null,
)

internal data class E2BEnvdConnectRequest(
  val process: E2BEnvdProcessSelector,
)

internal data class E2BEnvdSendSignalRequest(
  val process: E2BEnvdProcessSelector,
  val signal: Int,
)

internal sealed interface E2BEnvdProcessEvent {
  data class Start(
    val pid: Int,
  ) : E2BEnvdProcessEvent

  data class Data(
    val stdout: ByteArray? = null,
    val stderr: ByteArray? = null,
    val pty: ByteArray? = null,
  ) : E2BEnvdProcessEvent

  data class End(
    val exitCode: Int? = null,
    val exited: Boolean,
    val status: String,
    val error: String? = null,
  ) : E2BEnvdProcessEvent

  data object KeepAlive : E2BEnvdProcessEvent
}

internal object E2BEnvdProcessProtoCodec {
  fun encodeStartRequest(request: E2BEnvdStartRequest): ByteArray = ProtoWriter().apply {
    writeMessage(1, encodeProcessConfig(request.process))
    request.tag?.takeIf(String::isNotBlank)?.let { tag -> writeString(3, tag) }
    request.stdin?.let { stdin -> writeBool(4, stdin) }
  }.toByteArray()

  fun decodeStartRequest(payload: ByteArray): E2BEnvdStartRequest {
    var process: E2BEnvdProcessConfig? = null
    var tag: String? = null
    var stdin: Boolean? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> process = decodeProcessConfig(reader.readLengthDelimited())
        3 -> tag = reader.readString()
        4 -> stdin = reader.readBool()
        else -> reader.skipField(wireType)
      }
    }
    return E2BEnvdStartRequest(
      process = requireNotNull(process) { "E2B envd StartRequest is missing process." },
      tag = tag,
      stdin = stdin,
    )
  }

  fun encodeStartResponse(event: E2BEnvdProcessEvent): ByteArray = ProtoWriter().apply {
    writeMessage(1, encodeProcessEvent(event))
  }.toByteArray()

  fun decodeStartResponse(payload: ByteArray): E2BEnvdProcessEvent {
    var event: E2BEnvdProcessEvent? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> event = decodeProcessEvent(reader.readLengthDelimited())
        else -> reader.skipField(wireType)
      }
    }
    return requireNotNull(event) { "E2B envd StartResponse did not include a process event." }
  }

  fun encodeConnectRequest(request: E2BEnvdConnectRequest): ByteArray = ProtoWriter().apply {
    writeMessage(1, encodeProcessSelector(request.process))
  }.toByteArray()

  fun decodeConnectRequest(payload: ByteArray): E2BEnvdConnectRequest {
    var process: E2BEnvdProcessSelector? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> process = decodeProcessSelector(reader.readLengthDelimited())
        else -> reader.skipField(wireType)
      }
    }
    return E2BEnvdConnectRequest(
      process = requireNotNull(process) { "E2B envd ConnectRequest is missing process selector." },
    )
  }

  fun encodeConnectResponse(event: E2BEnvdProcessEvent): ByteArray = encodeStartResponse(event)

  fun decodeConnectResponse(payload: ByteArray): E2BEnvdProcessEvent = decodeStartResponse(payload)

  fun encodeConnectEnvelope(
    flags: Int,
    payload: ByteArray,
  ): ByteArray = ByteArrayOutputStream().apply {
    write(flags and 0xFF)
    write((payload.size ushr 24) and 0xFF)
    write((payload.size ushr 16) and 0xFF)
    write((payload.size ushr 8) and 0xFF)
    write(payload.size and 0xFF)
    write(payload)
  }.toByteArray()

  fun encodeSendSignalRequest(request: E2BEnvdSendSignalRequest): ByteArray = ProtoWriter().apply {
    writeMessage(1, encodeProcessSelector(request.process))
    writeUInt32(2, request.signal)
  }.toByteArray()

  fun decodeSendSignalRequest(payload: ByteArray): E2BEnvdSendSignalRequest {
    var process: E2BEnvdProcessSelector? = null
    var signal: Int? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> process = decodeProcessSelector(reader.readLengthDelimited())
        2 -> signal = reader.readUInt32()
        else -> reader.skipField(wireType)
      }
    }
    return E2BEnvdSendSignalRequest(
      process = requireNotNull(process) { "E2B envd SendSignalRequest is missing process selector." },
      signal = requireNotNull(signal) { "E2B envd SendSignalRequest is missing signal." },
    )
  }

  private fun encodeProcessConfig(config: E2BEnvdProcessConfig): ByteArray = ProtoWriter().apply {
    writeString(1, config.cmd)
    config.args.forEach { arg -> writeString(2, arg) }
    config.envs.forEach { (key, value) ->
      writeMessage(
        3,
        ProtoWriter().apply {
          writeString(1, key)
          writeString(2, value)
        }.toByteArray(),
      )
    }
    config.cwd?.takeIf(String::isNotBlank)?.let { cwd ->
      writeString(4, cwd)
    }
  }.toByteArray()

  private fun decodeProcessConfig(payload: ByteArray): E2BEnvdProcessConfig {
    var cmd: String? = null
    val args = mutableListOf<String>()
    val envs = linkedMapOf<String, String>()
    var cwd: String? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> cmd = reader.readString()
        2 -> args += reader.readString()
        3 -> {
          var key: String? = null
          var value: String? = null
          ProtoReader(reader.readLengthDelimited()).readFields { entryFieldNumber, entryWireType, entryReader ->
            when (entryFieldNumber) {
              1 -> key = entryReader.readString()
              2 -> value = entryReader.readString()
              else -> entryReader.skipField(entryWireType)
            }
          }
          if (key != null && value != null) {
            envs[key.orEmpty()] = value.orEmpty()
          }
        }
        4 -> cwd = reader.readString()
        else -> reader.skipField(wireType)
      }
    }
    return E2BEnvdProcessConfig(
      cmd = requireNotNull(cmd) { "E2B envd ProcessConfig is missing cmd." },
      args = args,
      envs = envs,
      cwd = cwd,
    )
  }

  private fun encodeProcessEvent(event: E2BEnvdProcessEvent): ByteArray = ProtoWriter().apply {
    when (event) {
      is E2BEnvdProcessEvent.Start -> writeMessage(
        1,
        ProtoWriter().apply { writeUInt32(1, event.pid) }.toByteArray(),
      )
      is E2BEnvdProcessEvent.Data -> writeMessage(
        2,
        ProtoWriter().apply {
          event.stdout?.let { writeBytes(1, it) }
          event.stderr?.let { writeBytes(2, it) }
          event.pty?.let { writeBytes(3, it) }
        }.toByteArray(),
      )
      is E2BEnvdProcessEvent.End -> writeMessage(
        3,
        ProtoWriter().apply {
          event.exitCode?.let { exitCode -> writeSInt32(1, exitCode) }
          writeBool(2, event.exited)
          writeString(3, event.status)
          event.error?.takeIf(String::isNotBlank)?.let { error -> writeString(4, error) }
        }.toByteArray(),
      )
      E2BEnvdProcessEvent.KeepAlive -> writeMessage(4, ByteArray(0))
    }
  }.toByteArray()

  private fun decodeProcessEvent(payload: ByteArray): E2BEnvdProcessEvent {
    var event: E2BEnvdProcessEvent? = null
    ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
      when (fieldNumber) {
        1 -> {
          var pid = 0
          ProtoReader(reader.readLengthDelimited()).readFields { nestedFieldNumber, nestedWireType, nestedReader ->
            when (nestedFieldNumber) {
              1 -> pid = nestedReader.readUInt32()
              else -> nestedReader.skipField(nestedWireType)
            }
          }
          event = E2BEnvdProcessEvent.Start(pid = pid)
        }
        2 -> {
          var stdout: ByteArray? = null
          var stderr: ByteArray? = null
          var pty: ByteArray? = null
          ProtoReader(reader.readLengthDelimited()).readFields { nestedFieldNumber, nestedWireType, nestedReader ->
            when (nestedFieldNumber) {
              1 -> stdout = nestedReader.readBytes()
              2 -> stderr = nestedReader.readBytes()
              3 -> pty = nestedReader.readBytes()
              else -> nestedReader.skipField(nestedWireType)
            }
          }
          event = E2BEnvdProcessEvent.Data(
            stdout = stdout,
            stderr = stderr,
            pty = pty,
          )
        }
        3 -> {
          var exitCode: Int? = null
          var exited = false
          var status = ""
          var error: String? = null
          ProtoReader(reader.readLengthDelimited()).readFields { nestedFieldNumber, nestedWireType, nestedReader ->
            when (nestedFieldNumber) {
              1 -> exitCode = nestedReader.readSInt32()
              2 -> exited = nestedReader.readBool()
              3 -> status = nestedReader.readString()
              4 -> error = nestedReader.readString()
              else -> nestedReader.skipField(nestedWireType)
            }
          }
          event = E2BEnvdProcessEvent.End(
            exitCode = exitCode,
            exited = exited,
            status = status,
            error = error,
          )
        }
        4 -> {
          reader.readLengthDelimited()
          event = E2BEnvdProcessEvent.KeepAlive
        }
        else -> reader.skipField(wireType)
      }
    }
    return requireNotNull(event) { "E2B envd ProcessEvent is empty." }
  }
}

private data class ResolvedNativeCommandSession(
  val snapshot: E2BSandboxSessionSnapshot,
  val source: String,
)

private data class ConnectEndStreamError(
  val code: String?,
  val message: String,
)

private data class NativeCommandAttemptContext(
  val sessionSource: String,
  val remoteWorkingDirectory: String,
) {
  fun fallbackMetadata(
    failureStage: String,
    httpStatusCode: Int? = null,
    transportFailureClass: String? = null,
    transportFailureMessage: String? = null,
  ): Map<String, String> = buildMap {
    put("sandboxCommandNativeAttempted", "true")
    put("sandboxCommandNativeAttemptApi", E2B_NATIVE_COMMAND_API)
    put("sandboxCommandNativeAttemptTransport", E2B_NATIVE_COMMAND_TRANSPORT)
    put("sandboxCommandNativeAttemptProtocol", E2B_NATIVE_COMMAND_PROTOCOL)
    put("sandboxCommandNativeAttemptSessionSource", sessionSource)
    put("sandboxCommandNativeAttemptRemoteWorkingDirectory", remoteWorkingDirectory)
    put("sandboxCommandNativeAttemptFailureStage", failureStage)
    httpStatusCode?.let { statusCode ->
      put("sandboxCommandNativeAttemptHttpStatusCode", statusCode.toString())
    }
    transportFailureClass?.takeIf(String::isNotBlank)?.let { failureClass ->
      put("sandboxCommandNativeAttemptTransportFailureClass", failureClass)
    }
    transportFailureMessage?.trim()?.takeIf(String::isNotBlank)?.let { failureMessage ->
      put("sandboxCommandNativeAttemptTransportFailureMessage", failureMessage)
    }
  }
}

private class E2BMinimalNativeForegroundCommandProcessRunner(
  private val workspaceRootProvider: () -> Path,
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
  private val fallbackRunnerProvider: () -> CommandProcessRunner,
  private val transport: E2BEnvdCommandTransport,
  private val capabilities: SandboxCommandBackendCapabilities,
  private val json: Json,
) : CommandProcessRunner {
  override fun run(
    commandLine: List<String>,
    workingDirectory: String?,
    config: CommandExecutionConfig,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
  ): CommandSpawnResult {
    if (commandLine.isEmpty()) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        spawnErrorMessage = "Command line must not be empty.",
        processStarted = false,
        metadata = preferredNativeSelection().metadata(),
      )
    }
    if (hooks.isCancellationRequested()) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        processStarted = false,
        cancelled = true,
        metadata = preferredNativeSelection().metadata() + capabilities.metadata(),
      )
    }

    val workspaceRoot = workspaceRootProvider().toAbsolutePath().normalize()
    val resolvedSession = resolveNativeCommandSession(
      workspaceRoot = workspaceRoot,
      sessionStore = sessionStore,
      activeSessionProvider = activeSessionProvider,
    )
      ?: return fallbackToWrapper(
        reasonCode = REASON_NATIVE_COMMAND_SESSION_UNAVAILABLE,
        detail = "No reusable E2B sandbox session with envd access is available for native foreground commands.",
        commandLine = commandLine,
        workingDirectory = workingDirectory,
        config = config,
        hooks = hooks,
      )
    val session = resolvedSession.snapshot
    val remoteWorkspaceRoot = session.remoteWorkspaceRoot?.trim()?.takeIf(String::isNotBlank)
      ?: return fallbackToWrapper(
        reasonCode = REASON_NATIVE_COMMAND_REMOTE_WORKSPACE_UNAVAILABLE,
        detail = "The reusable E2B sandbox session does not have a resolved remote workspace root yet.",
        commandLine = commandLine,
        workingDirectory = workingDirectory,
        config = config,
        hooks = hooks,
      )
    val envdAccessToken = session.envdAccessToken?.trim()?.takeIf(String::isNotBlank)
      ?: return fallbackToWrapper(
        reasonCode = REASON_NATIVE_COMMAND_ENVD_TOKEN_MISSING,
        detail = "The reusable E2B sandbox session does not have an envd access token.",
        commandLine = commandLine,
        workingDirectory = workingDirectory,
        config = config,
        hooks = hooks,
      )
    val settings = settingsProvider()
    val state = settings.state.sanitized()
    val remoteWorkingDirectory = resolveRemoteWorkingDirectory(
      localWorkspaceRoot = workspaceRoot,
      remoteWorkspaceRoot = remoteWorkspaceRoot,
      workingDirectory = workingDirectory,
    )
    val nativeAttemptContext = NativeCommandAttemptContext(
      sessionSource = resolvedSession.source,
      remoteWorkingDirectory = remoteWorkingDirectory,
    )
    val providerStableSelectorValue = "cmd-${UUID.randomUUID()}"
    val selectionMetadata = preferredNativeSelection().metadata()
    val collector = NativeCommandStreamCollector(
      json = json,
      outputByteLimit = config.outputByteLimit,
      baseMetadata = buildNativeMetadata(
        state = state,
        session = session,
        remoteWorkspaceRoot = remoteWorkspaceRoot,
        remoteWorkingDirectory = remoteWorkingDirectory,
        sessionSource = resolvedSession.source,
        pid = null,
        providerStableSelectorValue = providerStableSelectorValue,
        selectionMetadata = selectionMetadata,
      ),
      providerStableSelectorValue = providerStableSelectorValue,
    )

    val response = try {
      transport.stream(
        request = E2BEnvdCommandTransportRequest(
          method = "POST",
          url = nativeStartCommandUrl(session),
          headers = nativeHeaders(envdAccessToken = envdAccessToken, timeoutMs = config.timeoutMs),
          bodyBytes = E2BEnvdProcessProtoCodec.encodeConnectEnvelope(
            flags = 0,
            payload = E2BEnvdProcessProtoCodec.encodeStartRequest(
              E2BEnvdStartRequest(
                process = E2BEnvdProcessConfig(
                  cmd = commandLine.first(),
                  args = commandLine.drop(1),
                  cwd = remoteWorkingDirectory,
                ),
                tag = providerStableSelectorValue,
              ),
            ),
          ),
          connectTimeoutMs = timeoutInt(config.timeoutMs),
          readTimeoutMs = timeoutInt(config.timeoutMs),
        ),
        onEnvelope = collector::onEnvelope,
      )
    } catch (timeout: SocketTimeoutException) {
      return collector.timeoutResult()
    } catch (error: Exception) {
      return if (collector.processStarted) {
        collector.failureResult(
          message = error.message ?: error::class.java.simpleName,
          failureClass = error::class.java.simpleName,
        )
      } else {
        fallbackToWrapper(
          reasonCode = REASON_NATIVE_COMMAND_TRANSPORT_ERROR,
          detail = error.message ?: error::class.java.simpleName,
          commandLine = commandLine,
          workingDirectory = workingDirectory,
          config = config,
          hooks = hooks,
          nativeAttemptMetadata = nativeAttemptContext.fallbackMetadata(
            failureStage = "transport_exception",
            transportFailureClass = error::class.java.simpleName,
            transportFailureMessage = error.message,
          ),
        )
      }
    }

    if (response.statusCode !in 200..299) {
      return fallbackToWrapper(
        reasonCode = REASON_NATIVE_COMMAND_HTTP_ERROR,
        detail = "E2B native command API returned HTTP ${response.statusCode}: ${response.body.trim()}".trim(),
        commandLine = commandLine,
        workingDirectory = workingDirectory,
        config = config,
        hooks = hooks,
        nativeAttemptMetadata = nativeAttemptContext.fallbackMetadata(
          failureStage = "http_response_non_success",
          httpStatusCode = response.statusCode,
        ),
      )
    }
    collector.noteHttpStatusCode(response.statusCode)
    return collector.finalResult()
  }

  private fun fallbackToWrapper(
    reasonCode: String,
    detail: String,
    commandLine: List<String>,
    workingDirectory: String?,
    config: CommandExecutionConfig,
    hooks: com.opencray.core.orchestrator.RuntimeExecutionHooks,
    nativeAttemptMetadata: Map<String, String> = emptyMap(),
  ): CommandSpawnResult {
    val selectionMetadata = SandboxCommandBackendSelection(
      requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
      resolvedKind = "python_wrapper",
      providerNativeRequested = true,
      providerNativeAvailable = false,
      fallbackReasonCode = reasonCode,
      fallbackDetail = detail,
    ).metadata()
    val fallbackResult = fallbackRunnerProvider().run(
      commandLine = commandLine,
      workingDirectory = workingDirectory,
      config = config,
      hooks = hooks,
    )
    return fallbackResult.copy(
      metadata = fallbackResult.metadata + nativeAttemptMetadata + selectionMetadata,
    )
  }

  private fun preferredNativeSelection(): SandboxCommandBackendSelection =
    SandboxCommandBackendSelection(
      requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
      resolvedKind = capabilities.backendKind,
      providerNativeRequested = true,
      providerNativeAvailable = true,
    )

  private fun buildNativeMetadata(
    state: SandboxSettingsState,
    session: E2BSandboxSessionSnapshot,
    remoteWorkspaceRoot: String,
    remoteWorkingDirectory: String,
    sessionSource: String,
    pid: Int?,
    providerStableSelectorValue: String?,
    selectionMetadata: Map<String, String>,
  ): Map<String, String> = buildMap {
    put("runtimeBackend", E2B_NATIVE_COMMAND_RUNTIME_BACKEND)
    put("runtimeTransport", E2B_NATIVE_COMMAND_TRANSPORT)
    put("sandboxProvider", SandboxProviderId.E2B.wireValue)
    put("sandboxSessionMode", state.sessionMode)
    put("sandboxTimeoutAction", state.timeoutAction)
    put("sandboxAutoResume", state.autoResume.toString())
    put("sandboxProviderRequestTimeoutMs", state.requestTimeoutMs.toString())
    put("sandboxStartupTimeoutMs", state.startupTimeoutMs.toString())
    put("sandboxId", session.sandboxId)
    put("sandboxDomain", session.sandboxDomain)
    put("sandboxTemplateId", session.templateId)
    put("sandboxEnvdPort", E2B_ENVD_PORT.toString())
    put("sandboxCommandApi", E2B_NATIVE_COMMAND_API)
    put("sandboxCommandNativeProtocol", E2B_NATIVE_COMMAND_PROTOCOL)
    put("sandboxCommandSessionSource", sessionSource)
    put("remoteWorkspaceRoot", remoteWorkspaceRoot)
    put("remoteWorkingDirectory", remoteWorkingDirectory)
    pid?.let { processId -> put("sandboxCommandPid", processId.toString()) }
    providerStableSelectorValue?.takeIf(String::isNotBlank)?.let { stableSelectorValue ->
      putAll(
        normalizedProviderHandleMetadata(
          stableSelectorValue = stableSelectorValue,
          metadata = if (pid != null) {
            mapOf("sandboxCommandPid" to pid.toString())
          } else {
            emptyMap()
          },
        ),
      )
      putAll(
        normalizedCommandIdentityMetadata(
          stableSelectorValue = stableSelectorValue,
        ),
      )
    }
    putAll(capabilities.metadata())
    putAll(selectionMetadata)
  }

  companion object {
    internal const val REASON_NATIVE_COMMAND_SESSION_UNAVAILABLE: String =
      "native_command_session_unavailable"
    internal const val REASON_NATIVE_COMMAND_REMOTE_WORKSPACE_UNAVAILABLE: String =
      "native_command_remote_workspace_unavailable"
    internal const val REASON_NATIVE_COMMAND_ENVD_TOKEN_MISSING: String =
      "native_command_envd_token_missing"
    internal const val REASON_NATIVE_COMMAND_TRANSPORT_ERROR: String =
      "native_command_transport_error"
    internal const val REASON_NATIVE_COMMAND_HTTP_ERROR: String =
      "native_command_http_error"
  }
}

private class E2BMinimalProtocolManagedProcessControllerFactory(
  private val workspaceRootProvider: () -> Path,
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
  private val fallbackFactoryProvider: () -> ManagedProcessControllerFactory,
  private val transport: E2BEnvdCommandTransport,
  private val capabilities: SandboxCommandBackendCapabilities,
  private val json: Json,
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ReconnectableManagedProcessControllerFactory {
  override fun start(request: ManagedProcessStartRequest): ManagedProcessController {
    val workspaceRoot = workspaceRootProvider().toAbsolutePath().normalize()
    val resolvedSession = resolveNativeCommandSession(
      workspaceRoot = workspaceRoot,
      sessionStore = sessionStore,
      activeSessionProvider = activeSessionProvider,
    ) ?: return fallbackToWrapper(
      request = request,
      reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_SESSION_UNAVAILABLE,
      detail = "No reusable E2B sandbox session with envd access is available for native managed commands.",
    )
    val session = resolvedSession.snapshot
    val remoteWorkspaceRoot = session.remoteWorkspaceRoot?.trim()?.takeIf(String::isNotBlank)
      ?: return fallbackToWrapper(
        request = request,
        reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_REMOTE_WORKSPACE_UNAVAILABLE,
        detail = "The reusable E2B sandbox session does not have a resolved remote workspace root yet.",
      )
    val envdAccessToken = session.envdAccessToken?.trim()?.takeIf(String::isNotBlank)
      ?: return fallbackToWrapper(
        request = request,
        reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_ENVD_TOKEN_MISSING,
        detail = "The reusable E2B sandbox session does not have an envd access token.",
      )
    val state = settingsProvider().state.sanitized()
    val remoteWorkingDirectory = resolveRemoteWorkingDirectory(
      localWorkspaceRoot = workspaceRoot,
      remoteWorkspaceRoot = remoteWorkspaceRoot,
      workingDirectory = request.workingDirectory,
    )
    val nativeAttemptContext = NativeCommandAttemptContext(
      sessionSource = resolvedSession.source,
      remoteWorkingDirectory = remoteWorkingDirectory,
    )
    val selectionMetadata = SandboxCommandBackendSelection(
      requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
      resolvedKind = capabilities.backendKind,
      providerNativeRequested = true,
      providerNativeAvailable = true,
    ).metadata()
    return E2BMinimalNativeManagedCommandController(
      request = request,
      session = session,
      envdAccessToken = envdAccessToken,
      remoteWorkingDirectory = remoteWorkingDirectory,
      transport = transport,
      json = json,
      nativeAttemptContext = nativeAttemptContext,
      fallbackControllerProvider = { extraMetadata ->
        fallbackFactoryProvider().start(
          request.copy(metadata = request.metadata + extraMetadata),
        )
      },
      initialMetadata = buildMap {
        putAll(request.metadata)
        put("runtimeKind", "command_exec")
        put("terminationSupport", E2B_NATIVE_COMMAND_TERMINATION_SUPPORT)
        put("runtimeBackend", E2B_NATIVE_COMMAND_RUNTIME_BACKEND)
        put("runtimeTransport", E2B_NATIVE_COMMAND_TRANSPORT)
        put("sandboxProvider", SandboxProviderId.E2B.wireValue)
        put("sandboxSessionMode", state.sessionMode)
        put("sandboxTimeoutAction", state.timeoutAction)
        put("sandboxAutoResume", state.autoResume.toString())
        put("sandboxProviderRequestTimeoutMs", state.requestTimeoutMs.toString())
        put("sandboxStartupTimeoutMs", state.startupTimeoutMs.toString())
        put("sandboxId", session.sandboxId)
        put("sandboxDomain", session.sandboxDomain)
        put("sandboxTemplateId", session.templateId)
        put("sandboxEnvdPort", E2B_ENVD_PORT.toString())
        put("sandboxCommandApi", E2B_NATIVE_COMMAND_API)
        put("sandboxCommandNativeProtocol", E2B_NATIVE_COMMAND_PROTOCOL)
        put("sandboxCommandSessionSource", resolvedSession.source)
        put("sandboxCommandObservationMode", E2B_NATIVE_COMMAND_OBSERVATION_MODE)
        put("remoteWorkspaceRoot", remoteWorkspaceRoot)
        put("remoteWorkingDirectory", remoteWorkingDirectory)
        putAll(normalizedProviderHandleMetadata(stableSelectorValue = request.processId))
        putAll(normalizedCommandIdentityMetadata(stableSelectorValue = request.processId))
        putAll(normalizedObservationMetadata(processId = request.processId))
        putAll(normalizedProviderObservationMetadata())
        putAll(capabilities.metadata())
        putAll(selectionMetadata)
      },
      clock = clock,
    )
  }

  override fun reconnect(snapshot: ManagedProcessSnapshot): ManagedProcessController? {
    val normalizedSnapshot = snapshot.withNormalizedRemoteState()
    if (normalizedSnapshot.status != ManagedProcessStatus.RUNNING) {
      return null
    }
    val runtimeBackend = normalizedSnapshot.metadata["runtimeBackend"]?.trim()
    val resolvedBackend = normalizedSnapshot.metadata["sandboxCommandBackendResolvedKind"]?.trim()
    val remoteHandle = normalizedSnapshot.remoteHandle
    if (
      runtimeBackend != E2B_NATIVE_COMMAND_RUNTIME_BACKEND &&
      resolvedBackend != capabilities.backendKind &&
      remoteHandle?.provider?.trim() != SandboxProviderId.E2B.wireValue &&
      remoteHandle?.nativeProtocol?.trim() != E2B_NATIVE_COMMAND_PROTOCOL
    ) {
      return null
    }
    val workspaceRoot = workspaceRootProvider().toAbsolutePath().normalize()
    val resolvedSession = resolveNativeCommandSession(
      workspaceRoot = workspaceRoot,
      sessionStore = sessionStore,
      activeSessionProvider = activeSessionProvider,
    ) ?: return null
    val session = resolvedSession.snapshot
    val remoteWorkspaceRoot = remoteHandle?.remoteWorkspaceRoot
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: normalizedSnapshot.metadata["remoteWorkspaceRoot"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: session.remoteWorkspaceRoot?.trim()?.takeIf(String::isNotBlank)
      ?: return null
    val envdAccessToken = session.envdAccessToken?.trim()?.takeIf(String::isNotBlank)
      ?: return null
    val state = settingsProvider().state.sanitized()
    val selectionMetadata = SandboxCommandBackendSelection(
      requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
      resolvedKind = capabilities.backendKind,
      providerNativeRequested = true,
      providerNativeAvailable = true,
    ).metadata()
    val remoteWorkingDirectory = remoteHandle?.remoteWorkingDirectory
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: normalizedSnapshot.metadata["remoteWorkingDirectory"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: resolveRemoteWorkingDirectory(
        localWorkspaceRoot = workspaceRoot,
        remoteWorkspaceRoot = remoteWorkspaceRoot,
        workingDirectory = normalizedSnapshot.workingDirectory,
      )
    val request = ManagedProcessStartRequest(
      processId = normalizedSnapshot.processId,
      taskId = normalizedSnapshot.taskId,
      command = normalizedSnapshot.command,
      args = normalizedSnapshot.args,
      workingDirectory = normalizedSnapshot.workingDirectory,
      timeoutMs = normalizedSnapshot.timeoutMs,
      requestedAtEpochMs = normalizedSnapshot.startedAtEpochMs,
      metadata = normalizedSnapshot.metadata,
    )
    return E2BMinimalNativeManagedCommandController(
      request = request,
      session = session,
      envdAccessToken = envdAccessToken,
      remoteWorkingDirectory = remoteWorkingDirectory,
      transport = transport,
      json = json,
      nativeAttemptContext = NativeCommandAttemptContext(
        sessionSource = resolvedSession.source,
        remoteWorkingDirectory = remoteWorkingDirectory,
      ),
      fallbackControllerProvider = null,
      initialMetadata = buildMap {
        putAll(sanitizedReconnectSeedMetadata(normalizedSnapshot.metadata))
        put("runtimeKind", "command_exec")
        put("runtimeBackend", E2B_NATIVE_COMMAND_RUNTIME_BACKEND)
        put("runtimeTransport", E2B_NATIVE_COMMAND_TRANSPORT)
        put("sandboxProvider", SandboxProviderId.E2B.wireValue)
        put("sandboxSessionMode", state.sessionMode)
        put("sandboxTimeoutAction", state.timeoutAction)
        put("sandboxAutoResume", state.autoResume.toString())
        put("sandboxProviderRequestTimeoutMs", state.requestTimeoutMs.toString())
        put("sandboxStartupTimeoutMs", state.startupTimeoutMs.toString())
        put("sandboxId", session.sandboxId)
        put("sandboxDomain", session.sandboxDomain)
        put("sandboxTemplateId", session.templateId)
        put("sandboxEnvdPort", E2B_ENVD_PORT.toString())
        put("sandboxCommandApi", E2B_NATIVE_COMMAND_API)
        put("sandboxCommandNativeProtocol", E2B_NATIVE_COMMAND_PROTOCOL)
        put("sandboxCommandSessionSource", resolvedSession.source)
        put("sandboxCommandObservationMode", E2B_NATIVE_COMMAND_OBSERVATION_MODE)
        put("remoteWorkspaceRoot", remoteWorkspaceRoot)
        put("remoteWorkingDirectory", remoteWorkingDirectory)
        putAll(
          normalizedProviderHandleMetadata(
            stableSelectorValue = request.processId,
            snapshot = normalizedSnapshot,
            metadata = normalizedSnapshot.metadata,
          ),
        )
        putAll(
          normalizedCommandIdentityMetadata(
            stableSelectorValue = request.processId,
            snapshot = normalizedSnapshot,
            metadata = normalizedSnapshot.metadata,
          ),
        )
        putAll(
          normalizedObservationMetadata(
            processId = request.processId,
            snapshot = normalizedSnapshot,
            metadata = normalizedSnapshot.metadata,
          ),
        )
        putAll(
          normalizedProviderObservationMetadata(
            snapshot = normalizedSnapshot,
            metadata = normalizedSnapshot.metadata,
          ),
        )
        putAll(reconnectSelectorMetadata(normalizedSnapshot))
        put("sandboxCommandReconnectAttempted", "true")
        put("sandboxCommandReconnectApi", E2B_NATIVE_COMMAND_CONNECT_API)
        put("sandboxCommandReconnectSource", E2B_NATIVE_COMMAND_RECONNECT_SOURCE_DURABLE)
        put("sandboxCommandReconnectStatus", "connecting")
        put(
          "sandboxCommandReconnectRecoveryState",
          SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_CONNECTING,
        )
        put("sandboxCommandReconnectRetryable", "false")
        put("sandboxCommandReconnectAttemptCount", nextReconnectAttemptCount(normalizedSnapshot).toString())
        putAll(
          reconnectObservationMetadata(
            snapshot = normalizedSnapshot,
            capabilities = capabilities,
          ),
        )
        putAll(capabilities.metadata())
        putAll(selectionMetadata)
      },
      clock = clock,
      streamMode = NativeManagedCommandStreamMode.CONNECT,
      initialSnapshot = normalizedSnapshot,
    )
  }

  private fun fallbackToWrapper(
    request: ManagedProcessStartRequest,
    reasonCode: String,
    detail: String,
  ): ManagedProcessController {
    val selectionMetadata = SandboxCommandBackendSelection(
      requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
      resolvedKind = "python_wrapper",
      providerNativeRequested = true,
      providerNativeAvailable = false,
      fallbackReasonCode = reasonCode,
      fallbackDetail = detail,
    ).metadata()
    return fallbackFactoryProvider().start(
      request.copy(metadata = request.metadata + selectionMetadata),
    )
  }
}

private fun sanitizedReconnectSeedMetadata(
  metadata: Map<String, String>,
): Map<String, String> = metadata.filterKeys { key -> key !in RECONNECT_TRANSIENT_METADATA_KEYS }

private fun nextReconnectAttemptCount(snapshot: ManagedProcessSnapshot): Int =
  (snapshot.normalizedReconnectState()?.attemptCount
    ?: snapshot.metadata["sandboxCommandReconnectAttemptCount"]?.toIntOrNull()
    ?: 0) + 1

private data class EffectiveReconnectObservationSeed(
  val hostSource: String,
  val providerSource: String,
  val resumeMode: String,
  val hostObservationCursor: String,
  val hostObservationEventCount: Long,
  val stdoutBytes: Long,
  val stderrBytes: Long,
  val providerObservationCursor: String,
  val providerObservationEventCount: Long,
)

private fun effectiveReconnectObservationSeed(
  snapshot: ManagedProcessSnapshot,
): EffectiveReconnectObservationSeed {
  val normalizedSnapshot = snapshot.withNormalizedRemoteState()
  val deliveredObservationState = normalizedSnapshot.normalizedDeliveredObservationState()
  val reconnectSeed = normalizedSnapshot.reconnectState?.seed
  val observationMetadata = normalizedObservationMetadata(
    processId = normalizedSnapshot.processId,
    snapshot = normalizedSnapshot,
    metadata = normalizedSnapshot.metadata,
  )
  val providerObservationMetadata = normalizedProviderObservationMetadata(
    snapshot = normalizedSnapshot,
    metadata = normalizedSnapshot.metadata,
  )
  val hostSource = when {
    deliveredObservationState.hasHostBoundary() ->
      E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_DELIVERED_OBSERVATION

    reconnectSeed.hasHostBoundary() ->
      reconnectSeed?.source?.trim()?.takeIf(String::isNotBlank)
        ?: E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_SNAPSHOT_METADATA

    else -> E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_OBSERVATION_SNAPSHOT
  }
  val providerSource = when {
    deliveredObservationState.hasProviderBoundary() ->
      E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_DELIVERED_OBSERVATION

    reconnectSeed.hasProviderBoundary() ->
      reconnectSeed?.source?.trim()?.takeIf(String::isNotBlank)
        ?: E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_SNAPSHOT_METADATA

    else -> E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_OBSERVATION_SNAPSHOT
  }
  val hostObservationCursor = deliveredObservationState?.cursor?.trim()?.takeIf(String::isNotBlank)
    ?: reconnectSeed?.hostObservationCursor?.trim()?.takeIf(String::isNotBlank)
    ?: requireNotNull(observationMetadata["sandboxCommandObservationCursor"])
  val hostObservationEventCount = parseHostObservationCursor(deliveredObservationState?.cursor)
    ?: reconnectSeed?.hostObservationEventCount
    ?: parseHostObservationCursor(reconnectSeed?.hostObservationCursor)
    ?: requireNotNull(observationMetadata["sandboxCommandObservationEventCount"]).toLong()
  val stdoutBytes = deliveredObservationState?.stdoutBytes
    ?: reconnectSeed?.stdoutBytes
    ?: requireNotNull(observationMetadata["sandboxCommandObservationStdoutBytes"]).toLong()
  val stderrBytes = deliveredObservationState?.stderrBytes
    ?: reconnectSeed?.stderrBytes
    ?: requireNotNull(observationMetadata["sandboxCommandObservationStderrBytes"]).toLong()
  val providerObservationCursor =
    deliveredObservationState?.providerCursor?.trim()?.takeIf(String::isNotBlank)
      ?: reconnectSeed?.providerObservationCursor?.trim()?.takeIf(String::isNotBlank)
      ?: requireNotNull(providerObservationMetadata["sandboxCommandProviderObservationCursor"])
  val providerObservationEventCount = deliveredObservationState?.providerEventCount
    ?: parseProviderObservationCursor(deliveredObservationState?.providerCursor)
    ?: reconnectSeed?.providerObservationEventCount
    ?: parseProviderObservationCursor(reconnectSeed?.providerObservationCursor)
    ?: requireNotNull(providerObservationMetadata["sandboxCommandProviderObservationEventCount"]).toLong()
  val resumeMode = when (hostSource) {
    E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_DURABLE_DELIVERED_OBSERVATION ->
      E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_DELIVERED_SEED_THEN_LIVE_ATTACH

    E2B_NATIVE_COMMAND_RECONNECT_SEED_SOURCE_OBSERVATION_SNAPSHOT ->
      E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_OBSERVATION_SNAPSHOT_THEN_LIVE_ATTACH

    else -> E2B_NATIVE_COMMAND_RECONNECT_RESUME_MODE_SNAPSHOT_SEED_THEN_LIVE_ATTACH
  }
  return EffectiveReconnectObservationSeed(
    hostSource = hostSource,
    providerSource = providerSource,
    resumeMode = resumeMode,
    hostObservationCursor = hostObservationCursor,
    hostObservationEventCount = hostObservationEventCount,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    providerObservationCursor = providerObservationCursor,
    providerObservationEventCount = providerObservationEventCount,
  )
}

private fun reconnectObservationMetadata(
  snapshot: ManagedProcessSnapshot,
  capabilities: SandboxCommandBackendCapabilities,
): Map<String, String> = buildMap {
  val effectiveSeed = effectiveReconnectObservationSeed(snapshot)
  val providerResumeSupported = capabilities.supportsManagedProcessObservationCursorResume
  put("sandboxCommandReconnectResumeMode", effectiveSeed.resumeMode)
  put(
    "sandboxCommandReconnectBackfillSupported",
    capabilities.supportsManagedProcessObservationBackfill.toString(),
  )
  put(
    "sandboxCommandReconnectOutputGapRisk",
    (!providerResumeSupported || !capabilities.supportsManagedProcessObservationBackfill).toString(),
  )
  put("sandboxCommandReconnectSeedSource", effectiveSeed.hostSource)
  put("sandboxCommandReconnectProviderObservationSeedConsumed", "false")
  put(
    "sandboxCommandReconnectProviderObservationSeedState",
    E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_PENDING_LIVE_ATTACH,
  )
  put(
    "sandboxCommandReconnectSeedObservationCursor",
    effectiveSeed.hostObservationCursor,
  )
  put(
    "sandboxCommandReconnectSeedEventCount",
    effectiveSeed.hostObservationEventCount.toString(),
  )
  put(
    "sandboxCommandReconnectSeededStdoutBytes",
    effectiveSeed.stdoutBytes.toString(),
  )
  put(
    "sandboxCommandReconnectSeededStderrBytes",
    effectiveSeed.stderrBytes.toString(),
  )
  put(
    "sandboxCommandReconnectSeedProviderObservationCursor",
    effectiveSeed.providerObservationCursor,
  )
  put(
    "sandboxCommandReconnectSeedProviderObservationEventCount",
    effectiveSeed.providerObservationEventCount.toString(),
  )
  put("sandboxCommandReconnectProviderObservationSeedSource", effectiveSeed.providerSource)
  put("sandboxCommandReconnectProviderObservationResumeApplied", "false")
  put(
    "sandboxCommandReconnectProviderObservationResumeReason",
    if (providerResumeSupported) {
      E2B_NATIVE_COMMAND_PROVIDER_RESUME_REASON_LIVE_ATTACH_ONLY
    } else {
      E2B_NATIVE_COMMAND_PROVIDER_RESUME_REASON_PROTOCOL_CURSOR_UNSUPPORTED
    },
  )
}

private fun normalizedObservationMetadata(
  processId: String,
  snapshot: ManagedProcessSnapshot? = null,
  metadata: Map<String, String> = snapshot?.metadata.orEmpty(),
): Map<String, String> {
  val observationState = snapshot?.normalizedObservationState()
  val cursor = observationState?.hostCursor
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandObservationCursor")
  val eventCount = observationState?.hostEventCount
    ?: metadata["sandboxCommandObservationEventCount"]?.toLongOrNull()
    ?: parseHostObservationCursor(cursor)
    ?: parseHostObservationCursor(metadata["sandboxCommandObservationCursor"])
    ?: when {
      snapshot == null -> 0L
      snapshot.processStarted || snapshot.stdout.isNotEmpty() || snapshot.stderr.isNotEmpty() -> 1L
      else -> 0L
    }
  val stdoutBytes = observationState?.stdoutBytes
    ?: metadata["sandboxCommandObservationStdoutBytes"]?.toLongOrNull()
    ?: snapshot?.stdout?.utf8Length()
    ?: 0L
  val stderrBytes = observationState?.stderrBytes
    ?: metadata["sandboxCommandObservationStderrBytes"]?.toLongOrNull()
    ?: snapshot?.stderr?.utf8Length()
    ?: 0L
  return buildMap {
    put("sandboxCommandHandleIdKind", E2B_NATIVE_COMMAND_HANDLE_ID_KIND)
    put("sandboxCommandHandleId", processId)
    put("sandboxCommandHandleTag", processId)
    put("sandboxCommandObservationEventCount", eventCount.toString())
    put(
      "sandboxCommandObservationCursor",
      cursor?.takeIf { existingCursor -> parseHostObservationCursor(existingCursor) == eventCount }
        ?: hostObservationCursor(eventCount),
    )
    put("sandboxCommandObservationStdoutBytes", stdoutBytes.toString())
    put("sandboxCommandObservationStderrBytes", stderrBytes.toString())
  }
}

private fun normalizedProviderObservationMetadata(
  snapshot: ManagedProcessSnapshot? = null,
  metadata: Map<String, String> = snapshot?.metadata.orEmpty(),
): Map<String, String> {
  val observationState = snapshot?.normalizedObservationState()
  val hostCursor = observationState?.hostCursor
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandObservationCursor")
  val providerCursor = observationState?.providerCursor
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderObservationCursor")
  val hostEventCount = observationState?.hostEventCount
    ?: metadata["sandboxCommandObservationEventCount"]?.toLongOrNull()
    ?: parseHostObservationCursor(hostCursor)
    ?: parseHostObservationCursor(metadata["sandboxCommandObservationCursor"])
    ?: when {
      snapshot == null -> 0L
      snapshot.processStarted || snapshot.stdout.isNotEmpty() || snapshot.stderr.isNotEmpty() -> 1L
      else -> 0L
    }
  val providerEventCount = observationState?.providerEventCount
    ?: metadata["sandboxCommandProviderObservationEventCount"]?.toLongOrNull()
    ?: parseProviderObservationCursor(providerCursor)
    ?: parseProviderObservationCursor(metadata["sandboxCommandProviderObservationCursor"])
    ?: hostEventCount
  val providerMode = observationState?.providerMode
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderObservationMode")
    ?: E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_MODE
  val providerBackfillSupported = observationState?.providerBackfillSupported
    ?: metadata["sandboxCommandProviderObservationBackfillSupported"]?.toBooleanStrictOrNull()
    ?: false
  return buildMap {
    put("sandboxCommandProviderObservationMode", providerMode)
    put("sandboxCommandProviderObservationEventCount", providerEventCount.toString())
    put(
      "sandboxCommandProviderObservationCursor",
      providerCursor
        ?.takeIf { existingCursor -> parseProviderObservationCursor(existingCursor) == providerEventCount }
        ?: providerObservationCursor(providerEventCount),
    )
    put("sandboxCommandProviderObservationBackfillSupported", providerBackfillSupported.toString())
  }
}

private fun normalizedProviderHandleMetadata(
  stableSelectorValue: String,
  snapshot: ManagedProcessSnapshot? = null,
  metadata: Map<String, String> = emptyMap(),
): Map<String, String> {
  val remoteHandle = snapshot?.normalizedRemoteHandle()
  val stableSelector = remoteHandle?.stableSelectorValue
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderStableSelectorValue")
    ?: stableSelectorValue
  val stableSelectorKind = remoteHandle?.stableSelectorKind
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderStableSelectorKind")
    ?: E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG
  val explicitLiveSelectorKind = remoteHandle?.liveSelectorKind
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderLiveSelectorKind")
  val explicitLiveSelectorValue = remoteHandle?.liveSelectorValue
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderLiveSelectorValue")
  val livePid = metadata["sandboxCommandPid"]?.toIntOrNull()
    ?: explicitLiveSelectorValue
      ?.takeIf { explicitLiveSelectorKind == E2B_NATIVE_COMMAND_SELECTOR_KIND_PID }
      ?.toIntOrNull()
  val liveSelectorKind = when {
    livePid != null -> E2B_NATIVE_COMMAND_SELECTOR_KIND_PID
    explicitLiveSelectorKind != null -> explicitLiveSelectorKind
    else -> stableSelectorKind
  }
  val liveSelectorValue = when {
    livePid != null -> livePid.toString()
    explicitLiveSelectorValue != null -> explicitLiveSelectorValue
    else -> stableSelector
  }
  val providerHandleKind = remoteHandle?.providerHandleKind
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderHandleKind")
    ?: E2B_NATIVE_COMMAND_PROVIDER_HANDLE_KIND
  return buildMap {
    put("sandboxCommandProviderHandleKind", providerHandleKind)
    put("sandboxCommandProviderStableSelectorKind", stableSelectorKind)
    put("sandboxCommandProviderStableSelectorValue", stableSelector)
    put("sandboxCommandProviderLiveSelectorKind", liveSelectorKind)
    put("sandboxCommandProviderLiveSelectorValue", liveSelectorValue)
  }
}

private fun normalizedCommandIdentityMetadata(
  stableSelectorValue: String,
  snapshot: ManagedProcessSnapshot? = null,
  metadata: Map<String, String> = emptyMap(),
): Map<String, String> {
  val remoteHandle = snapshot?.normalizedRemoteHandle()
  val commandId = remoteHandle?.commandId
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandId")
    ?: remoteHandle?.stableSelectorValue
      ?.trim()
      ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderStableSelectorValue")
    ?: stableSelectorValue
  val commandIdKind = remoteHandle?.commandIdKind
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandIdKind")
    ?: remoteHandle?.stableSelectorKind
      ?.trim()
      ?.takeIf(String::isNotBlank)
    ?: metadata.trimmedValue("sandboxCommandProviderStableSelectorKind")
    ?: E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG
  return mapOf(
    "sandboxCommandIdKind" to commandIdKind,
    "sandboxCommandId" to commandId,
  )
}

private fun reconnectSelectorMetadata(
  snapshot: ManagedProcessSnapshot,
): Map<String, String> {
  val normalizedSnapshot = snapshot.withNormalizedRemoteState()
  val remoteHandle = normalizedSnapshot.remoteHandle
  val reconnectState = normalizedSnapshot.reconnectState
  val selectorKind = reconnectState?.selectorKind
    ?.trim()
    ?.takeIf(String::isNotBlank)
  val selectorValue = reconnectState?.selectorValue
    ?.trim()
    ?.takeIf(String::isNotBlank)
  val pid = normalizedSnapshot.metadata["sandboxCommandPid"]?.toIntOrNull()
    ?: remoteHandle?.liveSelectorValue
      ?.takeIf {
        remoteHandle.liveSelectorKind == E2B_NATIVE_COMMAND_SELECTOR_KIND_PID
      }
      ?.toIntOrNull()
    ?: selectorValue
      ?.takeIf { selectorKind == E2B_NATIVE_COMMAND_SELECTOR_KIND_PID }
      ?.toIntOrNull()
  return if (pid != null) {
    val selectorSource = reconnectState?.selectorSource
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: E2B_NATIVE_COMMAND_RECONNECT_SELECTOR_SOURCE_SNAPSHOT_PID
    mapOf(
      "sandboxCommandReconnectSelectorKind" to E2B_NATIVE_COMMAND_SELECTOR_KIND_PID,
      "sandboxCommandReconnectSelectorValue" to pid.toString(),
      "sandboxCommandReconnectSelectorSource" to selectorSource,
    )
  } else {
    val stableSelectorValue = remoteHandle?.stableSelectorValue
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: normalizedSnapshot.metadata.trimmedValue("sandboxCommandProviderStableSelectorValue")
      ?: normalizedSnapshot.processId
    mapOf(
      "sandboxCommandReconnectSelectorKind" to E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG,
      "sandboxCommandReconnectSelectorValue" to stableSelectorValue,
      "sandboxCommandReconnectSelectorSource" to E2B_NATIVE_COMMAND_RECONNECT_SELECTOR_SOURCE_STABLE_TAG,
    )
  }
}

private fun Map<String, String>.trimmedValue(key: String): String? =
  get(key)?.trim()?.takeIf(String::isNotBlank)

private fun hostObservationCursor(eventCount: Long): String =
  "$E2B_NATIVE_COMMAND_OBSERVATION_CURSOR_PREFIX${eventCount.coerceAtLeast(0L)}"

private fun parseHostObservationCursor(cursor: String?): Long? {
  val trimmed = cursor?.trim().orEmpty()
  if (!trimmed.startsWith(E2B_NATIVE_COMMAND_OBSERVATION_CURSOR_PREFIX)) {
    return null
  }
  return trimmed.removePrefix(E2B_NATIVE_COMMAND_OBSERVATION_CURSOR_PREFIX).toLongOrNull()
}

private fun providerObservationCursor(eventCount: Long): String =
  "$E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_CURSOR_PREFIX${eventCount.coerceAtLeast(0L)}"

private fun parseProviderObservationCursor(cursor: String?): Long? {
  val trimmed = cursor?.trim().orEmpty()
  if (!trimmed.startsWith(E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_CURSOR_PREFIX)) {
    return null
  }
  return trimmed.removePrefix(E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_CURSOR_PREFIX).toLongOrNull()
}

private fun ManagedProcessReconnectSeed?.hasHostBoundary(): Boolean =
  this?.hostObservationCursor?.trim()?.takeIf(String::isNotBlank) != null ||
    this?.hostObservationEventCount != null ||
    this?.stdoutBytes != null ||
    this?.stderrBytes != null

private fun ManagedProcessReconnectSeed?.hasProviderBoundary(): Boolean =
  this?.providerObservationCursor?.trim()?.takeIf(String::isNotBlank) != null ||
    this?.providerObservationEventCount != null

private fun ManagedProcessDeliveredObservationState?.hasHostBoundary(): Boolean =
  this?.cursor?.trim()?.takeIf(String::isNotBlank) != null ||
    this?.stdoutBytes != null ||
    this?.stderrBytes != null

private fun ManagedProcessDeliveredObservationState?.hasProviderBoundary(): Boolean =
  this?.providerCursor?.trim()?.takeIf(String::isNotBlank) != null ||
    this?.providerEventCount != null

private fun String.utf8Length(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

private val RECONNECT_TRANSIENT_METADATA_KEYS: Set<String> = setOf(
  "sandboxCommandReconnectStatus",
  "sandboxCommandReconnectRecoveryState",
  "sandboxCommandReconnectRetryable",
  "sandboxCommandReconnectRetryAfterEpochMs",
  "sandboxCommandReconnectFailureStage",
  "sandboxCommandReconnectFailureClass",
  "sandboxCommandReconnectFailureMessage",
  "sandboxCommandReconnectLastFailureAtEpochMs",
  "sandboxCommandReconnectHttpStatusCode",
  "sandboxCommandReconnectLastAttachedAtEpochMs",
  "sandboxCommandReconnectLastEventAtEpochMs",
  "sandboxCommandReconnectLastEventKind",
  "sandboxCommandReconnectSeedSource",
  "sandboxCommandReconnectProviderObservationSeedConsumed",
  "sandboxCommandReconnectProviderObservationSeedState",
  "sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs",
)

private const val SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_CONNECTING = "connecting"
private const val SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_ATTACHED_LIVE = "attached_live"
private const val SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_RETRY_SCHEDULED = "retry_scheduled"
private const val SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_COMPLETED = "completed"
private const val SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_FAILED_TERMINAL = "failed_terminal"

private data class NativeTerminationDispatchResult(
  val accepted: Boolean,
  val metadata: Map<String, String>,
)

private enum class NativeManagedCommandStreamMode {
  START,
  CONNECT,
}

private class E2BMinimalNativeManagedCommandController(
  private val request: ManagedProcessStartRequest,
  private val session: E2BSandboxSessionSnapshot,
  private val envdAccessToken: String,
  private val remoteWorkingDirectory: String,
  private val transport: E2BEnvdCommandTransport,
  private val json: Json,
  private val nativeAttemptContext: NativeCommandAttemptContext,
  private val fallbackControllerProvider: ((Map<String, String>) -> ManagedProcessController)?,
  initialMetadata: Map<String, String>,
  private val clock: () -> Long,
  private val streamMode: NativeManagedCommandStreamMode = NativeManagedCommandStreamMode.START,
  initialSnapshot: ManagedProcessSnapshot? = null,
  outputByteLimit: Int = E2B_NATIVE_COMMAND_OUTPUT_BYTE_LIMIT,
) : ManagedProcessController {
  private val lock = Any()
  private val completion = CountDownLatch(1)
  private val stdoutCollector = BoundedUtf8Collector()
  private val stderrCollector = BoundedUtf8Collector()
  private val totalBytes = TotalByteBudget(outputByteLimit)
  private val runtimeMetadata = LinkedHashMap<String, String>(initialMetadata)
  private var observationEventCount: Long =
    runtimeMetadata["sandboxCommandObservationEventCount"]?.toLongOrNull()
      ?: parseHostObservationCursor(runtimeMetadata["sandboxCommandObservationCursor"])
      ?: 0L
  private var observationStdoutBytes: Long =
    runtimeMetadata["sandboxCommandObservationStdoutBytes"]?.toLongOrNull() ?: 0L
  private var observationStderrBytes: Long =
    runtimeMetadata["sandboxCommandObservationStderrBytes"]?.toLongOrNull() ?: 0L
  private var providerObservationEventCount: Long =
    runtimeMetadata["sandboxCommandProviderObservationEventCount"]?.toLongOrNull()
      ?: parseProviderObservationCursor(runtimeMetadata["sandboxCommandProviderObservationCursor"])
      ?: observationEventCount
  private var reconnectLiveAttachObserved: Boolean = false

  private var status: ManagedProcessStatus = initialSnapshot?.status ?: ManagedProcessStatus.RUNNING
  private var processStarted: Boolean = initialSnapshot?.processStarted ?: false
  private var exitCode: Int? = initialSnapshot?.exitCode
  private var errorCode: String? = initialSnapshot?.errorCode
  private var errorMessage: String? = initialSnapshot?.errorMessage
  private var startedAtEpochMs: Long = initialSnapshot?.startedAtEpochMs ?: clock()
  private var updatedAtEpochMs: Long = initialSnapshot?.updatedAtEpochMs ?: startedAtEpochMs
  private var finishedAtEpochMs: Long? = initialSnapshot?.finishedAtEpochMs
  private var timedOut: Boolean = initialSnapshot?.timedOut ?: false
  private var cancelled: Boolean = initialSnapshot?.cancelled ?: false
  private var outputLimitExceeded: Boolean = initialSnapshot?.outputLimitExceeded ?: false
  private var endEvent: E2BEnvdProcessEvent.End? = null
  private var endStreamError: ConnectEndStreamError? = null
  private var terminationRequested: Boolean = initialSnapshot?.metadata?.get("terminationRequested") == "true"
  private var terminationSignalAttempted: Boolean = false
  private var terminationRequestAccepted: Boolean? =
    initialSnapshot?.metadata?.get("terminationRequestAccepted")?.toBooleanStrictOrNull()
  private var delegateController: ManagedProcessController? = null

  init {
    ensureObservationMetadataLocked()
    initialSnapshot?.stdout
      ?.takeIf(String::isNotEmpty)
      ?.let { text -> stdoutCollector.appendString(text, totalBytes) }
    initialSnapshot?.stderr
      ?.takeIf(String::isNotEmpty)
      ?.let { text -> stderrCollector.appendString(text, totalBytes) }
    outputLimitExceeded = outputLimitExceeded || totalBytes.limitExceeded
  }

  init {
    Thread(
      { executeNativeCommand() },
      when (streamMode) {
        NativeManagedCommandStreamMode.START -> "managed-e2b-native-command-${request.processId}"
        NativeManagedCommandStreamMode.CONNECT -> "managed-e2b-native-command-reconnect-${request.processId}"
      },
    ).apply {
      isDaemon = true
      start()
    }
  }

  override fun snapshot(): ManagedProcessSnapshot {
    val delegate = synchronized(lock) { delegateController }
    return delegate?.snapshot() ?: synchronized(lock) { snapshotLocked() }
  }

  override fun await(timeoutMs: Long): ManagedProcessSnapshot {
    val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
    val initialDelegate = synchronized(lock) { delegateController }
    if (initialDelegate != null) {
      return initialDelegate.await(timeoutMs)
    }
    completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    val delegate = synchronized(lock) { delegateController }
    return if (delegate != null) {
      val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
      delegate.await(remaining)
    } else {
      snapshot()
    }
  }

  override fun terminate(): ManagedProcessSnapshot {
    val delegate = synchronized(lock) { delegateController }
    if (delegate != null) {
      return delegate.terminate()
    }
    val shouldDispatch = synchronized(lock) {
      if (status.isTerminal) {
        false
      } else {
        terminationRequested = true
        updatedAtEpochMs = maxOf(updatedAtEpochMs, clock())
        shouldDispatchTerminationLocked().also { shouldDispatch ->
          if (shouldDispatch) {
            terminationSignalAttempted = true
          }
        }
      }
    }
    if (shouldDispatch) {
      synchronized(lock) {
        runtimeMetadata.putAll(SandboxExecutionTraceMetadata.providerTerminateMetadata(runtimeMetadata))
      }
      dispatchTerminationSignal()
    }
    return snapshot()
  }

  private fun executeNativeCommand() {
    try {
      val streamRequest = when (streamMode) {
        NativeManagedCommandStreamMode.START -> E2BEnvdCommandTransportRequest(
          method = "POST",
          url = nativeStartCommandUrl(session),
          headers = nativeHeaders(envdAccessToken = envdAccessToken, timeoutMs = request.timeoutMs),
          bodyBytes = E2BEnvdProcessProtoCodec.encodeConnectEnvelope(
            flags = 0,
            payload = E2BEnvdProcessProtoCodec.encodeStartRequest(
              E2BEnvdStartRequest(
                process = E2BEnvdProcessConfig(
                  cmd = request.command,
                  args = request.args,
                  cwd = remoteWorkingDirectory,
                ),
                tag = request.processId,
              ),
            ),
          ),
          connectTimeoutMs = timeoutInt(request.timeoutMs),
          readTimeoutMs = timeoutInt(request.timeoutMs),
        )

        NativeManagedCommandStreamMode.CONNECT -> E2BEnvdCommandTransportRequest(
          method = "POST",
          url = nativeConnectCommandUrl(session),
          headers = nativeHeaders(envdAccessToken = envdAccessToken, timeoutMs = request.timeoutMs),
          bodyBytes = E2BEnvdProcessProtoCodec.encodeConnectEnvelope(
            flags = 0,
            payload = E2BEnvdProcessProtoCodec.encodeConnectRequest(
              E2BEnvdConnectRequest(
                process = nativeReconnectSelector(),
              ),
            ),
          ),
          connectTimeoutMs = timeoutInt(request.timeoutMs),
          readTimeoutMs = timeoutInt(request.timeoutMs),
        )
      }
      val response = transport.stream(
        request = streamRequest,
        onEnvelope = ::handleEnvelope,
      )
      synchronized(lock) {
        when (streamMode) {
          NativeManagedCommandStreamMode.START -> {
            runtimeMetadata["sandboxCommandNativeHttpStatusCode"] = response.statusCode.toString()
          }

          NativeManagedCommandStreamMode.CONNECT -> {
            runtimeMetadata["sandboxCommandReconnectHttpStatusCode"] = response.statusCode.toString()
            runtimeMetadata["sandboxCommandReconnectStatus"] = "stream_connected"
          }
        }
      }
      if (response.statusCode !in 200..299) {
        if (streamMode == NativeManagedCommandStreamMode.START) {
          fallbackToWrapper(
            reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_HTTP_ERROR,
            detail = "E2B native command API returned HTTP ${response.statusCode}: ${response.body.trim()}".trim(),
            nativeAttemptMetadata = nativeAttemptContext.fallbackMetadata(
              failureStage = "http_response_non_success",
              httpStatusCode = response.statusCode,
            ),
          )
        } else {
          val failedAt = clock()
          synchronized(lock) {
            outputLimitExceeded = totalBytes.limitExceeded
            if (isRetryableReconnectHttpStatus(response.statusCode)) {
              markRetryableReconnectFailureLocked(
                failedAt = failedAt,
                failureStage = "http_response_non_success",
                failureMessage = "Managed sandbox command reconnect returned HTTP ${response.statusCode}.",
              )
            } else {
              status = ManagedProcessStatus.FAILED
              errorCode = "PROCESS_RECONNECT_FAILED"
              errorMessage = "Managed sandbox command reconnect returned HTTP ${response.statusCode}."
              updatedAtEpochMs = failedAt
              finishedAtEpochMs = failedAt
              runtimeMetadata["sandboxCommandReconnectStatus"] = "failed"
              runtimeMetadata["sandboxCommandReconnectRetryable"] = "false"
              runtimeMetadata["sandboxCommandReconnectFailureStage"] = "http_response_non_success"
              markReconnectProviderObservationSeedUnconsumedLocked(
                E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_FAILED_TERMINAL_BEFORE_LIVE_ATTACH,
              )
              updateReconnectRecoveryStateLocked()
            }
          }
        }
        return
      }
      finalizeSuccessfulStream()
    } catch (timeout: SocketTimeoutException) {
      val failedAt = clock()
      synchronized(lock) {
        outputLimitExceeded = totalBytes.limitExceeded
        if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
          markRetryableReconnectFailureLocked(
            failedAt = failedAt,
            failureStage = "transport_timeout",
            failureMessage = "Managed sandbox command reconnect exceeded timeout.",
          )
        } else {
          status = ManagedProcessStatus.TIMEOUT
          timedOut = true
          errorCode = "TIMEOUT"
          errorMessage = "Managed sandbox command exceeded timeout."
          updatedAtEpochMs = failedAt
          finishedAtEpochMs = failedAt
          runtimeMetadata["sandboxCommandNativeFailureStage"] = "transport_timeout"
        }
      }
    } catch (error: Exception) {
      if (!processStarted && streamMode == NativeManagedCommandStreamMode.START) {
        fallbackToWrapper(
          reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_TRANSPORT_ERROR,
          detail = error.message ?: error::class.java.simpleName,
          nativeAttemptMetadata = nativeAttemptContext.fallbackMetadata(
            failureStage = "transport_exception",
            transportFailureClass = error::class.java.simpleName,
            transportFailureMessage = error.message,
          ),
        )
      } else {
        val failedAt = clock()
        synchronized(lock) {
          outputLimitExceeded = totalBytes.limitExceeded
          if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
            markRetryableReconnectFailureLocked(
              failedAt = failedAt,
              failureStage = if (reconnectLiveAttachObserved) {
                E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_TRANSPORT_EXCEPTION_AFTER_LIVE_ATTACH
              } else {
                E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_TRANSPORT_EXCEPTION_BEFORE_LIVE_ATTACH
              },
              failureClass = error::class.java.simpleName,
              failureMessage = error.message ?: error::class.java.simpleName,
            )
          } else {
            status = ManagedProcessStatus.FAILED
            errorCode = "EXEC_ERROR"
            errorMessage = error.message ?: error::class.java.simpleName
            updatedAtEpochMs = failedAt
            finishedAtEpochMs = failedAt
            runtimeMetadata["sandboxCommandNativeFailureStage"] = "transport_exception_after_start"
            runtimeMetadata["sandboxCommandNativeTransportFailureClass"] = error::class.java.simpleName
            runtimeMetadata["sandboxCommandNativeTransportFailureMessage"] =
              error.message ?: error::class.java.simpleName
          }
        }
      }
    } finally {
      completion.countDown()
    }
  }

  private fun handleEnvelope(flags: Int, payload: ByteArray) {
    if ((flags and CONNECT_ENVELOPE_FLAG_COMPRESSED) != 0) {
      error("Compressed E2B envd Connect envelopes are not supported in the minimal client.")
    }
    var shouldDispatchTermination = false
    synchronized(lock) {
      val observedAt = clock()
      if ((flags and CONNECT_ENVELOPE_FLAG_END_STREAM) != 0) {
        endStreamError = parseConnectEndStreamError(payload, json)
        noteReconnectEventLocked(eventKind = "end_stream", observedAtEpochMs = observedAt)
        endStreamError?.code?.let { code ->
          runtimeMetadata["sandboxCommandNativeEndStreamErrorCode"] = code
        }
        endStreamError?.message?.trim()?.takeIf(String::isNotBlank)?.let { message ->
          runtimeMetadata["sandboxCommandNativeEndStreamErrorMessage"] = message
        }
        updatedAtEpochMs = maxOf(updatedAtEpochMs, observedAt)
        return
      }
      when (
        val event = when (streamMode) {
          NativeManagedCommandStreamMode.START -> E2BEnvdProcessProtoCodec.decodeStartResponse(payload)
          NativeManagedCommandStreamMode.CONNECT -> E2BEnvdProcessProtoCodec.decodeConnectResponse(payload)
        }
      ) {
        is E2BEnvdProcessEvent.Start -> {
          processStarted = true
          runtimeMetadata["sandboxCommandPid"] = event.pid.toString()
          noteObservationEventLocked()
          noteReconnectEventLocked(eventKind = "start", observedAtEpochMs = observedAt)
          if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
            runtimeMetadata["sandboxCommandReconnectStatus"] = "attached"
            runtimeMetadata["sandboxCommandReconnectRetryable"] = "false"
            runtimeMetadata.remove("sandboxCommandReconnectRetryAfterEpochMs")
            runtimeMetadata.remove("sandboxCommandReconnectFailureStage")
            runtimeMetadata.remove("sandboxCommandReconnectFailureClass")
            runtimeMetadata.remove("sandboxCommandReconnectFailureMessage")
            runtimeMetadata.remove("sandboxCommandReconnectLastFailureAtEpochMs")
            updateReconnectRecoveryStateLocked()
          }
          updatedAtEpochMs = maxOf(updatedAtEpochMs, observedAt)
          if (shouldDispatchTerminationLocked()) {
            terminationSignalAttempted = true
            shouldDispatchTermination = true
          }
        }
        is E2BEnvdProcessEvent.Data -> {
          processStarted = true
          noteObservationEventLocked(
            stdoutBytes = (event.stdout?.size ?: 0) + (event.pty?.size ?: 0),
            stderrBytes = event.stderr?.size ?: 0,
          )
          noteReconnectEventLocked(eventKind = "data", observedAtEpochMs = observedAt)
          event.stdout?.let { stdout -> stdoutCollector.append(stdout, totalBytes) }
          event.stderr?.let { stderr -> stderrCollector.append(stderr, totalBytes) }
          event.pty?.let { pty -> stdoutCollector.append(pty, totalBytes) }
          outputLimitExceeded = totalBytes.limitExceeded
          updatedAtEpochMs = maxOf(updatedAtEpochMs, observedAt)
        }
        is E2BEnvdProcessEvent.End -> {
          processStarted = true
          endEvent = event
          noteObservationEventLocked(
            stderrBytes = event.error?.utf8Length()?.toInt() ?: 0,
          )
          noteReconnectEventLocked(eventKind = "end", observedAtEpochMs = observedAt)
          runtimeMetadata["sandboxCommandNativeProcessStatus"] = event.status
          runtimeMetadata["sandboxCommandNativeProcessExited"] = event.exited.toString()
          updatedAtEpochMs = maxOf(updatedAtEpochMs, observedAt)
        }
        E2BEnvdProcessEvent.KeepAlive -> {
          noteObservationEventLocked()
          noteReconnectEventLocked(eventKind = "keepalive", observedAtEpochMs = observedAt)
          updatedAtEpochMs = maxOf(updatedAtEpochMs, observedAt)
        }
      }
    }
    if (shouldDispatchTermination) {
      dispatchTerminationSignal()
    }
  }

  private fun finalizeSuccessfulStream() {
    val finishedAt = clock()
    synchronized(lock) {
      outputLimitExceeded = totalBytes.limitExceeded
      val endStreamMessage = endStreamError?.message?.trim().orEmpty()
      val completed = endEvent
      when {
        terminationRequested && terminationRequestAccepted == true -> {
          status = ManagedProcessStatus.CANCELLED
          cancelled = true
          errorCode = "CANCELLED"
          errorMessage = "Managed sandbox command terminated."
          exitCode = completed?.exitCode
        }
        completed == null && endStreamError != null && !reconnectLiveAttachObserved -> {
          val detail = endStreamMessage.ifBlank { "E2B envd Connect stream returned an end-stream error." }
          if (streamMode == NativeManagedCommandStreamMode.START) {
            synchronized(lock) {
              runtimeMetadata["sandboxCommandNativeFailureStage"] = "connect_end_stream_before_start"
            }
            fallbackToWrapper(
              reasonCode = E2BMinimalNativeForegroundCommandProcessRunner.REASON_NATIVE_COMMAND_TRANSPORT_ERROR,
              detail = detail,
              nativeAttemptMetadata = nativeAttemptContext.fallbackMetadata(
                failureStage = "connect_end_stream_before_start",
              ),
            )
          } else {
            status = ManagedProcessStatus.FAILED
            errorCode = "PROCESS_RECONNECT_FAILED"
            errorMessage = detail
            runtimeMetadata["sandboxCommandReconnectStatus"] = "failed"
            runtimeMetadata["sandboxCommandReconnectFailureStage"] =
              E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_CONNECT_END_STREAM_BEFORE_LIVE_ATTACH
            markReconnectProviderObservationSeedUnconsumedLocked(
              E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_FAILED_TERMINAL_BEFORE_LIVE_ATTACH,
            )
            updateReconnectRecoveryStateLocked()
          }
          return
        }
        completed == null -> {
          status = if (processStarted) ManagedProcessStatus.FAILED else ManagedProcessStatus.SPAWN_ERROR
          errorCode = if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
            "PROCESS_RECONNECT_FAILED"
          } else if (processStarted) {
            "EXEC_ERROR"
          } else {
            "SPAWN_ERROR"
          }
          errorMessage = if (endStreamMessage.isNotBlank()) {
            endStreamMessage
          } else {
            "E2B native command stream ended without a process end event."
          }
          if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
            if (reconnectLiveAttachObserved) {
              markRetryableReconnectFailureLocked(
                failedAt = finishedAt,
                failureStage = E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_MISSING_PROCESS_END_AFTER_LIVE_ATTACH,
                failureMessage = errorMessage,
              )
              return
            } else {
              runtimeMetadata["sandboxCommandReconnectStatus"] = "failed"
              runtimeMetadata["sandboxCommandReconnectRetryable"] = "false"
              runtimeMetadata["sandboxCommandReconnectFailureStage"] =
                E2B_NATIVE_COMMAND_RECONNECT_FAILURE_STAGE_MISSING_PROCESS_END_BEFORE_LIVE_ATTACH
              markReconnectProviderObservationSeedUnconsumedLocked(
                E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_FAILED_TERMINAL_BEFORE_LIVE_ATTACH,
              )
              updateReconnectRecoveryStateLocked()
            }
          } else {
            runtimeMetadata["sandboxCommandNativeFailureStage"] = if (processStarted) {
              "missing_process_end_event"
            } else {
              "connect_end_stream_before_start"
            }
          }
        }
        outputLimitExceeded -> {
          exitCode = completed.exitCode
          status = ManagedProcessStatus.FAILED
          errorCode = "OUTPUT_LIMIT_EXCEEDED"
          errorMessage = "Managed sandbox command output exceeded configured byte limit."
        }
        completed.exitCode == 0 -> {
          exitCode = completed.exitCode
          status = ManagedProcessStatus.SUCCESS
        }
        else -> {
          exitCode = completed.exitCode
          status = ManagedProcessStatus.FAILED
          errorCode = "EXEC_ERROR"
          errorMessage = "Process exited with code ${completed.exitCode ?: -1}."
        }
      }
      if (streamMode == NativeManagedCommandStreamMode.CONNECT) {
        runtimeMetadata["sandboxCommandReconnectStatus"] = if (status.isTerminal) {
          "completed"
        } else {
          "attached"
        }
        runtimeMetadata["sandboxCommandReconnectRetryable"] = "false"
        runtimeMetadata.remove("sandboxCommandReconnectRetryAfterEpochMs")
        updateReconnectRecoveryStateLocked()
      }
      updatedAtEpochMs = finishedAt
      finishedAtEpochMs = finishedAt
    }
  }

  private fun markRetryableReconnectFailureLocked(
    failedAt: Long,
    failureStage: String,
    failureClass: String? = null,
    failureMessage: String? = null,
  ) {
    status = ManagedProcessStatus.RUNNING
    timedOut = false
    errorCode = null
    errorMessage = null
    updatedAtEpochMs = failedAt
    finishedAtEpochMs = null
    runtimeMetadata["sandboxCommandReconnectStatus"] = "retryable_failure"
    runtimeMetadata["sandboxCommandReconnectRetryable"] = "true"
    runtimeMetadata["sandboxCommandReconnectRetryAfterEpochMs"] =
      (failedAt + E2B_NATIVE_COMMAND_RECONNECT_RETRY_BACKOFF_MS).toString()
    runtimeMetadata["sandboxCommandReconnectLastFailureAtEpochMs"] = failedAt.toString()
    runtimeMetadata["sandboxCommandReconnectFailureStage"] = failureStage
    failureClass?.let { runtimeMetadata["sandboxCommandReconnectFailureClass"] = it }
    failureMessage?.let { runtimeMetadata["sandboxCommandReconnectFailureMessage"] = it }
    markReconnectProviderObservationSeedUnconsumedLocked(
      E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_RETRY_SCHEDULED_BEFORE_LIVE_ATTACH,
    )
    updateReconnectRecoveryStateLocked()
  }

  private fun noteReconnectEventLocked(
    eventKind: String,
    observedAtEpochMs: Long,
  ) {
    if (streamMode != NativeManagedCommandStreamMode.CONNECT) {
      return
    }
    if (eventKind != "end_stream") {
      markReconnectProviderObservationSeedConsumedLocked(observedAtEpochMs)
    }
    if (
      eventKind != "end_stream" &&
      runtimeMetadata["sandboxCommandReconnectStatus"] == "connecting"
    ) {
      runtimeMetadata["sandboxCommandReconnectStatus"] = "attached"
      runtimeMetadata["sandboxCommandReconnectRetryable"] = "false"
      runtimeMetadata.remove("sandboxCommandReconnectRetryAfterEpochMs")
      runtimeMetadata.remove("sandboxCommandReconnectFailureStage")
      runtimeMetadata.remove("sandboxCommandReconnectFailureClass")
      runtimeMetadata.remove("sandboxCommandReconnectFailureMessage")
      runtimeMetadata.remove("sandboxCommandReconnectLastFailureAtEpochMs")
    }
    runtimeMetadata["sandboxCommandReconnectLastEventAtEpochMs"] = observedAtEpochMs.toString()
    runtimeMetadata["sandboxCommandReconnectLastEventKind"] = eventKind
    if (
      eventKind != "end_stream" &&
      runtimeMetadata["sandboxCommandReconnectLastAttachedAtEpochMs"] == null
    ) {
      runtimeMetadata["sandboxCommandReconnectLastAttachedAtEpochMs"] = observedAtEpochMs.toString()
    }
    updateReconnectRecoveryStateLocked()
  }

  private fun markReconnectProviderObservationSeedConsumedLocked(observedAtEpochMs: Long) {
    if (reconnectLiveAttachObserved) {
      return
    }
    reconnectLiveAttachObserved = true
    runtimeMetadata["sandboxCommandReconnectProviderObservationSeedConsumed"] = "true"
    runtimeMetadata["sandboxCommandReconnectProviderObservationSeedState"] =
      E2B_NATIVE_COMMAND_RECONNECT_PROVIDER_SEED_STATE_CONSUMED_LIVE_ATTACH
    runtimeMetadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"] =
      observedAtEpochMs.toString()
  }

  private fun markReconnectProviderObservationSeedUnconsumedLocked(state: String) {
    if (streamMode != NativeManagedCommandStreamMode.CONNECT || reconnectLiveAttachObserved) {
      return
    }
    runtimeMetadata["sandboxCommandReconnectProviderObservationSeedConsumed"] = "false"
    runtimeMetadata["sandboxCommandReconnectProviderObservationSeedState"] = state
    runtimeMetadata.remove("sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs")
  }

  private fun noteObservationEventLocked(
    stdoutBytes: Int = 0,
    stderrBytes: Int = 0,
  ) {
    observationEventCount += 1L
    observationStdoutBytes += stdoutBytes.coerceAtLeast(0).toLong()
    observationStderrBytes += stderrBytes.coerceAtLeast(0).toLong()
    providerObservationEventCount += 1L
    ensureObservationMetadataLocked()
  }

  private fun ensureObservationMetadataLocked() {
    runtimeMetadata.putAll(
      normalizedCommandIdentityMetadata(
        stableSelectorValue = request.processId,
        metadata = runtimeMetadata,
      ),
    )
    runtimeMetadata.putAll(
      normalizedProviderHandleMetadata(
        stableSelectorValue = request.processId,
        metadata = runtimeMetadata,
      ),
    )
    runtimeMetadata.putAll(
      normalizedProviderObservationMetadata(
        metadata = runtimeMetadata + mapOf(
          "sandboxCommandProviderObservationEventCount" to providerObservationEventCount.toString(),
        ),
      ),
    )
    runtimeMetadata["sandboxCommandHandleIdKind"] = E2B_NATIVE_COMMAND_HANDLE_ID_KIND
    runtimeMetadata["sandboxCommandHandleId"] = request.processId
    runtimeMetadata["sandboxCommandHandleTag"] = request.processId
    runtimeMetadata["sandboxCommandObservationEventCount"] = observationEventCount.toString()
    runtimeMetadata["sandboxCommandObservationCursor"] = hostObservationCursor(observationEventCount)
    runtimeMetadata["sandboxCommandObservationStdoutBytes"] = observationStdoutBytes.toString()
    runtimeMetadata["sandboxCommandObservationStderrBytes"] = observationStderrBytes.toString()
  }

  private fun updateReconnectRecoveryStateLocked() {
    if (streamMode != NativeManagedCommandStreamMode.CONNECT) {
      return
    }
    runtimeMetadata["sandboxCommandReconnectRecoveryState"] = when {
      runtimeMetadata["sandboxCommandReconnectRetryable"] == "true" ->
        SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_RETRY_SCHEDULED

      runtimeMetadata["sandboxCommandReconnectStatus"] == "failed" ->
        SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_FAILED_TERMINAL

      runtimeMetadata["sandboxCommandReconnectStatus"] == "completed" ||
        (
          status.isTerminal &&
            runtimeMetadata["sandboxCommandReconnectLastAttachedAtEpochMs"] != null
          ) -> SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_COMPLETED

      runtimeMetadata["sandboxCommandReconnectLastAttachedAtEpochMs"] != null ||
        runtimeMetadata["sandboxCommandReconnectStatus"] == "attached" ->
        SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_ATTACHED_LIVE

      else -> SANDBOX_COMMAND_RECONNECT_RECOVERY_STATE_CONNECTING
    }
  }

  private fun nativeReconnectSelector(): E2BEnvdProcessSelector {
    val pid = runtimeMetadata["sandboxCommandReconnectSelectorValue"]
      ?.trim()
      ?.takeIf {
        runtimeMetadata["sandboxCommandReconnectSelectorKind"] == E2B_NATIVE_COMMAND_SELECTOR_KIND_PID
      }
      ?.toIntOrNull()
      ?: runtimeMetadata["sandboxCommandPid"]?.trim()?.toIntOrNull()
      ?: runtimeMetadata["sandboxCommandProviderLiveSelectorValue"]
        ?.trim()
        ?.takeIf {
          runtimeMetadata["sandboxCommandProviderLiveSelectorKind"] == E2B_NATIVE_COMMAND_SELECTOR_KIND_PID
        }
        ?.toIntOrNull()
    return if (pid != null) {
      E2BEnvdProcessSelector(pid = pid)
    } else {
      E2BEnvdProcessSelector(
        tag = runtimeMetadata["sandboxCommandProviderStableSelectorValue"]
          ?.trim()
          ?.takeIf(String::isNotBlank)
          ?: runtimeMetadata["sandboxCommandReconnectSelectorValue"]
            ?.trim()
            ?.takeIf {
              runtimeMetadata["sandboxCommandReconnectSelectorKind"] == E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG &&
                it.isNotBlank()
            }
          ?: request.processId,
      )
    }
  }

  private fun dispatchTerminationSignal() {
    val targetTag = runtimeMetadata["sandboxCommandProviderStableSelectorValue"]
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: request.processId
    val result = runCatching {
      val timeoutMs = minOf(request.timeoutMs, 10_000L)
      val response = transport.unary(
        request = E2BEnvdCommandTransportRequest(
          method = "POST",
          url = nativeSendSignalUrl(session),
          headers = nativeHeaders(envdAccessToken = envdAccessToken, timeoutMs = timeoutMs),
          bodyBytes = E2BEnvdProcessProtoCodec.encodeSendSignalRequest(
            E2BEnvdSendSignalRequest(
              process = E2BEnvdProcessSelector(tag = targetTag),
              signal = 9,
            ),
          ),
          connectTimeoutMs = timeoutInt(timeoutMs),
          readTimeoutMs = timeoutInt(timeoutMs),
        ),
      )
      if (response.statusCode in 200..299) {
        NativeTerminationDispatchResult(
          accepted = true,
          metadata = mapOf(
            "sandboxCommandTerminateApi" to E2B_NATIVE_COMMAND_SIGNAL_API,
            "sandboxCommandTerminateHttpStatusCode" to response.statusCode.toString(),
            "sandboxCommandTerminateRequestedSignal" to "9",
            "sandboxCommandTerminateSelectorKind" to E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG,
            "sandboxCommandTerminateSelectorValue" to targetTag,
            "sandboxCommandTerminateTargetTag" to targetTag,
          ),
        )
      } else {
        NativeTerminationDispatchResult(
          accepted = false,
          metadata = mapOf(
            "sandboxCommandTerminateApi" to E2B_NATIVE_COMMAND_SIGNAL_API,
            "sandboxCommandTerminateHttpStatusCode" to response.statusCode.toString(),
            "sandboxCommandTerminateRequestedSignal" to "9",
            "sandboxCommandTerminateSelectorKind" to E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG,
            "sandboxCommandTerminateSelectorValue" to targetTag,
            "sandboxCommandTerminateTargetTag" to targetTag,
            "sandboxCommandTerminateFailureStage" to "http_response_non_success",
          ) + response.body.trim().takeIf(String::isNotBlank)?.let { body ->
            mapOf("sandboxCommandTerminateFailureMessage" to body)
          }.orEmpty(),
        )
      }
    }.getOrElse { error ->
      NativeTerminationDispatchResult(
        accepted = false,
        metadata = mapOf(
          "sandboxCommandTerminateApi" to E2B_NATIVE_COMMAND_SIGNAL_API,
          "sandboxCommandTerminateRequestedSignal" to "9",
          "sandboxCommandTerminateSelectorKind" to E2B_NATIVE_COMMAND_SELECTOR_KIND_TAG,
          "sandboxCommandTerminateSelectorValue" to targetTag,
          "sandboxCommandTerminateTargetTag" to targetTag,
          "sandboxCommandTerminateFailureStage" to "transport_exception",
          "sandboxCommandTerminateFailureClass" to error::class.java.simpleName,
          "sandboxCommandTerminateFailureMessage" to (error.message ?: error::class.java.simpleName),
        ),
      )
    }
    synchronized(lock) {
      terminationRequestAccepted = result.accepted
      runtimeMetadata.putAll(result.metadata)
      if (
        result.accepted &&
        terminationRequested &&
        status.isTerminal &&
        status != ManagedProcessStatus.CANCELLED &&
        status != ManagedProcessStatus.SUCCESS
      ) {
        status = ManagedProcessStatus.CANCELLED
        cancelled = true
        errorCode = "CANCELLED"
        errorMessage = "Managed sandbox command terminated."
      }
      updatedAtEpochMs = maxOf(updatedAtEpochMs, clock())
    }
  }

  private fun shouldDispatchTerminationLocked(): Boolean =
    terminationRequested && processStarted && !status.isTerminal && !terminationSignalAttempted

  private fun fallbackToWrapper(
    reasonCode: String,
    detail: String,
    nativeAttemptMetadata: Map<String, String>,
  ) {
    val fallbackController = fallbackControllerProvider?.invoke(
      nativeAttemptMetadata + SandboxCommandBackendSelection(
        requestedKind = E2BSandboxCommandExecutionBackendFactory.REQUESTED_KIND_PROVIDER_NATIVE_PREFERRED,
        resolvedKind = "python_wrapper",
        providerNativeRequested = true,
        providerNativeAvailable = false,
        fallbackReasonCode = reasonCode,
        fallbackDetail = detail,
      ).metadata(),
    )
    if (fallbackController != null) {
      synchronized(lock) {
        delegateController = fallbackController
      }
      return
    }
    val failedAt = clock()
    synchronized(lock) {
      outputLimitExceeded = totalBytes.limitExceeded
      status = ManagedProcessStatus.SPAWN_ERROR
      errorCode = "SPAWN_ERROR"
      errorMessage = detail
      updatedAtEpochMs = failedAt
      finishedAtEpochMs = failedAt
      runtimeMetadata.putAll(nativeAttemptMetadata)
      runtimeMetadata["sandboxCommandBackendFallbackReasonCode"] = reasonCode
      runtimeMetadata["sandboxCommandBackendFallbackDetail"] = detail
    }
  }

  private fun snapshotLocked(): ManagedProcessSnapshot = ManagedProcessSnapshot(
    processId = request.processId,
    taskId = request.taskId,
    command = request.command,
    args = request.args,
    workingDirectory = request.workingDirectory,
    status = status,
    processStarted = processStarted,
    timeoutMs = request.timeoutMs,
    stdout = stdoutCollector.text(),
    stderr = stderrWithEndEventError(stderrCollector.text(), endEvent),
    exitCode = exitCode,
    errorCode = errorCode,
    errorMessage = errorMessage,
    startedAtEpochMs = startedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    timedOut = timedOut,
    cancelled = cancelled,
    outputLimitExceeded = outputLimitExceeded,
    remoteHandle = remoteHandleLocked(),
    observationState = observationStateLocked(),
    reconnectState = reconnectStateLocked(),
    metadata = buildMap {
      putAll(runtimeMetadata)
      if (terminationRequested) {
        put("terminationRequested", "true")
      }
      terminationRequestAccepted?.let { accepted ->
        put("terminationRequestAccepted", accepted.toString())
      }
    },
  ).withNormalizedRemoteState()

  private fun remoteHandleLocked(): ManagedProcessRemoteHandle = ManagedProcessRemoteHandle(
    provider = runtimeMetadata["sandboxProvider"]?.trim()?.takeIf(String::isNotBlank)
      ?: SandboxProviderId.E2B.wireValue,
    sandboxId = runtimeMetadata["sandboxId"]?.trim()?.takeIf(String::isNotBlank)
      ?: session.sandboxId,
    sandboxDomain = runtimeMetadata["sandboxDomain"]?.trim()?.takeIf(String::isNotBlank)
      ?: session.sandboxDomain,
    sessionId = runtimeMetadata["sandboxSessionId"]?.trim()?.takeIf(String::isNotBlank),
    commandIdKind = runtimeMetadata["sandboxCommandIdKind"]?.trim()?.takeIf(String::isNotBlank),
    commandId = runtimeMetadata["sandboxCommandId"]?.trim()?.takeIf(String::isNotBlank),
    providerHandleKind = runtimeMetadata["sandboxCommandProviderHandleKind"]?.trim()
      ?.takeIf(String::isNotBlank),
    stableSelectorKind = runtimeMetadata["sandboxCommandProviderStableSelectorKind"]?.trim()
      ?.takeIf(String::isNotBlank),
    stableSelectorValue = runtimeMetadata["sandboxCommandProviderStableSelectorValue"]?.trim()
      ?.takeIf(String::isNotBlank),
    liveSelectorKind = runtimeMetadata["sandboxCommandProviderLiveSelectorKind"]?.trim()
      ?.takeIf(String::isNotBlank),
    liveSelectorValue = runtimeMetadata["sandboxCommandProviderLiveSelectorValue"]?.trim()
      ?.takeIf(String::isNotBlank),
    remoteWorkspaceRoot = runtimeMetadata["remoteWorkspaceRoot"]?.trim()
      ?.takeIf(String::isNotBlank)
      ?: session.remoteWorkspaceRoot?.trim()?.takeIf(String::isNotBlank),
    remoteWorkingDirectory = runtimeMetadata["remoteWorkingDirectory"]?.trim()
      ?.takeIf(String::isNotBlank)
      ?: remoteWorkingDirectory,
    nativeProtocol = runtimeMetadata["sandboxCommandNativeProtocol"]?.trim()
      ?.takeIf(String::isNotBlank)
      ?: E2B_NATIVE_COMMAND_PROTOCOL,
  )

  private fun observationStateLocked(): ManagedProcessObservationState = ManagedProcessObservationState(
    mode = runtimeMetadata["sandboxCommandObservationMode"]?.trim()?.takeIf(String::isNotBlank)
      ?: E2B_NATIVE_COMMAND_OBSERVATION_MODE,
    hostEventCount = observationEventCount,
    hostCursor = hostObservationCursor(observationEventCount),
    stdoutBytes = observationStdoutBytes,
    stderrBytes = observationStderrBytes,
    providerMode = runtimeMetadata["sandboxCommandProviderObservationMode"]?.trim()
      ?.takeIf(String::isNotBlank)
      ?: E2B_NATIVE_COMMAND_PROVIDER_OBSERVATION_MODE,
    providerEventCount = providerObservationEventCount,
    providerCursor = providerObservationCursor(providerObservationEventCount),
    providerBackfillSupported =
      runtimeMetadata["sandboxCommandProviderObservationBackfillSupported"]?.toBooleanStrictOrNull()
        ?: false,
    liveObservationSupported =
      runtimeMetadata["sandboxCommandSupportsManagedProcessLiveObservation"]?.toBooleanStrictOrNull(),
    cursorResumeSupported =
      runtimeMetadata["sandboxCommandSupportsManagedProcessObservationCursorResume"]?.toBooleanStrictOrNull(),
    backfillSupported =
      runtimeMetadata["sandboxCommandSupportsManagedProcessObservationBackfill"]?.toBooleanStrictOrNull(),
  )

  private fun reconnectStateLocked(): ManagedProcessReconnectState? {
    val seed = reconnectSeedLocked()
    val hasReconnectMetadata =
      streamMode == NativeManagedCommandStreamMode.CONNECT ||
        runtimeMetadata.keys.any { key -> key.startsWith("sandboxCommandReconnect") }
    if (!hasReconnectMetadata && seed == null) {
      return null
    }
    return ManagedProcessReconnectState(
      api = runtimeMetadata["sandboxCommandReconnectApi"]?.trim()?.takeIf(String::isNotBlank),
      source = runtimeMetadata["sandboxCommandReconnectSource"]?.trim()?.takeIf(String::isNotBlank),
      status = runtimeMetadata["sandboxCommandReconnectStatus"]?.trim()?.takeIf(String::isNotBlank),
      recoveryState = runtimeMetadata["sandboxCommandReconnectRecoveryState"]?.trim()
        ?.takeIf(String::isNotBlank),
      resumeMode = runtimeMetadata["sandboxCommandReconnectResumeMode"]?.trim()
        ?.takeIf(String::isNotBlank),
      backfillSupported =
        runtimeMetadata["sandboxCommandReconnectBackfillSupported"]?.toBooleanStrictOrNull(),
      outputGapRisk = runtimeMetadata["sandboxCommandReconnectOutputGapRisk"]?.toBooleanStrictOrNull(),
      retryable = runtimeMetadata["sandboxCommandReconnectRetryable"]?.toBooleanStrictOrNull(),
      retryAfterEpochMs = runtimeMetadata["sandboxCommandReconnectRetryAfterEpochMs"]?.toLongOrNull(),
      attemptCount = runtimeMetadata["sandboxCommandReconnectAttemptCount"]?.toIntOrNull(),
      httpStatusCode = runtimeMetadata["sandboxCommandReconnectHttpStatusCode"]?.toIntOrNull(),
      selectorKind = runtimeMetadata["sandboxCommandReconnectSelectorKind"]?.trim()
        ?.takeIf(String::isNotBlank),
      selectorValue = runtimeMetadata["sandboxCommandReconnectSelectorValue"]?.trim()
        ?.takeIf(String::isNotBlank),
      selectorSource = runtimeMetadata["sandboxCommandReconnectSelectorSource"]?.trim()
        ?.takeIf(String::isNotBlank),
      lastAttachedAtEpochMs =
        runtimeMetadata["sandboxCommandReconnectLastAttachedAtEpochMs"]?.toLongOrNull(),
      lastEventAtEpochMs = runtimeMetadata["sandboxCommandReconnectLastEventAtEpochMs"]?.toLongOrNull(),
      lastEventKind = runtimeMetadata["sandboxCommandReconnectLastEventKind"]?.trim()
        ?.takeIf(String::isNotBlank),
      lastFailureAtEpochMs =
        runtimeMetadata["sandboxCommandReconnectLastFailureAtEpochMs"]?.toLongOrNull(),
      failureStage = runtimeMetadata["sandboxCommandReconnectFailureStage"]?.trim()
        ?.takeIf(String::isNotBlank),
      failureClass = runtimeMetadata["sandboxCommandReconnectFailureClass"]?.trim()
        ?.takeIf(String::isNotBlank),
      failureMessage = runtimeMetadata["sandboxCommandReconnectFailureMessage"]?.trim()
        ?.takeIf(String::isNotBlank),
      providerObservationResumeApplied =
        runtimeMetadata["sandboxCommandReconnectProviderObservationResumeApplied"]?.toBooleanStrictOrNull(),
      providerObservationResumeReason =
        runtimeMetadata["sandboxCommandReconnectProviderObservationResumeReason"]?.trim()
          ?.takeIf(String::isNotBlank),
      seed = seed,
    )
  }

  private fun reconnectSeedLocked(): ManagedProcessReconnectSeed? {
    val source = runtimeMetadata["sandboxCommandReconnectSeedSource"]?.trim()?.takeIf(String::isNotBlank)
    val providerObservationSeedConsumed =
      runtimeMetadata["sandboxCommandReconnectProviderObservationSeedConsumed"]?.toBooleanStrictOrNull()
    val providerObservationSeedState =
      runtimeMetadata["sandboxCommandReconnectProviderObservationSeedState"]?.trim()
        ?.takeIf(String::isNotBlank)
    val providerObservationSeedConsumedAtEpochMs =
      runtimeMetadata["sandboxCommandReconnectProviderObservationSeedConsumedAtEpochMs"]?.toLongOrNull()
    val hostObservationCursor = runtimeMetadata["sandboxCommandReconnectSeedObservationCursor"]?.trim()
      ?.takeIf(String::isNotBlank)
    val hostObservationEventCount =
      runtimeMetadata["sandboxCommandReconnectSeedEventCount"]?.toLongOrNull()
    val stdoutBytes = runtimeMetadata["sandboxCommandReconnectSeededStdoutBytes"]?.toLongOrNull()
    val stderrBytes = runtimeMetadata["sandboxCommandReconnectSeededStderrBytes"]?.toLongOrNull()
    val providerObservationCursor =
      runtimeMetadata["sandboxCommandReconnectSeedProviderObservationCursor"]?.trim()
        ?.takeIf(String::isNotBlank)
    val providerObservationEventCount =
      runtimeMetadata["sandboxCommandReconnectSeedProviderObservationEventCount"]?.toLongOrNull()
    val providerObservationSeedSource =
      runtimeMetadata["sandboxCommandReconnectProviderObservationSeedSource"]?.trim()
        ?.takeIf(String::isNotBlank)
    if (
      source == null &&
      providerObservationSeedConsumed == null &&
      providerObservationSeedState == null &&
      providerObservationSeedConsumedAtEpochMs == null &&
      hostObservationCursor == null &&
      hostObservationEventCount == null &&
      stdoutBytes == null &&
      stderrBytes == null &&
      providerObservationCursor == null &&
      providerObservationEventCount == null &&
      providerObservationSeedSource == null
    ) {
      return null
    }
    return ManagedProcessReconnectSeed(
      source = source,
      providerObservationSeedConsumed = providerObservationSeedConsumed,
      providerObservationSeedState = providerObservationSeedState,
      providerObservationSeedConsumedAtEpochMs = providerObservationSeedConsumedAtEpochMs,
      hostObservationCursor = hostObservationCursor,
      hostObservationEventCount = hostObservationEventCount,
      stdoutBytes = stdoutBytes,
      stderrBytes = stderrBytes,
      providerObservationCursor = providerObservationCursor,
      providerObservationEventCount = providerObservationEventCount,
      providerObservationSeedSource = providerObservationSeedSource,
    )
  }
}

private fun resolveNativeCommandSession(
  workspaceRoot: Path,
  sessionStore: E2BSandboxSessionStore,
  activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
): ResolvedNativeCommandSession? {
  val active = activeSessionProvider()
    ?.takeIf { snapshot -> snapshot.matchesWorkspace(workspaceRoot) }
  val stored = sessionStore.load()
    ?.takeIf { snapshot -> snapshot.matchesWorkspace(workspaceRoot) }
  return when {
    active != null && stored != null && active.sandboxId == stored.sandboxId -> {
      ResolvedNativeCommandSession(
        snapshot = active.copy(
          envdAccessToken = active.envdAccessToken ?: stored.envdAccessToken,
          trafficAccessToken = active.trafficAccessToken ?: stored.trafficAccessToken,
          remoteWorkspaceRoot = active.remoteWorkspaceRoot ?: stored.remoteWorkspaceRoot,
        ),
        source = "active_memory_and_persisted",
      )
    }
    active != null -> ResolvedNativeCommandSession(active, "active_memory")
    stored != null -> ResolvedNativeCommandSession(stored, "persisted")
    else -> null
  }
}

private fun isRetryableReconnectHttpStatus(statusCode: Int): Boolean =
  statusCode == 408 || statusCode == 429 || statusCode >= 500

private fun E2BSandboxSessionSnapshot.matchesWorkspace(workspaceRoot: Path): Boolean {
  val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize().toString()
  return this.workspaceRoot == normalizedWorkspace
}

private fun parseConnectEndStreamError(
  payload: ByteArray,
  json: Json,
): ConnectEndStreamError? {
  if (payload.isEmpty()) {
    return null
  }
  val element = runCatching {
    json.parseToJsonElement(String(payload, StandardCharsets.UTF_8)).jsonObject
  }.getOrNull() ?: return ConnectEndStreamError(
    code = null,
    message = String(payload, StandardCharsets.UTF_8),
  )
  val error = element["error"]?.jsonObject ?: return null
  val code = error["code"]?.jsonPrimitive?.contentOrNull
  val message = error["message"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
  return if (code.isNullOrBlank() && message.isBlank()) {
    null
  } else {
    ConnectEndStreamError(
      code = code,
      message = message.ifBlank { "E2B envd Connect end-stream returned an error." },
    )
  }
}

private fun encodeProcessSelector(selector: E2BEnvdProcessSelector): ByteArray = ProtoWriter().apply {
  selector.pid?.let { pid -> writeUInt32(1, pid) }
  selector.tag?.takeIf(String::isNotBlank)?.let { tag -> writeString(2, tag) }
}.toByteArray()

private fun decodeProcessSelector(payload: ByteArray): E2BEnvdProcessSelector {
  var pid: Int? = null
  var tag: String? = null
  ProtoReader(payload).readFields { fieldNumber, wireType, reader ->
    when (fieldNumber) {
      1 -> pid = reader.readUInt32()
      2 -> tag = reader.readString()
      else -> reader.skipField(wireType)
    }
  }
  return E2BEnvdProcessSelector(pid = pid, tag = tag)
}

private fun nativeHeaders(
  envdAccessToken: String,
  timeoutMs: Long,
): Map<String, String> = buildMap {
  put("Accept", E2B_CONNECT_CONTENT_TYPE_PROTO)
  put("Content-Type", E2B_CONNECT_CONTENT_TYPE_PROTO)
  put("Connect-Protocol-Version", E2B_CONNECT_PROTOCOL_VERSION)
  put("Connect-Timeout-Ms", timeoutMs.coerceAtLeast(1L).toString())
  put("Keepalive-Ping-Interval", E2B_ENVD_KEEPALIVE_PING_INTERVAL)
  put("User-Agent", "OpenCray-E2B/1.0")
  put("X-Access-Token", envdAccessToken)
  put(
    "Authorization",
    "Basic " + Base64.getEncoder().encodeToString(
      "$E2B_ENVD_DEFAULT_USER:".toByteArray(StandardCharsets.UTF_8),
    ),
  )
}

private fun nativeStartCommandUrl(
  session: E2BSandboxSessionSnapshot,
): String = "https://$E2B_ENVD_PORT-${session.sandboxId}.${session.sandboxDomain}/process.Process/Start"

private fun nativeConnectCommandUrl(
  session: E2BSandboxSessionSnapshot,
): String = "https://$E2B_ENVD_PORT-${session.sandboxId}.${session.sandboxDomain}/process.Process/Connect"

private fun nativeSendSignalUrl(
  session: E2BSandboxSessionSnapshot,
): String = "https://$E2B_ENVD_PORT-${session.sandboxId}.${session.sandboxDomain}/process.Process/SendSignal"

private fun resolveRemoteWorkingDirectory(
  localWorkspaceRoot: Path,
  remoteWorkspaceRoot: String,
  workingDirectory: String?,
): String {
  val normalized = workingDirectory?.trim().orEmpty()
  if (normalized.isBlank()) {
    return remoteWorkspaceRoot
  }
  val normalizedPath = runCatching { Path.of(normalized).normalize() }.getOrNull()
  if (normalizedPath != null && normalizedPath.isAbsolute) {
    return runCatching {
      val relative = localWorkspaceRoot.relativize(normalizedPath)
      val relativeText = relative.joinToString(separator = "/") { component -> component.toString() }
      when {
        relativeText.isBlank() || relativeText == "." -> remoteWorkspaceRoot
        relativeText.startsWith("..") -> normalizedPath.toString()
        else -> "$remoteWorkspaceRoot/$relativeText"
      }
    }.getOrElse { normalizedPath.toString() }
  }
  val relativeText = normalizedPath?.joinToString(separator = "/") { component -> component.toString() }
    ?: normalized.replace("\\", "/")
  return if (relativeText.isBlank() || relativeText == ".") {
    remoteWorkspaceRoot
  } else {
    "$remoteWorkspaceRoot/$relativeText"
  }
}

private fun timeoutInt(value: Long): Int = value
  .coerceAtLeast(1L)
  .coerceAtMost(Int.MAX_VALUE.toLong())
  .toInt()

private class NativeCommandStreamCollector(
  private val json: Json,
  outputByteLimit: Int,
  baseMetadata: Map<String, String>,
  private val providerStableSelectorValue: String? = baseMetadata["sandboxCommandProviderStableSelectorValue"]
    ?.trim()
    ?.takeIf(String::isNotBlank),
) {
  private val stdoutCollector = BoundedUtf8Collector()
  private val stderrCollector = BoundedUtf8Collector()
  private val totalBytes = TotalByteBudget(outputByteLimit)

  var processStarted: Boolean = false
    private set
  private var endEvent: E2BEnvdProcessEvent.End? = null
  private var endStreamError: ConnectEndStreamError? = null
  private val metadata = LinkedHashMap<String, String>(baseMetadata)

  fun noteHttpStatusCode(statusCode: Int) {
    metadata["sandboxCommandNativeHttpStatusCode"] = statusCode.toString()
  }

  fun onEnvelope(flags: Int, payload: ByteArray) {
    if ((flags and CONNECT_ENVELOPE_FLAG_COMPRESSED) != 0) {
      error("Compressed E2B envd Connect envelopes are not supported in the minimal client.")
    }
    if ((flags and CONNECT_ENVELOPE_FLAG_END_STREAM) != 0) {
      endStreamError = parseConnectEndStreamError(payload, json)
      endStreamError?.code?.let { code ->
        metadata["sandboxCommandNativeEndStreamErrorCode"] = code
      }
      endStreamError?.message?.trim()?.takeIf(String::isNotBlank)?.let { message ->
        metadata["sandboxCommandNativeEndStreamErrorMessage"] = message
      }
      return
    }
    when (val event = E2BEnvdProcessProtoCodec.decodeStartResponse(payload)) {
      is E2BEnvdProcessEvent.Start -> {
        processStarted = true
        metadata["sandboxCommandPid"] = event.pid.toString()
        refreshProviderHandleMetadata()
      }
      is E2BEnvdProcessEvent.Data -> {
        processStarted = true
        event.stdout?.let { stdout -> stdoutCollector.append(stdout, totalBytes) }
        event.stderr?.let { stderr -> stderrCollector.append(stderr, totalBytes) }
        event.pty?.let { pty -> stdoutCollector.append(pty, totalBytes) }
      }
      is E2BEnvdProcessEvent.End -> {
        processStarted = true
        endEvent = event
        metadata["sandboxCommandNativeProcessStatus"] = event.status
        metadata["sandboxCommandNativeProcessExited"] = event.exited.toString()
      }
      E2BEnvdProcessEvent.KeepAlive -> Unit
    }
  }

  private fun refreshProviderHandleMetadata() {
    providerStableSelectorValue?.let { stableSelectorValue ->
      metadata.putAll(
        normalizedProviderHandleMetadata(
          stableSelectorValue = stableSelectorValue,
          metadata = metadata,
        ),
      )
    }
  }

  fun timeoutResult(): CommandSpawnResult = CommandSpawnResult(
    exitCode = null,
    stdout = stdoutCollector.text(),
    stderr = stderrCollector.text(),
    processStarted = processStarted,
    timedOut = true,
    outputLimitExceeded = totalBytes.limitExceeded,
    metadata = metadataWithFailureStage("transport_timeout"),
  )

  fun failureResult(
    message: String,
    failureClass: String? = null,
  ): CommandSpawnResult = CommandSpawnResult(
    exitCode = endEvent?.exitCode,
    stdout = stdoutCollector.text(),
    stderr = stderrWithEndEventError(),
    spawnErrorMessage = message,
    processStarted = processStarted,
    outputLimitExceeded = totalBytes.limitExceeded,
    metadata = metadataWithFailureStage(
      stage = "transport_exception_after_start",
      transportFailureClass = failureClass,
      transportFailureMessage = message,
    ),
  )

  fun finalResult(): CommandSpawnResult {
    val endStreamMessage = endStreamError?.message?.trim().orEmpty()
    if (endStreamError != null && !processStarted) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = "",
        stderr = "",
        spawnErrorMessage = endStreamMessage.ifBlank { "E2B envd Connect stream returned an end-stream error." },
        processStarted = false,
        metadata = metadataWithFailureStage("connect_end_stream_before_start"),
      )
    }
    val completed = endEvent
    if (completed == null) {
      return CommandSpawnResult(
        exitCode = null,
        stdout = stdoutCollector.text(),
        stderr = stderrCollector.text(),
        spawnErrorMessage = if (endStreamMessage.isNotBlank()) {
          endStreamMessage
        } else {
          "E2B native command stream ended without a process end event."
        },
        processStarted = processStarted,
        outputLimitExceeded = totalBytes.limitExceeded,
        metadata = metadataWithFailureStage("missing_process_end_event"),
      )
    }
    return CommandSpawnResult(
      exitCode = completed.exitCode,
      stdout = stdoutCollector.text(),
      stderr = stderrWithEndEventError(),
      processStarted = processStarted,
      outputLimitExceeded = totalBytes.limitExceeded,
      metadata = metadata.toMap(),
    )
  }

  private fun metadataWithFailureStage(
    stage: String,
    transportFailureClass: String? = null,
    transportFailureMessage: String? = null,
  ): Map<String, String> = LinkedHashMap(metadata).apply {
    put("sandboxCommandNativeFailureStage", stage)
    transportFailureClass?.takeIf(String::isNotBlank)?.let { failureClass ->
      put("sandboxCommandNativeTransportFailureClass", failureClass)
    }
    transportFailureMessage?.trim()?.takeIf(String::isNotBlank)?.let { failureMessage ->
      put("sandboxCommandNativeTransportFailureMessage", failureMessage)
    }
  }

  private fun stderrWithEndEventError(): String {
    return stderrWithEndEventError(stderrCollector.text(), endEvent)
  }
}

private fun stderrWithEndEventError(
  stderrText: String,
  endEvent: E2BEnvdProcessEvent.End?,
): String {
  val endError = endEvent?.error?.trim().orEmpty()
  return when {
    endError.isBlank() -> stderrText
    stderrText.isBlank() -> endError
    stderrText.contains(endError) -> stderrText
    else -> "$stderrText\n$endError"
  }
}

private class TotalByteBudget(
  private val limit: Int,
) {
  private var count: Int = 0
  var limitExceeded: Boolean = false
    private set

  fun reserve(length: Int): Int {
    if (length <= 0) {
      return 0
    }
    val available = (limit - count).coerceAtLeast(0)
    val accepted = minOf(available, length)
    count += accepted
    if (accepted < length) {
      limitExceeded = true
    }
    return accepted
  }
}

private class BoundedUtf8Collector {
  private val output = ByteArrayOutputStream()

  fun append(
    bytes: ByteArray,
    totalBudget: TotalByteBudget,
  ) {
    if (bytes.isEmpty()) {
      return
    }
    val accepted = totalBudget.reserve(bytes.size)
    if (accepted > 0) {
      output.write(bytes, 0, accepted)
    }
  }

  fun appendString(
    text: String,
    totalBudget: TotalByteBudget,
  ) {
    append(text.toByteArray(StandardCharsets.UTF_8), totalBudget)
  }

  fun text(): String = output.toString(StandardCharsets.UTF_8.name())
}

private class ProtoWriter {
  private val output = ByteArrayOutputStream()

  fun writeString(fieldNumber: Int, value: String) {
    writeLengthDelimited(fieldNumber, value.toByteArray(StandardCharsets.UTF_8))
  }

  fun writeBytes(fieldNumber: Int, value: ByteArray) {
    writeLengthDelimited(fieldNumber, value)
  }

  fun writeMessage(fieldNumber: Int, value: ByteArray) {
    writeLengthDelimited(fieldNumber, value)
  }

  fun writeBool(fieldNumber: Int, value: Boolean) {
    writeTag(fieldNumber, wireType = 0)
    writeVarint(if (value) 1 else 0)
  }

  fun writeUInt32(fieldNumber: Int, value: Int) {
    writeTag(fieldNumber, wireType = 0)
    writeVarint(value.toLong() and 0xFFFFFFFFL)
  }

  fun writeSInt32(fieldNumber: Int, value: Int) {
    writeTag(fieldNumber, wireType = 0)
    writeVarint(zigZagEncode32(value).toLong() and 0xFFFFFFFFL)
  }

  fun toByteArray(): ByteArray = output.toByteArray()

  private fun writeLengthDelimited(fieldNumber: Int, value: ByteArray) {
    writeTag(fieldNumber, wireType = 2)
    writeVarint(value.size.toLong())
    output.write(value, 0, value.size)
  }

  private fun writeTag(fieldNumber: Int, wireType: Int) {
    writeVarint(((fieldNumber shl 3) or wireType).toLong())
  }

  private fun writeVarint(value: Long) {
    var current = value
    while (true) {
      if ((current and 0x7FL.inv()) == 0L) {
        output.write(current.toInt())
        return
      }
      output.write(((current and 0x7F) or 0x80).toInt())
      current = current ushr 7
    }
  }

  private fun zigZagEncode32(value: Int): Int = (value shl 1) xor (value shr 31)
}

private class ProtoReader(
  private val bytes: ByteArray,
) {
  private var position: Int = 0

  fun readFields(
    onField: (fieldNumber: Int, wireType: Int, reader: ProtoReader) -> Unit,
  ) {
    while (!isAtEnd()) {
      val tag = readVarint().toInt()
      val fieldNumber = tag ushr 3
      val wireType = tag and 0x07
      onField(fieldNumber, wireType, this)
    }
  }

  fun readString(): String = String(readLengthDelimited(), StandardCharsets.UTF_8)

  fun readBytes(): ByteArray = readLengthDelimited()

  fun readLengthDelimited(): ByteArray {
    val length = readVarint().toInt()
    require(length >= 0) { "Negative protobuf length encountered." }
    val end = position + length
    require(end <= bytes.size) { "Length-delimited protobuf field exceeds input size." }
    val slice = bytes.copyOfRange(position, end)
    position = end
    return slice
  }

  fun readBool(): Boolean = readVarint() != 0L

  fun readUInt32(): Int = readVarint().toInt()

  fun readSInt32(): Int = zigZagDecode32(readVarint().toInt())

  fun skipField(wireType: Int) {
    when (wireType) {
      0 -> readVarint()
      1 -> skipBytes(8)
      2 -> {
        val length = readVarint().toInt()
        skipBytes(length)
      }
      5 -> skipBytes(4)
      else -> error("Unsupported protobuf wire type: $wireType")
    }
  }

  private fun skipBytes(length: Int) {
    require(length >= 0) { "Negative protobuf skip length encountered." }
    val end = position + length
    require(end <= bytes.size) { "Protobuf skip exceeds input size." }
    position = end
  }

  private fun readVarint(): Long {
    var shift = 0
    var result = 0L
    while (shift < 64) {
      require(position < bytes.size) { "Unexpected end of protobuf input." }
      val byte = bytes[position++].toInt() and 0xFF
      result = result or ((byte and 0x7F).toLong() shl shift)
      if ((byte and 0x80) == 0) {
        return result
      }
      shift += 7
    }
    error("Malformed protobuf varint.")
  }

  private fun zigZagDecode32(value: Int): Int = (value ushr 1) xor -(value and 1)

  private fun isAtEnd(): Boolean = position >= bytes.size
}
