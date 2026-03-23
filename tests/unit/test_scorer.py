from app.models.schemas import Candidate, FieldDefinition
from app.scorers.candidate_scorer import CandidateScorer


def test_candidate_scorer_pick_best_by_consistency():
    field = FieldDefinition(
        field_code="project_name",
        field_name="项目名称",
        aliases=["项目名称"],
        description="",
        data_type="string",
        required=True,
        normalizer="strip",
        validator="non_empty",
    )
    candidates = [
        Candidate(
            field_code="project_name",
            value="A项目",
            source_doc_id="d1",
            source_block_id="b1",
            extraction_method="alias_neighbor",
            score_breakdown={"alias": 0.6},
        ),
        Candidate(
            field_code="project_name",
            value="A项目",
            source_doc_id="d2",
            source_block_id="b2",
            extraction_method="alias_neighbor",
            score_breakdown={"alias": 0.6},
        ),
        Candidate(
            field_code="project_name",
            value="B项目",
            source_doc_id="d3",
            source_block_id="b3",
            extraction_method="kv_rule",
            score_breakdown={"kv": 0.75},
        ),
    ]

    best = CandidateScorer().pick_best(field, candidates)
    assert best is not None
    assert best.value == "B项目" or best.value == "A项目"
