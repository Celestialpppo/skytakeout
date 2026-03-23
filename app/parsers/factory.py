"""Parser factory by extension."""

from __future__ import annotations

from pathlib import Path

from app.parsers.base import BaseParser
from app.parsers.docx_parser import DocxParser
from app.parsers.md_parser import MarkdownParser
from app.parsers.txt_parser import TextParser
from app.parsers.xlsx_parser import XlsxParser


class ParserFactory:
    """Resolve parser instance by file extension."""

    _registry: dict[str, BaseParser] = {
        ".docx": DocxParser(),
        ".md": MarkdownParser(),
        ".txt": TextParser(),
        ".xlsx": XlsxParser(),
    }

    @classmethod
    def get_parser(cls, file_path: Path) -> BaseParser:
        parser = cls._registry.get(file_path.suffix.lower())
        if parser is None:
            raise ValueError(f"Unsupported file type: {file_path.suffix}")
        return parser
