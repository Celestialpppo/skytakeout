"""FastAPI app entry for pipeline trigger."""

from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel

from app.services.pipeline import ExtractionPipelineService

app = FastAPI(title="Competition Extraction Service")


class RunRequest(BaseModel):
    input_files: list[str]
    fields_yaml: str
    mapping_yaml: str
    template_path: str
    output_path: str


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/run")
def run_pipeline(req: RunRequest) -> dict:
    pipeline = ExtractionPipelineService()
    traces, candidates, validations = pipeline.run(
        inputs=[Path(p) for p in req.input_files],
        fields_yaml=Path(req.fields_yaml),
        mapping_yaml=Path(req.mapping_yaml),
        template_path=Path(req.template_path),
        output_path=Path(req.output_path),
    )
    return {
        "traces": [t.model_dump() for t in traces],
        "candidates": {k: v.model_dump() if v else None for k, v in candidates.items()},
        "validations": {k: v.model_dump() for k, v in validations.items()},
    }
