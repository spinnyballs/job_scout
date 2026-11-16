"""Import all built-in job board adapters so they self-register."""
from . import seek as _seek  # noqa: F401
from . import stubs as _stubs  # noqa: F401
from .base import JobBoardAdapter, get_adapter, list_adapters, register_adapter

__all__ = ["JobBoardAdapter", "get_adapter", "list_adapters", "register_adapter"]
