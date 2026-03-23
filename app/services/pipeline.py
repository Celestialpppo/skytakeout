"""End-to-end orchestration pipeline for extraction and filling."""

from __future__ import annotations

from pathlib import Path

from app.core.config_loader import load_field_definitions, load_template_mappings
from app.extractors.rule_extractor import RuleBasedExtractor
from app.fillers.xlsx_filler import XlsxTemplateFiller
from app.models.schemas import Candidate, FilledCellTrace, ValidationResult
from app.parsers.factory import ParserFactory
from app.scorers.candidate_scorer import CandidateScorer
from app.validators.field_validator import BasicFieldValidator


class ExtractionPipelineService:
    """Compose parse → extract → score → validate → fill workflow."""

    def __init__(self) -> None:
        self.extractor = RuleBasedExtractor()
        self.scorer = CandidateScorer()
        self.validator = BasicFieldValidator()
        self.filler = XlsxTemplateFiller()

    def run(
        self,
        inputs: list[Path],
        fields_yaml: Path,
        mapping_yaml: Path,
        template_path: Path,
        output_path: Path,
    ) -> tuple[list[FilledCellTrace], dict[str, Candidate | None], dict[str, ValidationResult]]:
        fields = load_field_definitions(fields_yaml)
        mappings = load_template_mappings(mapping_yaml)

        parsed_docs = []
        for file_path in inputs:
            parser = ParserFactory.get_parser(file_path)
            parsed_docs.append(parser.parse(file_path))

        recalls = self.extractor.extract(fields, parsed_docs)
        final_candidates: dict[str, Candidate | None] = {}
        validations: dict[str, ValidationResult] = {}

        for field in fields:
            candidate = self.scorer.pick_best(field, recalls.get(field.field_code, []))
            if candidate is None and field.default_value is not None:
                candidate = Candidate(
                    field_code=field.field_code,
                    value=str(field.default_value),
                    source_doc_id="__default__",
                    source_block_id="__default__",
                    extraction_method="default",
                    raw_score=0.01,
                )
            if candidate is not None:
                candidate.value = self.validator.normalize(field, candidate.value)

            final_candidates[field.field_code] = candidate
            validations[field.field_code] = self.validator.validate(field, candidate.value if candidate else None)

        traces = self.filler.fill(
            template_path=template_path,
            output_path=output_path,
            mappings=mappings,
            final_candidates=final_candidates,
            validations=validations,
        )
        return traces, final_candidates, validations
