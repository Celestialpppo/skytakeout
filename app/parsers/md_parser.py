"""Markdown parser implementation."""

from __future__ import annotations

from pathlib import Path

from app.models.schemas import Block, BlockType, ParsedDocument
from app.parsers.base import BaseParser


class MarkdownParser(BaseParser):
    """Parse markdown by line-level blocks with heading detection."""

    def parse(self, file_path: Path, doc_id: str | None = None) -> ParsedDocument:
        text = file_path.read_text(encoding="utf-8")
        blocks: list[Block] = []
        for idx, line in enumerate(text.splitlines()):
            clean = line.strip()
            if not clean:
                continue
            block_type = BlockType.HEADING if clean.startswith("#") else BlockType.PARAGRAPH
            blocks.append(
                Block(
                    block_id=f"md-{idx}",
                    block_type=block_type,
                    text=clean,
                    metadata={"line": idx + 1},
                )
            )

        return ParsedDocument(
            doc_id=doc_id or file_path.stem,
            source_path=file_path,
            doc_type="md",
            blocks=blocks,
        )
