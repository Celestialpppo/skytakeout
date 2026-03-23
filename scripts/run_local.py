"""Run local demo pipeline for competition-oriented extraction."""

from __future__ import annotations

from pathlib import Path

import typer

from app.services.pipeline import ExtractionPipelineService

app = typer.Typer()


@app.command()
def main(
    input_dir: Path = typer.Option(..., help="Directory containing docx/md/xlsx/txt files"),
    fields_yaml: Path = typer.Option(Path("config/fields.yaml")),
    mapping_yaml: Path = typer.Option(Path("config/template_mappings/demo_template.yaml")),
    template_path: Path = typer.Option(..., help="Excel template path"),
    output_path: Path = typer.Option(Path("outputs/filled_demo.xlsx")),
) -> None:
    files = [p for p in input_dir.iterdir() if p.suffix.lower() in {".docx", ".md", ".xlsx", ".txt"}]
    pipeline = ExtractionPipelineService()
    traces, candidates, validations = pipeline.run(
        inputs=files,
        fields_yaml=fields_yaml,
        mapping_yaml=mapping_yaml,
        template_path=template_path,
        output_path=output_path,
    )

    typer.echo(f"Processed files: {len(files)}")
    for field_code, candidate in candidates.items():
        status = validations[field_code].status
        typer.echo(f"{field_code}: {candidate.value if candidate else None} ({status})")
    typer.echo(f"Output written: {output_path}")
    typer.echo(f"Trace count: {len(traces)}")


if __name__ == "__main__":
    app()
