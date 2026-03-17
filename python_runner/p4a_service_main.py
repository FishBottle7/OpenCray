from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

from python_runner.p4a_bridge import run_request_file


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
        return 250
    try:
        return int(raw_value)
    except (TypeError, ValueError):
        return 250


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-root", default=_default_runtime_root())
    parser.add_argument("--poll-interval-ms", type=int, default=_default_poll_interval_ms())
    parser.add_argument("--once", action="store_true")
    return parser


def _process_pending_requests(runtime_root: Path) -> int:
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"
    requests_dir.mkdir(parents=True, exist_ok=True)
    results_dir.mkdir(parents=True, exist_ok=True)
    logs_dir.mkdir(parents=True, exist_ok=True)

    processed = 0
    for request_path in sorted(requests_dir.glob("*.json")):
        request_id = request_path.stem
        result_path = results_dir / f"{request_id}.json"
        if result_path.exists():
            continue
        log_path = logs_dir / f"{request_id}.log"
        run_request_file(
            request_path=request_path,
            result_path=result_path,
            log_path=log_path,
        )
        processed += 1
    return processed


def main(argv: list[str] | None = None) -> int:
    ns = _build_arg_parser().parse_args(argv)
    runtime_root_raw = str(ns.runtime_root).strip()
    if not runtime_root_raw:
        raise SystemExit("PYTHON_SERVICE_ARGUMENT or --runtime-root must provide runtimeRoot.")

    runtime_root = Path(runtime_root_raw)

    if ns.once:
        _process_pending_requests(runtime_root)
        return 0

    while True:
        _process_pending_requests(runtime_root)
        time.sleep(max(ns.poll_interval_ms, 50) / 1000.0)


if __name__ == "__main__":
    raise SystemExit(main())
