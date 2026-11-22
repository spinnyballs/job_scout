"""CLI entry point for running scans or launching the local web dashboard."""
from __future__ import annotations

import argparse
import asyncio
import os
import socket
import webbrowser
from threading import Timer

import uvicorn

from app.config import ConfigManager
from app.scanner import ScannerOrchestrator
from app.storage import JobStore


async def _run_cli_scan(source: str | None, probes: int) -> None:
    store = JobStore()
    cfg = ConfigManager()
    orch = ScannerOrchestrator(store=store, config_mgr=cfg)
    print("Starting scan...")
    summary = await orch.run_scan(source_id=source, max_auto_salary_probes=probes)
    print(
        f"Done! Scanned {summary.total_fetched} listings across {summary.queries_run} queries -> "
        f"{summary.new_jobs} new, {summary.updated_jobs} updated, {summary.blocked_jobs} blocked, "
        f"{summary.details_fetched} full JDs archived, {summary.salaries_probed} hidden salaries estimated."
    )
    if summary.errors:
        print("Warnings/Errors:")
        for err in summary.errors:
            print(f"  - {err}")

    top = store.list_jobs(min_score=45, limit=10)
    if top:
        print("\nTop Matches:")
        for j in top:
            sal = j["salary_label"] or (
                f"~${j['salary_est_min']//1000}k-${j['salary_est_max']//1000}k"
                if j.get("salary_est_min")
                else "unprobed"
            )
            print(f"  [{j['score']:>3}] {j['title']} @ {j['company']} ({j['location']}) | {sal}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Job Scout — Multi-board job scanner & dashboard")
    sub = parser.add_subparsers(dest="cmd")

    serve_p = sub.add_parser("serve", help="Start the local web dashboard")
    serve_p.add_argument("--host", default="0.0.0.0")
    serve_p.add_argument("--port", type=int, default=8765)
    serve_p.add_argument("--open", action="store_true", help="Open dashboard directly inside the copilot browser")
    serve_p.add_argument("--system-browser", action="store_true", help="Open in default OS browser instead of unified copilot browser")

    scan_p = sub.add_parser("scan", help="Run a headless scan into SQLite")
    scan_p.add_argument("--source", default=None, help="Board ID (e.g., seek_nz, seek_au)")
    scan_p.add_argument("--probes", type=int, default=6, help="Max top jobs to auto-probe hidden salary")

    args = parser.parse_args()
    cmd = args.cmd or "serve"

    if cmd == "scan":
        asyncio.run(_run_cli_scan(args.source, args.probes))
    else:
        host = getattr(args, "host", "0.0.0.0")
        port = getattr(args, "port", 8765)
        check_host = "127.0.0.1" if host == "0.0.0.0" else host
        url = f"http://{check_host}:{port}"
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(0.5)
            if sock.connect_ex((check_host, port)) == 0:
                print(
                    f"\n[i] Job Scout is already running on {url}.\n"
                    f"    Opening dashboard in your browser..."
                )
                if getattr(args, "open", False):
                    webbrowser.open(url)
                return
        if getattr(args, "open", False):
            if getattr(args, "system_browser", False):
                Timer(1.0, lambda: webbrowser.open(url)).start()
            else:
                os.environ["JOB_SCOUT_OPEN_COPILOT_BROWSER"] = "1"
                os.environ["JOB_SCOUT_URL"] = url
        print(f"Dashboard running at {url} (listening on {host}:{port} for Phone Wi-Fi Sync)")
        uvicorn.run("app.main:app", host=host, port=port, reload=False)


if __name__ == "__main__":
    main()
