"""Rule-first field candidate extractor."""

from __future__ import annotations

import re
from collections import defaultdict

from app.models.schemas import Block, Candidate, FieldDefinition, ParsedDocument


class RuleBasedExtractor:
    """Recall candidates by alias, key-value relation and regex hints."""

    _phone_pattern = re.compile(r"(?<!\d)(1\d{10})(?!\d)")

    def extract(self, fields: list[FieldDefinition], docs: list[ParsedDocument]) -> dict[str, list[Candidate]]:
        recalls: dict[str, list[Candidate]] = defaultdict(list)
        for field in fields:
            for doc in docs:
                for block in doc.blocks:
                    recalls[field.field_code].extend(self._extract_from_block(field, doc.doc_id, block))
        return recalls

    def _extract_from_block(self, field: FieldDefinition, doc_id: str, block: Block) -> list[Candidate]:
        results: list[Candidate] = []
        text = block.text.strip()

        for alias in field.aliases:
            if alias in text:
                value = self._extract_neighbor_value(text, alias)
                if value:
                    results.append(
                        Candidate(
                            field_code=field.field_code,
                            value=value,
                            source_doc_id=doc_id,
                            source_block_id=block.block_id,
                            extraction_method="alias_neighbor",
                            score_breakdown={"alias": 0.6},
                            metadata={"alias": alias},
                        )
                    )

        if field.data_type == "string" and ":" in text:
            key, value = [part.strip() for part in text.split(":", 1)]
            if any(alias == key for alias in field.aliases) and value:
                results.append(
                    Candidate(
                        field_code=field.field_code,
                        value=value,
                        source_doc_id=doc_id,
                        source_block_id=block.block_id,
                        extraction_method="kv_rule",
                        score_breakdown={"kv": 0.75},
                    )
                )

        if field.validator == "phone_cn":
            match = self._phone_pattern.search(text)
            if match:
                results.append(
                    Candidate(
                        field_code=field.field_code,
                        value=match.group(1),
                        source_doc_id=doc_id,
                        source_block_id=block.block_id,
                        extraction_method="regex_phone",
                        score_breakdown={"regex": 0.7},
                    )
                )

        return results

    @staticmethod
    def _extract_neighbor_value(text: str, alias: str) -> str:
        if alias not in text:
            return ""

        rest = text.split(alias, 1)[1].strip()
        for sep in [":", "：", "=", "是"]:
            if rest.startswith(sep):
                rest = rest[len(sep) :].strip()
                break
        return rest[:128].strip()
