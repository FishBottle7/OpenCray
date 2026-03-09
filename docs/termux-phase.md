# Termux runtime phase split

## Scope statement

Task 17 adds a normalized Termux adapter contract for runtime parity planning. V1 production execution must not require Termux installation, Termux permissions, or Termux process execution. The supported production path in V1 remains the in-app runtime.

## Current V1 contract types

The current V1 scaffold defines these runtime contract types in `runtime/src/main/kotlin/com/opencray/runtime/TermuxRuntimeAdapterContract.kt`:

- `TermuxRuntimeAdapter`
- `TermuxRuntimeBackend`
- `TermuxV1Marker`
- `TermuxRuntimeAvailability`
- `TermuxRuntimeOperation`
- `TermuxRuntimeRequest`
  - `TermuxRuntimeRequest.Exec`
  - `TermuxRuntimeRequest.InstallDependencies`
- `TermuxRuntimeResponse`
- `TermuxRuntimeContract`
- `V1UnavailableTermuxRuntimeAdapter`

Related request mapping helpers are also present:

- `PythonExecRequest.toTermuxRuntimeRequest(...)`
- `PipInstallRequest.toTermuxRuntimeRequest(...)`

## V1 scaffold-only, shipping now

The following behavior is part of V1:

- A stable request and response shape for both script execution and dependency installation.
- A shared outcome envelope through `ExecutionResult`, so callers do not need a Termux-specific success or failure model.
- A normalized backend marker through `TermuxRuntimeBackend`.
- A normalized availability marker through `TermuxRuntimeAvailability` and `TermuxV1Marker`.
- Metadata normalization through `TermuxRuntimeResponse.from(...)`, including:
  - `TermuxRuntimeContract.METADATA_RUNTIME_OPERATION`
  - `TermuxRuntimeContract.METADATA_RUNTIME_BACKEND`
  - `TermuxRuntimeContract.METADATA_TERMUX_AVAILABLE`
  - `TermuxRuntimeContract.METADATA_TERMUX_V1_MARKER`
- A V1 stub adapter through `V1UnavailableTermuxRuntimeAdapter`.

This is scaffold-only. It defines the contract that a future Termux-backed runtime must satisfy, but it does not add real Termux execution in V1.

## V1 unavailable stub behavior

`TermuxRuntimeAdapter.v1Unavailable()` returns `V1UnavailableTermuxRuntimeAdapter`.

Its operational behavior is fixed as follows:

- `backend` is `TermuxRuntimeBackend.TERMUX_STUB`.
- `availability` is `TermuxRuntimeAvailability.Unavailable`.
- The default unavailable reason code is `TermuxRuntimeContract.ERROR_TERMUX_UNAVAILABLE`.
- The default unavailable detail is `"Termux execution is unavailable in V1; callers must use the in-app runtime path."`
- The V1 marker is `TermuxV1Marker.UNAVAILABLE_IN_V1`.
- Any `execute(...)`, `exec(...)`, or `install(...)` call returns `TermuxRuntimeResponse` with an `ExecutionResult` whose:
  - `status` is `ExecutionStatus.DENIED`
  - `errorCode` matches the availability reason code
  - `errorMessage` matches the availability detail
  - `taskId` matches the request task id
- Request metadata is preserved, then normalized metadata keys are added if absent.

The stub is intentional. It gives callers a deterministic contract outcome while keeping V1 production behavior independent from Termux.

## Deferred Phase 2 implementation items

The following work is deferred to Phase 2 and is not part of V1:

- Real `TermuxRuntimeBackend.TERMUX` execution.
- Real Termux availability detection and environment validation.
- Process launch and lifecycle handling for Termux-backed script execution.
- Termux-backed dependency installation flow.
- Mapping of Termux process results into the normalized `ExecutionResult` envelope.
- Translation of Termux-specific failure conditions into stable error codes and messages.
- Runtime configuration for selecting the in-app backend versus a real Termux backend.
- Production hardening for filesystem, timeout, and environment parity between both runtime paths.

## Parity expectations between in-app runtime and Termux adapter

When Phase 2 is implemented, the Termux adapter is expected to match the in-app runtime contract at the caller boundary:

- The same logical operations must be supported: exec and dependency installation.
- `ExecutionResult` remains the canonical result payload for both backends.
- `taskId`, status, timestamps, and error reporting semantics must stay comparable across backends.
- Metadata must continue to expose operation, backend, availability, and V1 marker state through the normalized contract keys.
- Callers must not need separate business logic branches for in-app success and Termux success.
- Backend selection may vary, but the response contract must remain stable.

## Learnings

- The V1 contract is intentionally complete at the API boundary, even though the Termux backend is still denied by design.

## Issues

- `docs/` did not exist before this file, so this task introduces the directory together with the requested document.
