from __future__ import annotations

import argparse
import json
import os
import time
import traceback
from pathlib import Path

from python_runner.p4a_bridge import run_request_file

SERVICE_STATE_SCHEMA_VERSION = 1
SERVICE_HEARTBEAT_INTERVAL_MS = 1000


def _now_ms() -> int:
    return int(time.time() * 1000)


def _atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    tmp_path.write_text(content, encoding="utf-8")
    os.replace(tmp_path, path)


def _write_json(path: Path, payload: dict[str, object]) -> None:
    _atomic_write_text(
        path,
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    )


def _service_argument_payload() -> dict[str, object]:
    raw_argument = os.environ.get("PYTHON_SERVICE_ARGUMENT", "").strip()
    if not raw_argument:
        return {}
    try:
        payload = json.loads(raw_argument)
    except json.JSONDecodeError:
        return {"runtimeRoot": raw_argument}
    if isinstance(payload, dict):
        return payload
    return {"runtimeRoot": str(payload)}


def _default_runtime_root() -> str:
    payload = _service_argument_payload()
    runtime_root = str(payload.get("runtimeRoot", "")).strip()
    if runtime_root:
        return runtime_root
    return os.environ.get("OPENCRAY_P4A_RUNTIME_ROOT", "").strip()


def _default_poll_interval_ms() -> int:
    payload = _service_argument_payload()
    raw_value = payload.get("pollIntervalMs")
    if raw_value is None:
        return 25
    try:
        return int(raw_value)
    except (TypeError, ValueError):
        return 25


def _default_run_once() -> bool:
    payload = _service_argument_payload()
    raw_value = payload.get("once")
    if isinstance(raw_value, bool):
        return raw_value
    if isinstance(raw_value, str):
        normalized = raw_value.strip().lower()
        if normalized in {"1", "true", "yes", "on"}:
            return True
        if normalized in {"0", "false", "no", "off"}:
            return False
    return False


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-root", default=_default_runtime_root())
    parser.add_argument("--poll-interval-ms", type=int, default=_default_poll_interval_ms())
    parser.add_argument("--once", action="store_true", default=_default_run_once())
    return parser


def _service_state_paths(runtime_root: Path) -> tuple[Path, Path]:
    state_dir = runtime_root / "service_state"
    return state_dir / "service-state.json", state_dir / "service-ready.json"


def _load_json_if_present(path: Path) -> dict[str, object]:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


def _write_service_state(
    runtime_root: Path,
    *,
    state: str,
    started_at_epoch_ms: int,
    poll_interval_ms: int,
    startup_request_id: str,
    current_request_id: str | None = None,
    claimed_request_id: str | None = None,
    execution_started_at_epoch_ms: int | None = None,
    last_observed_request_id: str | None = None,
    last_processed_request_id: str | None = None,
    last_processed_status: str | None = None,
    last_error: str | None = None,
    last_traceback: str | None = None,
) -> None:
    state_path, ready_path = _service_state_paths(runtime_root)
    existing = _load_json_if_present(state_path)
    payload: dict[str, object] = {
        "schemaVersion": SERVICE_STATE_SCHEMA_VERSION,
        "runtimeRoot": str(runtime_root),
        "pid": os.getpid(),
        "state": state,
        "startedAtEpochMs": int(existing.get("startedAtEpochMs", started_at_epoch_ms)),
        "updatedAtEpochMs": _now_ms(),
        "pollIntervalMs": poll_interval_ms,
        "startupRequestId": startup_request_id,
        "currentRequestId": current_request_id,
        "claimedRequestId": claimed_request_id,
        "executionStartedAtEpochMs": execution_started_at_epoch_ms,
        "lastObservedRequestId": last_observed_request_id
        if last_observed_request_id is not None
        else existing.get("lastObservedRequestId"),
        "lastProcessedRequestId": last_processed_request_id
        if last_processed_request_id is not None
        else existing.get("lastProcessedRequestId"),
        "lastProcessedStatus": last_processed_status
        if last_processed_status is not None
        else existing.get("lastProcessedStatus"),
        "lastError": last_error if last_error is not None else existing.get("lastError"),
        "lastTraceback": last_traceback if last_traceback is not None else existing.get("lastTraceback"),
    }
    _write_json(state_path, payload)
    _write_json(
        ready_path,
        {
            "schemaVersion": SERVICE_STATE_SCHEMA_VERSION,
            "runtimeRoot": str(runtime_root),
            "pid": os.getpid(),
            "state": state,
            "startedAtEpochMs": payload["startedAtEpochMs"],
            "updatedAtEpochMs": payload["updatedAtEpochMs"],
            "pollIntervalMs": poll_interval_ms,
            "startupRequestId": startup_request_id,
            "currentRequestId": current_request_id,
            "claimedRequestId": claimed_request_id,
            "executionStartedAtEpochMs": execution_started_at_epoch_ms,
        },
    )


def _ensure_runtime_dirs(runtime_root: Path) -> None:
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"
    requests_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)
    (_service_state_paths(runtime_root)[0]).parent.mkdir(parents=True, exist_ok=True)


def _result_status(result_path: Path) -> str | None:
    if not result_path.exists():
        return None
    try:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    status = payload.get("status")
    return str(status).strip() if status is not None else None


def _safe_unlink(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except Exception:
        pass


def _pending_request_paths(
    requests_dir: Path,
    results_dir: Path,
    startup_request_id: str,
    *,
    limit: int | None = None,
) -> list[Path]:
    pending = [
        request_path
        for request_path in sorted(requests_dir.glob("*.json"))
        if not (results_dir / f"{request_path.stem}.json").exists()
    ]
    if not startup_request_id:
        return pending

    startup_path = next(
        (request_path for request_path in pending if request_path.stem == startup_request_id),
        None,
    )
    if startup_path is None:
        return pending[:limit] if limit is not None else pending
    ordered = [startup_path, *(request_path for request_path in pending if request_path != startup_path)]
    return ordered[:limit] if limit is not None else ordered


def _process_pending_requests(
    runtime_root: Path,
    *,
    started_at_epoch_ms: int,
    poll_interval_ms: int,
    startup_request_id: str,
    limit: int | None = None,
) -> int:
    _ensure_runtime_dirs(runtime_root)
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"

    processed = 0
    for request_path in _pending_request_paths(
        requests_dir=requests_dir,
        results_dir=results_dir,
        startup_request_id=startup_request_id,
        limit=limit,
    ):
        request_id = request_path.stem
        result_path = results_dir / f"{request_id}.json"
        log_path = logs_dir / f"{request_id}.log"
        execution_started_at = _now_ms()
        _write_service_state(
            runtime_root,
            state="processing",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=request_id,
            claimed_request_id=request_id,
            execution_started_at_epoch_ms=execution_started_at,
            last_observed_request_id=request_id,
        )
        run_request_file(
            request_path=request_path,
            result_path=result_path,
            log_path=log_path,
            execution_started_at_epoch_ms=execution_started_at,
        )
        _write_service_state(
            runtime_root,
            state="idle",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=None,
            claimed_request_id=None,
            execution_started_at_epoch_ms=None,
            last_observed_request_id=request_id,
            last_processed_request_id=request_id,
            last_processed_status=_result_status(result_path),
        )
        _safe_unlink(request_path)
        processed += 1
    return processed


def main(argv: list[str] | None = None) -> int:
    ns = _build_arg_parser().parse_args(argv)
    runtime_root_raw = str(ns.runtime_root).strip()
    if not runtime_root_raw:
        raise SystemExit("PYTHON_SERVICE_ARGUMENT or --runtime-root must provide runtimeRoot.")

    runtime_root = Path(runtime_root_raw)
    started_at_epoch_ms = _now_ms()
    poll_interval_ms = max(int(ns.poll_interval_ms), 25)
    startup_request_id = str(_service_argument_payload().get("requestId", "")).strip()

    try:
        _ensure_runtime_dirs(runtime_root)
        _write_service_state(
            runtime_root,
            state="ready",
            started_at_epoch_ms=started_at_epoch_ms,
            poll_interval_ms=poll_interval_ms,
            startup_request_id=startup_request_id,
            current_request_id=None,
            claimed_request_id=None,
            execution_started_at_epoch_ms=None,
        )

        if ns.once:
            _process_pending_requests(
                runtime_root,
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                limit=1,
            )
            _write_service_state(
                runtime_root,
                state="idle",
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                current_request_id=None,
                claimed_request_id=None,
                execution_started_at_epoch_ms=None,
            )
            return 0

        next_heartbeat_epoch_ms = 0
        while True:
            now = _now_ms()
            if now >= next_heartbeat_epoch_ms:
                _write_service_state(
                    runtime_root,
                    state="idle",
                    started_at_epoch_ms=started_at_epoch_ms,
                    poll_interval_ms=poll_interval_ms,
                    startup_request_id=startup_request_id,
                    current_request_id=None,
                    claimed_request_id=None,
                    execution_started_at_epoch_ms=None,
                )
                next_heartbeat_epoch_ms = now + max(SERVICE_HEARTBEAT_INTERVAL_MS, poll_interval_ms)
            _process_pending_requests(
                runtime_root,
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
            )
            time.sleep(poll_interval_ms / 1000.0)
    except Exception as exc:
        try:
            _ensure_runtime_dirs(runtime_root)
            _write_service_state(
                runtime_root,
                state="startup_error",
                started_at_epoch_ms=started_at_epoch_ms,
                poll_interval_ms=poll_interval_ms,
                startup_request_id=startup_request_id,
                current_request_id=None,
                claimed_request_id=None,
                execution_started_at_epoch_ms=None,
                last_error=str(exc),
                last_traceback=traceback.format_exc(),
            )
        except Exception:
            pass
        raise


if __name__ == "__main__":
    raise SystemExit(main())
