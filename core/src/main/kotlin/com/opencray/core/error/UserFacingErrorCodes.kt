package com.opencray.core.error

/**
 * Central registry mapping internal string error codes to short user-facing
 * error codes (E + 4 digits) so failures can be reported and looked up easily.
 *
 * Segments: E0xxx policy/approval, E1xxx command/process execution,
 * E2xxx LLM/provider, E3xxx session orchestration, E4xxx filesystem,
 * E5xxx skills, E6xxx MCP (reserved), E7xxx terminal environment,
 * E8xxx subagent, E9xxx unknown/unregistered.
 *
 * When adding or renaming an error code, register it here with a unique
 * short code and update docs/error-codes.md.
 */
object UserFacingErrorCodes {
  const val UNKNOWN: String = "E9999"

  private val registry: Map<String, String> = buildMap {
    put("DENY_POLICY", "E0001")
    put("APPROVAL_REQUIRED", "E0002")
    put("HIGH_RISK_APPROVAL_REQUIRED", "E0003")
    put("WORKSPACE_BOUNDARY_DENIED", "E0004")
    put("SKILL_TOOL_POLICY_BLOCKED", "E0005")
    put("BLOCK_APPROVAL_REQUIRED", "E0006")
    put("BLOCK_APPROVAL_TASK_MISMATCH", "E0007")
    put("DENY_POLICY_DECISION", "E0008")
    put("MEDIA_JOB_ID_INVALID", "E0009")
    put("MEDIA_JOB_ORIGIN_MISMATCH", "E0010")
    put("DENY_INVALID_PATH", "E0011")
    put("DENY_PATH_TRAVERSAL", "E0012")
    put("DENY_PATH_ESCAPE", "E0013")
    put("DENY_PROTECTED_FILE", "E0014")

    put("TIMEOUT", "E1001")
    put("EXEC_ERROR", "E1002")
    put("SPAWN_ERROR", "E1003")
    put("OUTPUT_LIMIT_EXCEEDED", "E1004")
    put("CANCELLED_BY_HOOK", "E1005")
    put("CANCELLED", "E1006")
    put("PYTHON_RUNTIME_EXECUTION_FAILED", "E1011")
    put("PROCESS_INTERRUPTED_ON_RESTORE", "E1012")

    put("PROVIDER_FAILURE", "E2001")
    put("PROVIDER_COMPACT_FAILURE", "E2002")
    put("PROVIDER_TIMEOUT_FALLBACK_APPLIED", "E2010")
    put("PROVIDER_TIMEOUT_TERMINAL_POLICY", "E2011")
    put("PROVIDER_TIMEOUT_FALLBACK_EXHAUSTED", "E2012")
    put("PROVIDER_RATE_LIMIT_429_FALLBACK_APPLIED", "E2020")
    put("PROVIDER_RATE_LIMIT_429_TERMINAL_POLICY", "E2021")
    put("PROVIDER_RATE_LIMIT_429_FALLBACK_EXHAUSTED", "E2022")
    put("LLM_RETRY_EXHAUSTED_AWAITING_RESUME", "E2031")
    put("EMPTY_RESPONSE_RECOVERY_EXHAUSTED", "E2032")
    put("MISSING_LLM_CONFIG", "E2040")
    put("ON_DEVICE_LLM_NOT_SUPPORTED", "E2041")
    put("PROVIDER_REQUEST_CANCELLED", "E2042")

    put("RUNTIME_EXCEPTION", "E3001")
    put("RUNTIME_INTERRUPTED", "E3002")
    put("RESTART_REQUIRES_EXPLICIT_RETRY", "E3003")
    put("OWNER_LEASE_STORE_CORRUPTED", "E3004")

    put("INVALID_OPERATION", "E4001")
    put("ALREADY_EXISTS", "E4002")
    put("FILE_NOT_FOUND", "E4003")
    put("IO_ERROR", "E4004")
    put("ROLLBACK_FAILED", "E4005")

    put("FILE_READ_FAILED", "E5001")
    put("MISSING_FRONT_MATTER", "E5002")
    put("UNTERMINATED_FRONT_MATTER", "E5003")
    put("INVALID_FRONT_MATTER", "E5004")
    put("DUPLICATE_SKILL_NAME", "E5005")
    put("INVALID_SKILL_METADATA", "E5006")

    put("TERMUX_UNAVAILABLE", "E7001")

    put("SUBAGENT_BACKGROUND_INTERRUPTED", "E8001")
  }

  fun all(): Map<String, String> = registry

  fun shortCodeOf(stringCode: String?): String? = stringCode
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let(registry::get)
}
