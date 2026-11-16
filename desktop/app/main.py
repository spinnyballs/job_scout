"""FastAPI backend + dashboard entry point."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import logging
import os
import shutil
import socket
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import BackgroundTasks, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from .apply_copilot import ApplyCopilotManager
from .config import ConfigManager
from .llm import get_llm_provider, list_llm_providers
from .models import (
    CandidateProfile,
    JobStatus,
    LLMProviderConfig,
    PhoneSyncRequest,
    QABankEntry,
    ScannerConfig,
)
from .profile import ProfileManager, SecretsManager
from .scanner import ScannerOrchestrator
from .sources import list_adapters
from .storage import JobStore
from .tailor import ARTIFACTS_DIR, ApplicationTailorService

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"
COPILOT_LOG_PATH = BASE_DIR / "data" / "copilot.log"
COPILOT_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)

logger = logging.getLogger("job_scout.copilot")
if not logger.handlers:
    logger.setLevel(logging.INFO)
    _fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    _fh = logging.FileHandler(COPILOT_LOG_PATH, encoding="utf-8")
    _fh.setFormatter(_fmt)
    logger.addHandler(_fh)

store = JobStore()
config_mgr = ConfigManager()
profile_mgr = ProfileManager()
secrets_mgr = SecretsManager()
tailor_svc = ApplicationTailorService()
copilot = ApplyCopilotManager()
orchestrator = ScannerOrchestrator(store=store, config_mgr=config_mgr)


async def _safe_open_dashboard(url: str) -> None:
    try:
        await copilot.open_dashboard_tab(url)
    except Exception:
        logger.exception("Failed to open dashboard inside copilot browser")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if os.environ.get("JOB_SCOUT_OPEN_COPILOT_BROWSER") == "1":
        url = os.environ.get("JOB_SCOUT_URL", "http://127.0.0.1:8765")
        asyncio.create_task(_safe_open_dashboard(url))
    yield
    await copilot.aclose()


app = FastAPI(title="Job Scout — Scan, Tailor & Triage", version="0.3.0", lifespan=lifespan)


@app.middleware("http")
async def disable_static_cache(request: Request, call_next):
    response = await call_next(request)
    if request.url.path == "/" or request.url.path.startswith("/static/"):
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, max-age=0"
    return response


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
app.mount("/artifacts", StaticFiles(directory=str(ARTIFACTS_DIR)), name="artifacts")


class ScanRequest(BaseModel):
    source_id: str | None = None
    query_names: list[str] | None = None
    max_auto_salary_probes: int = 8


class JobPatchRequest(BaseModel):
    status: JobStatus | None = None
    user_notes: str | None = None


class TailorRequest(BaseModel):
    extra_angle: str = ""


class TailorQueueEnqueueRequest(BaseModel):
    job_ids: list[int]
    extra_angle: str = ""


class LLMSettingsPayload(BaseModel):
    llm: LLMProviderConfig
    api_keys: dict[str, str] = {}


class MarkAppliedRequest(BaseModel):
    apply_method: str = "seek_quick_apply"
    status: JobStatus = JobStatus.APPLIED
    note: str = ""


class PrepareApplyRequest(BaseModel):
    ignore_cooldown: bool = False


class ImportJobUrlRequest(BaseModel):
    url: str
    probe_salary: bool = True
    auto_tailor: bool = False
    extra_angle: str = ""


@app.get("/", include_in_schema=False)
async def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/api/sources")
async def api_sources() -> dict[str, Any]:
    return {"sources": list_adapters()}


@app.get("/api/stats")
async def api_stats() -> dict[str, Any]:
    return store.get_stats()


@app.get("/api/scan/status")
async def api_scan_status() -> dict[str, Any]:
    return orchestrator.state.to_dict()


@app.post("/api/scan/start")
async def api_scan_start(req: ScanRequest, background_tasks: BackgroundTasks) -> dict[str, Any]:
    if orchestrator.state.is_running:
        raise HTTPException(status_code=409, detail="Scan already running")

    async def _runner() -> None:
        try:
            await orchestrator.run_scan(
                source_id=req.source_id,
                query_names=req.query_names,
                max_auto_salary_probes=req.max_auto_salary_probes,
            )
        except Exception as exc:
            orchestrator.state.stage = "error"
            orchestrator.state.message = f"Scan failed: {exc}"
            orchestrator.state.errors.append(str(exc))

    background_tasks.add_task(_runner)
    await asyncio.sleep(0.05)
    return {"accepted": True, "state": orchestrator.state.to_dict()}


@app.get("/api/jobs")
async def api_list_jobs(
    status: str | None = None,
    min_score: int | None = None,
    search: str | None = None,
    query_tag: str | None = None,
    include_blocked: bool = False,
    synced_from_phone: bool = False,
    sort: str = "score",
    limit: int = 250,
) -> dict[str, Any]:
    items = store.list_jobs(
        status=status,
        min_score=min_score,
        search=search,
        query_tag=query_tag,
        include_blocked=include_blocked,
        synced_from_phone=synced_from_phone,
        sort=sort,
        limit=limit,
    )
    return {"jobs": items, "count": len(items)}


@app.get("/api/jobs/{job_id}")
async def api_get_job(job_id: int) -> dict[str, Any]:
    job = store.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return job


@app.post("/api/jobs/import-url")
async def api_import_job_url(req: ImportJobUrlRequest) -> dict[str, Any]:
    try:
        job = await orchestrator.import_job_from_url(req.url, probe_salary=req.probe_salary)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Import job URL failed")
        raise HTTPException(status_code=500, detail=f"Failed to import SEEK job: {exc}") from exc

    tailor_error: str | None = None
    if req.auto_tailor and job and not job.get("blocked_reason"):
        try:
            tailor_res = await api_tailor_job(job["id"], TailorRequest(extra_angle=req.extra_angle))
            job = tailor_res["job"]
        except HTTPException as exc:
            tailor_error = str(exc.detail)
        except Exception as exc:
            tailor_error = str(exc)

    return {"job": job, "tailor_error": tailor_error}


@app.patch("/api/jobs/{job_id}")
async def api_patch_job(job_id: int, req: JobPatchRequest) -> dict[str, Any]:
    updated = store.update_job_triage(
        job_id,
        status=req.status.value if req.status else None,
        user_notes=req.user_notes,
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Job not found")
    return updated


@app.post("/api/jobs/{job_id}/probe-salary")
async def api_probe_salary(job_id: int) -> dict[str, Any]:
    try:
        return await orchestrator.probe_single_job_salary(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Salary probe failed: {exc}") from exc


@app.post("/api/jobs/{job_id}/refresh-detail")
async def api_refresh_detail(job_id: int) -> dict[str, Any]:
    try:
        return await orchestrator.enrich_single_job_detail(job_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Detail fetch failed: {exc}") from exc


@app.get("/api/config")
async def api_get_config() -> dict[str, Any]:
    return config_mgr.load().model_dump()


@app.put("/api/config")
async def api_save_config(cfg: ScannerConfig) -> dict[str, Any]:
    saved = config_mgr.save(cfg)
    rescored = orchestrator.rescore_all_jobs(saved)
    return {"config": saved.model_dump(), "rescored_jobs": rescored}


@app.post("/api/rescore")
async def api_rescore() -> dict[str, Any]:
    count = orchestrator.rescore_all_jobs()
    return {"rescored_jobs": count}


# --- Step 2 Endpoints: AI Fit Analysis, CV/Cover Letter Tailoring, Profile, Q&A Bank, AI Providers ---


def _artifact_url(abs_path: str | None) -> str | None:
    if not abs_path:
        return None
    try:
        rel = Path(abs_path).resolve().relative_to(ARTIFACTS_DIR.resolve())
        return "/artifacts/" + rel.as_posix()
    except ValueError:
        return None


@app.post("/api/jobs/{job_id}/analyze-fit")
async def api_analyze_fit(job_id: int) -> dict[str, Any]:
    job = store.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    cfg = config_mgr.load()
    profile = profile_mgr.load()
    try:
        provider = get_llm_provider(cfg.llm, secrets_mgr)
        fit = await tailor_svc.analyze_fit(provider, profile, job)
    except ValueError as exc:
        logger.warning("Fit analysis ValueError for job %s: %s", job_id, exc)
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("LLM fit analysis failed for job %s", job_id)
        raise HTTPException(status_code=500, detail=f"LLM fit analysis failed: {exc}") from exc

    updated = store.save_llm_fit(job_id, fit.model_dump(), fit.fit_score)
    return {"job": updated, "fit": fit.model_dump()}


@app.post("/api/jobs/{job_id}/tailor")
async def api_tailor_job(job_id: int, req: TailorRequest) -> dict[str, Any]:
    job = store.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    cfg = config_mgr.load()
    profile = profile_mgr.load()
    qa_entries = store.list_qa_entries()
    try:
        provider = get_llm_provider(cfg.llm, secrets_mgr)
        result = await tailor_svc.generate_tailored_package(
            provider, profile, job, qa_entries, extra_angle=req.extra_angle
        )
    except ValueError as exc:
        logger.warning("Tailoring ValueError for job %s: %s", job_id, exc)
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Tailoring failed for job %s", job_id)
        raise HTTPException(status_code=500, detail=f"Tailoring failed: {exc}") from exc

    pkg_with_urls = {
        **result["payload"],
        "model_used": result["model_used"],
        "cv_pdf_url": _artifact_url(result["cv_pdf_path"]),
        "cv_html_url": _artifact_url(result["cv_html_path"]),
        "cover_letter_pdf_url": _artifact_url(result["cover_letter_pdf_path"]),
        "cover_letter_html_url": _artifact_url(result["cover_letter_html_path"]),
        "artifact_dir": result["artifact_dir"],
    }
    updated = store.save_tailored_package(
        job_id,
        pkg_with_urls,
        result["cv_pdf_path"],
        result["cover_letter_pdf_path"],
    )
    return {"job": updated, "package": pkg_with_urls}


class TailorQueueManager:
    """Parallel background queue for batch CV + Cover Letter tailoring."""

    MAX_CONCURRENT = 20

    def __init__(self) -> None:
        self.is_running: bool = False
        self.cancel_requested: bool = False
        self.batch_seq: int = 0
        self.active_jobs: dict[int, dict[str, str]] = {}
        self.queued_ids: list[int] = []
        self.angles: dict[int, str] = {}
        self.completed_ids: list[int] = []
        self.failed: list[dict[str, Any]] = []
        self.total: int = 0
        self.message: str = ""
        self.finished_at: str | None = None
        self._tasks: dict[int, asyncio.Task[None]] = {}
        self._sem: asyncio.Semaphore | None = None

    def snapshot(self) -> dict[str, Any]:
        active_ids = list(self.active_jobs.keys())
        first_active = self.active_jobs.get(active_ids[0]) if active_ids else None
        return {
            "is_running": self.is_running,
            "cancel_requested": self.cancel_requested,
            "batch_seq": self.batch_seq,
            "active_ids": active_ids,
            "current_job_id": active_ids[0] if active_ids else None,
            "current_title": first_active["title"] if first_active else "",
            "current_company": first_active["company"] if first_active else "",
            "queued_ids": list(self.queued_ids),
            "completed_ids": list(self.completed_ids),
            "failed": list(self.failed),
            "total": self.total,
            "done_count": len(self.completed_ids) + len(self.failed),
            "message": self.message,
            "finished_at": self.finished_at,
        }

    def _update_running_message(self) -> None:
        done = len(self.completed_ids) + len(self.failed)
        running_n = len(self.active_jobs)
        waiting_n = len(self.queued_ids)
        if running_n == 1:
            info = next(iter(self.active_jobs.values()))
            self.message = f"Tailoring ({done}/{self.total}): {info['title']} @ {info['company']}"
        elif running_n > 1:
            wait_part = f", {waiting_n} waiting" if waiting_n else ""
            self.message = f"Tailoring {running_n} jobs in parallel ({done}/{self.total} done{wait_part})..."
        elif waiting_n:
            self.message = f"Starting {waiting_n} job(s)..."

    def enqueue(self, job_ids: list[int], extra_angle: str = "") -> dict[str, Any]:
        valid_new: list[int] = []
        for jid in job_ids:
            if jid in self.active_jobs or jid in self.queued_ids or jid in self._tasks or jid in valid_new:
                continue
            job = store.get_job(jid)
            if not job or job.get("status") == "blocked" or job.get("blocked_reason"):
                continue
            valid_new.append(jid)
            if extra_angle.strip():
                self.angles[jid] = extra_angle.strip()

        if not valid_new:
            return {**self.snapshot(), "added": 0}

        if not self.is_running:
            self.batch_seq += 1
            self.is_running = True
            self.cancel_requested = False
            self.active_jobs = {}
            self.queued_ids = list(valid_new)
            self.completed_ids = []
            self.failed = []
            self.total = len(valid_new)
            self.finished_at = None
            self._tasks = {}
            self._sem = asyncio.Semaphore(self.MAX_CONCURRENT)
        else:
            self.queued_ids.extend(valid_new)
            self.total += len(valid_new)

        for jid in valid_new:
            task = asyncio.create_task(self._run_job(jid))
            self._tasks[jid] = task

        self._update_running_message()
        return {**self.snapshot(), "added": len(valid_new)}

    def cancel(self) -> dict[str, Any]:
        if not self.is_running:
            return self.snapshot()
        self.cancel_requested = True
        cancelled_waiting = set(self.queued_ids)
        self.queued_ids.clear()
        for jid in cancelled_waiting:
            t = self._tasks.get(jid)
            if t and not t.done():
                t.cancel()
        self.message = "Stopping (finishing in-flight jobs)..."
        return self.snapshot()

    async def _run_job(self, jid: int) -> None:
        assert self._sem is not None
        title = f"Job #{jid}"
        company = ""
        try:
            async with self._sem:
                if self.cancel_requested:
                    return
                if jid in self.queued_ids:
                    self.queued_ids.remove(jid)
                job = store.get_job(jid)
                if not job:
                    return
                title = job.get("title") or title
                company = job.get("company") or ""
                self.active_jobs[jid] = {"title": title, "company": company}
                self._update_running_message()

                angle = self.angles.pop(jid, "")
                cfg = config_mgr.load()
                profile = profile_mgr.load()
                qa_entries = store.list_qa_entries()
                provider = get_llm_provider(cfg.llm, secrets_mgr)

                try:
                    result = await tailor_svc.generate_tailored_package(
                        provider, profile, job, qa_entries, extra_angle=angle
                    )
                except Exception as first_exc:
                    err_s = str(first_exc).lower()
                    if "429" in err_s or "resource_exhausted" in err_s or "rate" in err_s:
                        await asyncio.sleep(2.5)
                        result = await tailor_svc.generate_tailored_package(
                            provider, profile, job, qa_entries, extra_angle=angle
                        )
                    else:
                        raise

                pkg_with_urls = {
                    **result["payload"],
                    "model_used": result["model_used"],
                    "cv_pdf_url": _artifact_url(result["cv_pdf_path"]),
                    "cv_html_url": _artifact_url(result["cv_html_path"]),
                    "cover_letter_pdf_url": _artifact_url(result["cover_letter_pdf_path"]),
                    "cover_letter_html_url": _artifact_url(result["cover_letter_html_path"]),
                    "artifact_dir": result["artifact_dir"],
                }
                store.save_tailored_package(
                    jid,
                    pkg_with_urls,
                    result["cv_pdf_path"],
                    result["cover_letter_pdf_path"],
                )
                self.completed_ids.append(jid)
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            logger.exception("Tailor queue failed for job %s", jid)
            self.failed.append({
                "job_id": jid,
                "title": title,
                "company": company,
                "error": str(exc),
            })
        finally:
            if jid in self.queued_ids:
                self.queued_ids.remove(jid)
            self.active_jobs.pop(jid, None)
            self._tasks.pop(jid, None)
            if not self._tasks and not self.active_jobs and not self.queued_ids:
                self.is_running = False
                self.finished_at = datetime.now(timezone.utc).isoformat()
                ok_n = len(self.completed_ids)
                fail_n = len(self.failed)
                if self.cancel_requested:
                    self.message = f"Queue stopped ({ok_n} tailored, {fail_n} failed)."
                elif fail_n:
                    self.message = f"Finished batch: {ok_n} tailored, {fail_n} failed."
                else:
                    self.message = f"All {ok_n} tailored CV & Cover Letter package(s) are ready!"
            else:
                self._update_running_message()


tailor_queue = TailorQueueManager()


@app.get("/api/tailor-queue/status")
async def api_tailor_queue_status() -> dict[str, Any]:
    return tailor_queue.snapshot()


@app.post("/api/tailor-queue/enqueue")
async def api_tailor_queue_enqueue(req: TailorQueueEnqueueRequest) -> dict[str, Any]:
    cfg = config_mgr.load()
    try:
        get_llm_provider(cfg.llm, secrets_mgr)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return tailor_queue.enqueue(req.job_ids, extra_angle=req.extra_angle)


@app.post("/api/tailor-queue/cancel")
async def api_tailor_queue_cancel() -> dict[str, Any]:
    return tailor_queue.cancel()


@app.post("/api/jobs/{job_id}/reset-tailored")
async def api_reset_tailored(job_id: int) -> dict[str, Any]:
    job = store.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    for p in (job.get("cv_pdf_path"), job.get("cover_letter_pdf_path")):
        if p:
            try:
                Path(p).unlink(missing_ok=True)
            except OSError:
                pass
    pkg = job.get("tailored_package") or {}
    art_dir = pkg.get("artifact_dir")
    if art_dir:
        try:
            shutil.rmtree(art_dir, ignore_errors=True)
        except OSError:
            pass
    updated = store.clear_tailored_package(job_id)
    return {"job": updated}


@app.get("/api/profile")
async def api_get_profile() -> dict[str, Any]:
    return profile_mgr.load().model_dump()


@app.put("/api/profile")
async def api_save_profile(profile: CandidateProfile) -> dict[str, Any]:
    saved = profile_mgr.save(profile)
    return saved.model_dump()


@app.post("/api/profile/audit")
async def api_audit_profile() -> dict[str, Any]:
    cfg = config_mgr.load()
    profile = profile_mgr.load()
    recent = store.list_jobs(limit=15)
    try:
        provider = get_llm_provider(cfg.llm, secrets_mgr)
        suggestions = await tailor_svc.audit_profile(provider, profile, recent)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Profile audit failed: {exc}") from exc
    return suggestions.model_dump()


@app.get("/api/qa-bank")
async def api_list_qa() -> dict[str, Any]:
    return {"entries": [e.model_dump() for e in store.list_qa_entries()]}


@app.post("/api/qa-bank")
async def api_upsert_qa(entry: QABankEntry) -> dict[str, Any]:
    saved = store.upsert_qa_entry(entry)
    return saved.model_dump()


@app.delete("/api/qa-bank/{entry_id}")
async def api_delete_qa(entry_id: int) -> dict[str, Any]:
    store.delete_qa_entry(entry_id)
    return {"deleted": entry_id}


@app.get("/api/llm/settings")
async def api_get_llm_settings() -> dict[str, Any]:
    cfg = config_mgr.load()
    key_names = [
        cfg.llm.gemini_api_key_env,
        cfg.llm.deepseek_api_key_env,
        cfg.llm.openai_api_key_env,
        cfg.llm.anthropic_api_key_env,
        *[p.api_key_env for p in cfg.llm.custom_presets],
    ]
    return {
        "llm": cfg.llm.model_dump(),
        "providers": list_llm_providers(cfg.llm),
        "key_status": secrets_mgr.status(list(dict.fromkeys(key_names))),
    }


@app.put("/api/llm/settings")
async def api_save_llm_settings(payload: LLMSettingsPayload) -> dict[str, Any]:
    cfg = config_mgr.load()
    cfg.llm = payload.llm
    config_mgr.save(cfg)
    if payload.api_keys:
        secrets_mgr.set_keys(payload.api_keys)
    return await api_get_llm_settings()


def _detect_lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


@app.get("/api/sync/info")
async def api_sync_info(request: Request) -> dict[str, Any]:
    port = request.url.port or 8765
    ips = _detect_lan_ips()
    stats = store.get_stats()
    return {
        "lan_ips": ips,
        "sync_urls": [f"http://{ip}:{port}" for ip in ips],
        "port": port,
        "synced_from_phone": stats.get("synced_from_phone", 0),
        "applied_count": len(store.get_applied_keys()),
    }


@app.post("/api/sync/phone")
async def api_sync_phone(req: PhoneSyncRequest) -> dict[str, Any]:
    if req.profile is not None and req.overwrite_profile:
        profile_mgr.save(req.profile)
    sync_res = store.sync_phone_jobs(req.jobs)
    return {
        "ok": True,
        "profile_overwritten": bool(req.profile is not None and req.overwrite_profile),
        "server_profile": profile_mgr.load().model_dump(),
        **sync_res,
    }


# --- Step 3A Endpoints: Playwright Assisted-Apply Copilot & Application Ledger ---


@app.get("/api/copilot/status")
async def api_copilot_status(verify_live: bool = False) -> dict[str, Any]:
    try:
        return await copilot.check_seek_session(verify_live=verify_live)
    except Exception as exc:
        logger.exception("Copilot status check failed")
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc


@app.post("/api/copilot/login")
async def api_copilot_login() -> dict[str, Any]:
    try:
        return await copilot.open_seek_login_page()
    except Exception as exc:
        logger.exception("Copilot SEEK login failed")
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc


@app.post("/api/copilot/logout")
async def api_copilot_logout() -> dict[str, Any]:
    try:
        return await copilot.logout_seek()
    except Exception as exc:
        logger.exception("Copilot SEEK logout failed")
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc


@app.post("/api/jobs/{job_id}/prepare-apply")
async def api_prepare_apply(job_id: int, req: PrepareApplyRequest | None = None) -> dict[str, Any]:
    job = store.get_job(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    if job.get("status") == "blocked" or job.get("blocked_reason"):
        raise HTTPException(status_code=403, detail=f"Blocked by employer guardrail: {job.get('blocked_reason')}")

    cfg = config_mgr.load()
    ignore_cd = req.ignore_cooldown if req else False
    recent = store.recent_company_applications(job["company"], cooldown_days=cfg.company_cooldown_days)
    recent_other = [r for r in recent if r["id"] != job_id]
    if recent_other and not ignore_cd:
        return {
            "cooldown_warning": True,
            "recent_applications": recent_other,
            "message": (
                f"You already applied to {job['company']} within the last {cfg.company_cooldown_days} days "
                f"({recent_other[0]['title']}). Confirm override to proceed anyway."
            ),
        }

    pkg = job.get("tailored_package") or {}
    qa_entries = store.list_qa_entries()
    cv_pdf = job.get("cv_pdf_path") or None
    cl_pdf = job.get("cover_letter_pdf_path") or None

    if not cv_pdf:
        generic_pdf = Path(BASE_DIR).parent / "CV_Generic.pdf"
        if generic_pdf.exists():
            cv_pdf = str(generic_pdf)

    try:
        session = await copilot.prepare_application(
            job=job,
            qa_entries=qa_entries,
            cv_pdf_path=cv_pdf,
            cover_letter_pdf_path=cl_pdf,
            cover_letter_text=pkg.get("cover_letter_body", ""),
            drafted_answers=pkg.get("suggested_screening_answers", {}),
        )
    except Exception as exc:
        logger.exception("Copilot prepare_application failed for job %s", job_id)
        raise HTTPException(status_code=500, detail=f"{type(exc).__name__}: {exc}") from exc
    return {"cooldown_warning": False, "session": session}


@app.post("/api/jobs/{job_id}/mark-applied")
async def api_mark_applied(job_id: int, req: MarkAppliedRequest) -> dict[str, Any]:
    updated = store.mark_applied(
        job_id,
        apply_method=req.apply_method,
        status=req.status.value,
        log_note=req.note,
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Job not found")
    return updated
