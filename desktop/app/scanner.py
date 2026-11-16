"""End-to-end scan orchestrator (Search -> Deduplicate -> GraphQL Detail -> Rule Score -> Salary Probe -> SQLite)."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from .config import ConfigManager
from .models import JobStatus, NormalizedJob, ScannerConfig, ScanRunSummary
from .scoring import score_job
from .sources import get_adapter
from .sources.seek import parse_seek_url_or_id
from .storage import JobStore


@dataclass
class LiveScanState:
    is_running: bool = False
    stage: str = "idle"
    message: str = "Ready"
    queries_done: int = 0
    queries_total: int = 0
    fetched: int = 0
    new_jobs: int = 0
    details_done: int = 0
    salaries_done: int = 0
    last_summary: dict[str, Any] | None = None
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "is_running": self.is_running,
            "stage": self.stage,
            "message": self.message,
            "queries_done": self.queries_done,
            "queries_total": self.queries_total,
            "fetched": self.fetched,
            "new_jobs": self.new_jobs,
            "details_done": self.details_done,
            "salaries_done": self.salaries_done,
            "last_summary": self.last_summary,
            "errors": self.errors[-10:],
        }


class ScannerOrchestrator:
    def __init__(self, store: JobStore, config_mgr: ConfigManager) -> None:
        self.store = store
        self.config_mgr = config_mgr
        self.state = LiveScanState()
        self._lock = asyncio.Lock()

    async def run_scan(
        self,
        *,
        source_id: str | None = None,
        query_names: list[str] | None = None,
        max_auto_salary_probes: int = 8,
    ) -> ScanRunSummary:
        if self._lock.locked():
            raise RuntimeError("A scan is already in progress.")

        async with self._lock:
            config = self.config_mgr.load()
            board_id = source_id or config.default_board
            run_id = self.store.create_scan_run(board_id)
            summary = ScanRunSummary(
                run_id=run_id,
                started_at=datetime.now(timezone.utc),
                source=board_id,
            )

            enabled_queries = [
                q for q in config.queries
                if q.enabled and (not query_names or q.name in query_names)
            ]

            self.state = LiveScanState(
                is_running=True,
                stage="searching",
                message=f"Starting scan across {len(enabled_queries)} search profile(s)...",
                queries_total=len(enabled_queries),
            )

            adapter = get_adapter(board_id, request_delay_ms=config.request_delay_ms)
            try:
                # 1. Collect & merge search results by external_id within this batch
                batch: dict[str, NormalizedJob] = {}
                for idx, q in enumerate(enabled_queries, start=1):
                    self.state.message = f"[{idx}/{len(enabled_queries)}] Scanning '{q.name}'..."
                    try:
                        async for item in adapter.search(q):
                            summary.total_fetched += 1
                            self.state.fetched = summary.total_fetched
                            if item.external_id in batch:
                                existing_item = batch[item.external_id]
                                for mq in item.matched_queries:
                                    if mq not in existing_item.matched_queries:
                                        existing_item.matched_queries.append(mq)
                            else:
                                batch[item.external_id] = item
                        summary.queries_run += 1
                    except Exception as exc:
                        err = f"Query '{q.name}' failed: {exc}"
                        summary.errors.append(err)
                        self.state.errors.append(err)
                    self.state.queries_done = idx

                # 2. Enrich details for new listings (or existing ones missing description) & upsert
                self.state.stage = "enriching"
                probe_candidates: list[tuple[int, NormalizedJob, int]] = []

                items_list = list(batch.values())
                for i, job in enumerate(items_list, start=1):
                    db_existing = self.store.get_existing_job(job.source, job.external_id)
                    needs_detail = config.fetch_details_for_all_new and (
                        db_existing is None or not db_existing.get("description_text")
                    )

                    # Quick pre-check blocklist on company name before spending detail request
                    pre_score = score_job(job, config)
                    if needs_detail and not pre_score.blocked_reason:
                        self.state.message = f"Fetching job details ({i}/{len(items_list)}): {job.title[:45]}..."
                        try:
                            job = await adapter.enrich_detail(job)
                            summary.details_fetched += 1
                            self.state.details_done = summary.details_fetched
                        except Exception as exc:
                            err = f"Detail fetch failed for {job.external_id}: {exc}"
                            summary.errors.append(err)
                            self.state.errors.append(err)
                    elif db_existing and db_existing.get("description_text"):
                        # Carry existing description & salary forward for accurate scoring
                        job.description_html = db_existing.get("description_html") or ""
                        job.description_text = db_existing.get("description_text") or ""
                        job.is_link_out = bool(db_existing.get("is_link_out"))
                        if job.salary_est_min is None:
                            job.salary_est_min = db_existing.get("salary_est_min")
                            job.salary_est_max = db_existing.get("salary_est_max")

                    final_breakdown = score_job(job, config)
                    row_id, is_new = self.store.upsert_job(job, final_breakdown)
                    if is_new:
                        summary.new_jobs += 1
                        self.state.new_jobs = summary.new_jobs
                    else:
                        summary.updated_jobs += 1
                    if final_breakdown.blocked_reason:
                        summary.blocked_jobs += 1

                    # Queue top unprobed matches for automatic Seek hidden salary estimation
                    already_has_salary = (
                        job.salary_est_min is not None
                        or (db_existing and db_existing.get("salary_probed_at"))
                    )
                    if (
                        adapter.supports_salary_probe
                        and not final_breakdown.blocked_reason
                        and not already_has_salary
                        and final_breakdown.score >= config.probe_salary_above_score
                    ):
                        probe_candidates.append((row_id, job, final_breakdown.score))

                # 3. Probe hidden salary for top N new/unprobed matches
                if probe_candidates and max_auto_salary_probes > 0:
                    self.state.stage = "probing_salary"
                    probe_candidates.sort(key=lambda x: x[2], reverse=True)
                    top_to_probe = probe_candidates[:max_auto_salary_probes]
                    for idx, (row_id, job, _) in enumerate(top_to_probe, start=1):
                        self.state.message = (
                            f"Estimating hidden Seek salary ({idx}/{len(top_to_probe)}): {job.title[:40]}..."
                        )
                        try:
                            smin, smax = await adapter.probe_salary(job)
                            self.store.update_job_salary(row_id, smin, smax)
                            if smin is not None:
                                job.salary_est_min, job.salary_est_max = smin, smax
                                updated_bd = score_job(job, config)
                                self.store.update_job_score(row_id, updated_bd)
                                summary.salaries_probed += 1
                                self.state.salaries_done = summary.salaries_probed
                        except Exception as exc:
                            err = f"Salary probe failed for {job.external_id}: {exc}"
                            summary.errors.append(err)
                            self.state.errors.append(err)

                summary.finished_at = datetime.now(timezone.utc)
                self.store.finish_scan_run(summary)
                self.state.stage = "done"
                self.state.message = (
                    f"Scan complete — {summary.new_jobs} new, {summary.updated_jobs} updated, "
                    f"{summary.blocked_jobs} blocked."
                )
                self.state.last_summary = summary.model_dump(mode="json")
                return summary
            finally:
                self.state.is_running = False
                await adapter.aclose()

    async def probe_single_job_salary(self, job_id: int) -> dict[str, Any]:
        row = self.store.get_job(job_id)
        if not row:
            raise KeyError(f"Job {job_id} not found")
        config = self.config_mgr.load()
        adapter = get_adapter(row["source"], request_delay_ms=config.request_delay_ms)
        try:
            norm = self._row_to_normalized(row)
            smin, smax = await adapter.probe_salary(norm)
            self.store.update_job_salary(job_id, smin, smax)
            norm.salary_est_min, norm.salary_est_max = smin, smax
            bd = score_job(norm, config)
            self.store.update_job_score(job_id, bd)
            return self.store.get_job(job_id) or row
        finally:
            await adapter.aclose()

    async def enrich_single_job_detail(self, job_id: int) -> dict[str, Any]:
        row = self.store.get_job(job_id)
        if not row:
            raise KeyError(f"Job {job_id} not found")
        config = self.config_mgr.load()
        adapter = get_adapter(row["source"], request_delay_ms=config.request_delay_ms)
        try:
            norm = self._row_to_normalized(row)
            norm = await adapter.enrich_detail(norm)
            bd = score_job(norm, config)
            self.store.upsert_job(norm, bd)
            return self.store.get_job(job_id) or row
        finally:
            await adapter.aclose()

    async def import_job_from_url(
        self,
        url_or_id: str,
        *,
        probe_salary: bool = True,
    ) -> dict[str, Any]:
        """Fetch a single SEEK job by URL/ID (even if not found by search queries), score it, and save to SQLite."""
        config = self.config_mgr.load()
        source_id, external_id = parse_seek_url_or_id(url_or_id, default_board=config.default_board)
        adapter = get_adapter(source_id, request_delay_ms=config.request_delay_ms)
        try:
            existing = self.store.get_existing_job(source_id, external_id)
            if existing:
                norm = self._row_to_normalized(existing)
                norm.url = f"{adapter.job_url_prefix}/{external_id}"
                if "Manual Link" not in norm.matched_queries:
                    norm.matched_queries.append("Manual Link")
            else:
                norm = NormalizedJob(
                    source=source_id,
                    external_id=external_id,
                    url=f"{adapter.job_url_prefix}/{external_id}",
                    title="",
                    company="",
                    matched_queries=["Manual Link"],
                )

            norm = await adapter.enrich_detail(norm)
            bd = score_job(norm, config)
            row_id, _ = self.store.upsert_job(norm, bd)

            if probe_salary and adapter.supports_salary_probe and not bd.blocked_reason and norm.salary_est_min is None:
                try:
                    smin, smax = await adapter.probe_salary(norm)
                    self.store.update_job_salary(row_id, smin, smax)
                    if smin is not None:
                        norm.salary_est_min, norm.salary_est_max = smin, smax
                        bd = score_job(norm, config)
                        self.store.update_job_score(row_id, bd)
                except Exception:
                    pass

            saved = self.store.get_job(row_id)
            if saved and not saved.get("blocked_reason") and saved.get("status") == JobStatus.DISCOVERED.value:
                saved = self.store.update_job_triage(row_id, status=JobStatus.SHORTLISTED.value)
            return saved or self.store.get_job(row_id)  # type: ignore[return-value]
        finally:
            await adapter.aclose()

    def rescore_all_jobs(self, config: ScannerConfig | None = None) -> int:
        cfg = config or self.config_mgr.load()
        rows = self.store.all_jobs_for_rescore()
        for row in rows:
            norm = self._row_to_normalized(row)
            bd = score_job(norm, cfg)
            self.store.update_job_score(row["id"], bd)
        return len(rows)

    @staticmethod
    def _row_to_normalized(row: dict[str, Any]) -> NormalizedJob:
        return NormalizedJob(
            source=row["source"],
            external_id=row["external_id"],
            url=row["url"],
            title=row["title"],
            company=row["company"],
            advertiser_id=row.get("advertiser_id"),
            is_agency=bool(row.get("is_agency")),
            location=row.get("location") or "",
            work_type=row.get("work_type") or "",
            work_arrangement=row.get("work_arrangement") or "",
            classifications=row.get("classifications") or [],
            teaser=row.get("teaser") or "",
            bullet_points=row.get("bullet_points") or [],
            description_html=row.get("description_html") or "",
            description_text=row.get("description_text") or "",
            salary_label=row.get("salary_label") or "",
            salary_est_min=row.get("salary_est_min"),
            salary_est_max=row.get("salary_est_max"),
            posted_at=row.get("posted_at"),
            expires_at=row.get("expires_at"),
            is_link_out=bool(row.get("is_link_out")),
            is_expired=bool(row.get("is_expired")),
            screening_questions=row.get("screening_questions") or [],
            matched_queries=row.get("matched_queries") or [],
        )
