"""Evaluation scaffold for accuracy and runtime metrics."""

from __future__ import annotations

import json
import time
from pathlib import Path

import typer

app = typer.Typer()


@app.command()
def main(prediction_json: Path = typer.Option(...), gold_json: Path = typer.Option(...)) -> None:
    start = time.perf_counter()
    pred = json.loads(prediction_json.read_text(encoding="utf-8"))
    gold = json.loads(gold_json.read_text(encoding="utf-8"))

    matched = 0
    total = len(gold)
    for field_code, gold_value in gold.items():
        if str(pred.get(field_code, "")).strip() == str(gold_value).strip():
            matched += 1

    accuracy = matched / total if total else 0.0
    elapsed = time.perf_counter() - start
    typer.echo(json.dumps({"accuracy": accuracy, "elapsed_seconds": elapsed}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    app()
