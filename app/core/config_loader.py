"""YAML based configuration loaders."""

from __future__ import annotations

from pathlib import Path

import yaml

from app.models.schemas import FieldDefinition, TemplateCellMapping


def _load_yaml(path: Path) -> dict:
    if not path.exists():
        raise FileNotFoundError(f"Config not found: {path}")
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return data or {}


def load_field_definitions(path: Path) -> list[FieldDefinition]:
    """Load field schema from fields.yaml."""
    content = _load_yaml(path)
    fields = content.get("fields", [])
    return [FieldDefinition.model_validate(item) for item in fields]


def load_template_mappings(path: Path) -> list[TemplateCellMapping]:
    """Load template cell mappings from YAML."""
    content = _load_yaml(path)
    mappings = content.get("mappings", [])
    return [TemplateCellMapping.model_validate(item) for item in mappings]
