package com.opencray.app.e2b

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

internal const val CONNECT_ENVELOPE_FLAG_COMPRESSED: Int = 0x01
internal const val CONNECT_ENVELOPE_FLAG_END_STREAM: Int = 0x02

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

internal class ProtoWriter {
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

internal class ProtoReader(
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
