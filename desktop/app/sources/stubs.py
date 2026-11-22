"""Future adapter slots demonstrating how additional job boards plug into the pipeline."""
from __future__ import annotations

from typing import AsyncIterator

from ..models import NormalizedJob, SearchQueryConfig
from .base import JobBoardAdapter, register_adapter


@register_adapter
class TradeMeJobsStubAdapter(JobBoardAdapter):
    source_id = "trademe_nz"
    display_name = "Trade Me Jobs (NZ) — Planned Slot"
    region = "NZ"
    supports_salary_probe = False
    is_ready = False

    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        raise NotImplementedError("Trade Me adapter slot reserved for Phase 1b")
        yield  # pragma: no cover

    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        return job


@register_adapter
class LinkedInStubAdapter(JobBoardAdapter):
    source_id = "linkedin"
    display_name = "LinkedIn Jobs (CDP Browser) — Planned Slot"
    region = "Global"
    supports_salary_probe = False
    is_ready = False

    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        raise NotImplementedError("LinkedIn CDP adapter slot reserved for Phase 1b")
        yield  # pragma: no cover

    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        return job


@register_adapter
class DirectATSStubAdapter(JobBoardAdapter):
    source_id = "direct_ats"
    display_name = "Direct Company ATS (Greenhouse / Lever / Workday) — Planned Slot"
    region = "Global"
    supports_salary_probe = False
    is_ready = False

    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        raise NotImplementedError("Direct ATS adapter slot reserved for Phase 1b")
        yield  # pragma: no cover

    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        return job


@register_adapter
class ManualPasteAdapter(JobBoardAdapter):
    source_id = "manual"
    display_name = "Pasted JD / Other Sites"
    region = "Any"
    supports_salary_probe = False
    is_ready = False

    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        raise NotImplementedError("Manual pasted jobs do not run scheduled scans")
        yield  # pragma: no cover

    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        return job

