from __future__ import annotations

import argparse
import os
import time
from pathlib import Path

from tools.android_python_runtime_p4a.runtime.opencray_runtime_main import run_request_file


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime-root", default=os.environ.get("OPENCRAY_P4A_RUNTIME_ROOT", ""))
    parser.add_argument("--poll-interval-ms", type=int, default=250)
    parser.add_argument("--once", action="store_true")
    return parser


def _process_pending_requests(runtime_root: Path) -> int:
    requests_dir = runtime_root / "requests"
    results_dir = runtime_root / "results"
    logs_dir = runtime_root / "logs"
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
        raise SystemExit("OPENCRAY_P4A_RUNTIME_ROOT or --runtime-root is required.")

    runtime_root = Path(runtime_root_raw)
    requests_dir = runtime_root / "requests"
    requests_dir.mkdir(parents=True, exist_ok=True)

    if ns.once:
        _process_pending_requests(runtime_root)
        return 0

    while True:
        _process_pending_requests(runtime_root)
        time.sleep(max(ns.poll_interval_ms, 50) / 1000.0)


if __name__ == "__main__":
    raise SystemExit(main())
