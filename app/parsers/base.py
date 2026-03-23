"""Parsers convert source files into ParsedDocument with normalized blocks."""

from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path

from app.models.schemas import ParsedDocument


class BaseParser(ABC):
    """Base parser contract."""

    @abstractmethod
    def parse(self, file_path: Path, doc_id: str | None = None) -> ParsedDocument:
        """Parse one file into unified representation."""
