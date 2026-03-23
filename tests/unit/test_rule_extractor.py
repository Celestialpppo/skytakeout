from pathlib import Path

from app.extractors.rule_extractor import RuleBasedExtractor
from app.models.schemas import Block, BlockType, FieldDefinition, ParsedDocument


def test_rule_extractor_alias_and_regex():
    field_name = FieldDefinition(
        field_code="applicant_name",
        field_name="申报人",
        aliases=["申报人"],
        description="",
        data_type="string",
        required=True,
        normalizer="strip",
        validator="non_empty",
    )
    field_phone = FieldDefinition(
        field_code="contact_phone",
        field_name="联系电话",
        aliases=["联系电话"],
        description="",
        data_type="string",
        required=False,
        normalizer="phone_cn",
        validator="phone_cn",
    )
    doc = ParsedDocument(
        doc_id="d1",
        source_path=Path("demo.txt"),
        doc_type="txt",
        blocks=[
            Block(block_id="b1", block_type=BlockType.RAW_TEXT, text="申报人：张三"),
            Block(block_id="b2", block_type=BlockType.RAW_TEXT, text="联系电话 13800138000"),
        ],
    )

    recalls = RuleBasedExtractor().extract([field_name, field_phone], [doc])
    assert recalls["applicant_name"][0].value == "张三"
    assert recalls["contact_phone"][0].value == "13800138000"
