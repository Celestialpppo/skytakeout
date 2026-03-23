# RUNBOOK

## 运行

```bash
python -m pip install -r requirements.txt
python scripts/run_local.py --input-dir tests/fixtures/demo_inputs --template-path tests/fixtures/demo_template.xlsx
```

## 测试

```bash
pytest -q
```

## 已知限制

- 当前仅规则抽取（别名/正则/邻域），未启用 LLM 兜底。
- 跨字段一致性校验尚未扩展。
- 模板映射目前为手工配置或脚手架生成。
