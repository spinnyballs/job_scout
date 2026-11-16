"""Pluggable job board adapter interface and registry."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, AsyncIterator

from ..models import NormalizedJob, SearchQueryConfig


class JobBoardAdapter(ABC):
    """Contract that every job board scraper/connector implements.

    Leaving Seek-specific quirks inside `SeekAdapter` allows future adapters
    (Trade Me Jobs, LinkedIn, Indeed, Greenhouse/Lever direct ATS) to plug into
    the exact same scanner, SQLite store, CV tailor, and review dashboard.
    """

    source_id: str = ""
    display_name: str = ""
    region: str = ""
    supports_salary_probe: bool = False
    is_ready: bool = True

    def __init__(self, request_delay_ms: int = 250) -> None:
        self.request_delay_ms = request_delay_ms

    @abstractmethod
    async def search(self, query: SearchQueryConfig) -> AsyncIterator[NormalizedJob]:
        """Yield NormalizedJob items matching a search query."""
        raise NotImplementedError
        yield  # pragma: no cover

    @abstractmethod
    async def enrich_detail(self, job: NormalizedJob) -> NormalizedJob:
        """Fetch full job description, screening questions, and apply mode."""
        raise NotImplementedError

    async def probe_salary(self, job: NormalizedJob) -> tuple[int | None, int | None]:
        """Optional: Estimate hidden salary band (min, max) if supported by the board."""
        return (job.salary_est_min, job.salary_est_max)

    async def aclose(self) -> None:
        """Release any underlying HTTP/browser sessions."""
        return None


_ADAPTER_REGISTRY: dict[str, type[JobBoardAdapter]] = {}


def register_adapter(cls: type[JobBoardAdapter]) -> type[JobBoardAdapter]:
    if not cls.source_id:
        raise ValueError(f"Adapter {cls.__name__} must define source_id")
    _ADAPTER_REGISTRY[cls.source_id] = cls
    return cls


def get_adapter(source_id: str, *, request_delay_ms: int = 250) -> JobBoardAdapter:
    if source_id not in _ADAPTER_REGISTRY:
        available = ", ".join(sorted(_ADAPTER_REGISTRY))
        raise KeyError(f"Unknown job source '{source_id}'. Available: {available}")
    cls = _ADAPTER_REGISTRY[source_id]
    if not cls.is_ready:
        raise RuntimeError(f"Adapter '{source_id}' ({cls.display_name}) is a stub slot and not yet enabled.")
    return cls(request_delay_ms=request_delay_ms)


def list_adapters() -> list[dict[str, Any]]:
    return [
        {
            "source_id": cls.source_id,
            "display_name": cls.display_name,
            "region": cls.region,
            "supports_salary_probe": cls.supports_salary_probe,
            "is_ready": cls.is_ready,
        }
        for cls in _ADAPTER_REGISTRY.values()
    ]
