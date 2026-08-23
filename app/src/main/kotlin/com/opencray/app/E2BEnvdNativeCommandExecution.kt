package com.opencray.app

import com.opencray.app.e2b.E2BMinimalNativeForegroundCommandProcessRunner
import com.opencray.app.e2b.E2BMinimalProtocolManagedProcessControllerFactory
import com.opencray.app.e2b.E2BResponse
import com.opencray.app.e2b.E2BSandboxActivityTracker
import com.opencray.app.e2b.SharedE2BSandboxActivityTracker
import com.opencray.core.contracts.ExecutionResult
import com.opencray.core.contracts.PolicyDecision
import com.opencray.core.orchestrator.RuntimeExecutionHooks
import com.opencray.runtime.CommandApprovalToken
import com.opencray.runtime.CommandExecutionRequest
import com.opencray.runtime.CommandExecutor
import com.opencray.runtime.PythonScriptRuntime
import com.opencray.runtime.process.ManagedProcessController
import com.opencray.runtime.process.ManagedProcessControllerFactory
import com.opencray.runtime.process.ManagedProcessSnapshot
import com.opencray.runtime.process.ManagedProcessStartRequest
import com.opencray.runtime.process.ReconnectableManagedProcessControllerFactory
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.json.Json

private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_CONTRACT: String =
  "host_buffered_seed_then_live_attach"
private const val E2B_NATIVE_COMMAND_PROVIDER_RESUME_BLOCKER: String =
  "envd_connect_request_selector_only"

internal class E2BMinimalProtocolSandboxCommandExecutionBackend(
  private val workspaceRootProvider: () -> Path,
  private val settingsProvider: () -> ResolvedSandboxSettings,
  private val sessionStore: E2BSandboxSessionStore,
  private val activeSessionProvider: () -> E2BSandboxSessionSnapshot?,
  private val pythonRuntime: PythonScriptRuntime,
  private val transport: E2BEnvdCommandTransport = UrlConnectionE2BEnvdCommandTransport(),
  private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
  private val activityTracker: E2BSandboxActivityTracker = SharedE2BSandboxActivityTracker,
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
        activityTracker = activityTracker,
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
