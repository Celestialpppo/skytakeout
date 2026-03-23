"""Candidate scoring and arbitration."""

from __future__ import annotations

from collections import Counter

from app.models.schemas import Candidate, FieldDefinition


class CandidateScorer:
    """Score candidates with deterministic weighted signals."""

    method_bonus = {
        "kv_rule": 0.1,
        "alias_neighbor": 0.05,
        "regex_phone": 0.05,
    }

    def score(self, field: FieldDefinition, candidates: list[Candidate]) -> list[Candidate]:
        if not candidates:
            return []

        value_counts = Counter(c.value for c in candidates)
        scored: list[Candidate] = []
        for candidate in candidates:
            breakdown = dict(candidate.score_breakdown)
            breakdown["method_bonus"] = self.method_bonus.get(candidate.extraction_method, 0.0)
            breakdown["consistency"] = min(0.2, 0.05 * (value_counts[candidate.value] - 1))
            candidate.raw_score = sum(breakdown.values())
            candidate.score_breakdown = breakdown
            scored.append(candidate)

        scored.sort(key=lambda x: x.raw_score, reverse=True)
        return scored

    def pick_best(self, field: FieldDefinition, candidates: list[Candidate]) -> Candidate | None:
        ranked = self.score(field, candidates)
        return ranked[0] if ranked else None
