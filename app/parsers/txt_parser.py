"""TXT parser implementation."""

from __future__ import annotations

from pathlib import Path

from app.models.schemas import Block, BlockType, ParsedDocument
from app.parsers.base import BaseParser


class TextParser(BaseParser):
    """Parse txt by non-empty lines."""

    def parse(self, file_path: Path, doc_id: str | None = None) -> ParsedDocument:
        text = file_path.read_text(encoding="utf-8")
        blocks = [
            Block(
                block_id=f"txt-{idx}",
                block_type=BlockType.RAW_TEXT,
                text=line.strip(),
                metadata={"line": idx + 1},
            )
            for idx, line in enumerate(text.splitlines())
            if line.strip()
        ]

        return ParsedDocument(
            doc_id=doc_id or file_path.stem,
            source_path=file_path,
            doc_type="txt",
            blocks=blocks,
        )
