"""Core schema models for field-centric extraction pipeline."""

from __future__ import annotations

from enum import Enum
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field


class BlockType(str, Enum):
    """Supported normalized block types."""

    PARAGRAPH = "paragraph"
    HEADING = "heading"
    TABLE_CELL = "table_cell"
    KV_PAIR = "kv_pair"
    RAW_TEXT = "raw_text"


class Block(BaseModel):
    """A normalized text unit extracted from any input document."""

    block_id: str
    block_type: BlockType = BlockType.RAW_TEXT
    text: str = ""
    metadata: dict[str, Any] = Field(default_factory=dict)


class ParsedDocument(BaseModel):
    """Unified parsed document representation."""

    doc_id: str
    source_path: Path
    doc_type: Literal["docx", "md", "xlsx", "txt"]
    blocks: list[Block] = Field(default_factory=list)


class FieldDefinition(BaseModel):
    """Schema-first field definition loaded from YAML."""

    field_code: str
    field_name: str
    aliases: list[str] = Field(default_factory=list)
    description: str = ""
    data_type: Literal["string", "number", "date", "enum"] = "string"
    required: bool = False
    default_value: Any | None = None
    normalizer: str = "identity"
    validator: str = "basic"
    priority_sources: list[str] = Field(default_factory=list)
    examples: list[str] = Field(default_factory=list)


class Candidate(BaseModel):
    """Candidate value recalled for one field."""

    field_code: str
    value: str
    source_doc_id: str
    source_block_id: str
    extraction_method: str
    raw_score: float = 0.0
    score_breakdown: dict[str, float] = Field(default_factory=dict)
    metadata: dict[str, Any] = Field(default_factory=dict)


class TemplateCellMapping(BaseModel):
    """Mapping from field to one template cell."""

    template_id: str
    sheet_name: str
    cell: str
    field_code: str
    required: bool = False
    fill_mode: Literal["overwrite", "if_empty"] = "overwrite"
    postprocess: str = "identity"


class ValidationResult(BaseModel):
    """Validation result for a final field value."""

    valid: bool
    status: Literal["ok", "warning", "error"] = "ok"
    message: str = ""


class FilledCellTrace(BaseModel):
    """Trace for one output cell to ensure auditability."""

    template_id: str
    sheet_name: str
    cell: str
    field_code: str
    final_value: Any
    source_doc_id: str | None = None
    source_block_id: str | None = None
    extraction_method: str | None = None
    candidate_score: float | None = None
    validation_status: Literal["ok", "warning", "error"] = "ok"
    notes: str = ""
