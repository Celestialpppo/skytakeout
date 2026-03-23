"""Write extracted values into xlsx template with trace output."""

from __future__ import annotations

from pathlib import Path

from openpyxl import load_workbook

from app.models.schemas import Candidate, FilledCellTrace, TemplateCellMapping, ValidationResult


class XlsxTemplateFiller:
    """Apply template mappings and produce per-cell trace."""

    def fill(
        self,
        template_path: Path,
        output_path: Path,
        mappings: list[TemplateCellMapping],
        final_candidates: dict[str, Candidate | None],
        validations: dict[str, ValidationResult],
    ) -> list[FilledCellTrace]:
        workbook = load_workbook(template_path)
        traces: list[FilledCellTrace] = []

        for mapping in mappings:
            sheet = workbook[mapping.sheet_name]
            candidate = final_candidates.get(mapping.field_code)
            validation = validations.get(mapping.field_code)
            value = candidate.value if candidate else None

            if mapping.fill_mode == "if_empty" and sheet[mapping.cell].value not in (None, ""):
                notes = "skipped due to if_empty"
            else:
                sheet[mapping.cell] = value
                notes = "filled"

            traces.append(
                FilledCellTrace(
                    template_id=mapping.template_id,
                    sheet_name=mapping.sheet_name,
                    cell=mapping.cell,
                    field_code=mapping.field_code,
                    final_value=value,
                    source_doc_id=candidate.source_doc_id if candidate else None,
                    source_block_id=candidate.source_block_id if candidate else None,
                    extraction_method=candidate.extraction_method if candidate else None,
                    candidate_score=candidate.raw_score if candidate else None,
                    validation_status=validation.status if validation else "warning",
                    notes=notes,
                )
            )

        output_path.parent.mkdir(parents=True, exist_ok=True)
        workbook.save(output_path)
        return traces
