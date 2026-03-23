from pathlib import Path

from app.core.config_loader import load_field_definitions, load_template_mappings


def test_load_field_definitions():
    fields = load_field_definitions(Path("config/fields.yaml"))
    assert len(fields) >= 3
    assert fields[0].field_code == "project_name"


def test_load_template_mappings():
    mappings = load_template_mappings(Path("config/template_mappings/demo_template.yaml"))
    assert len(mappings) >= 3
    assert mappings[0].cell == "B2"
