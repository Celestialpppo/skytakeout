# ARCHITECTURE

系统采用字段中心型流程：

`parse -> extract -> score -> validate -> fill -> export`

- `parsers`: docx/md/xlsx/txt 统一解析为 `ParsedDocument + Block`。
- `extractors`: 按字段别名、正则、键值关系召回候选。
- `scorers`: 对候选做加权评分并选取最高置信。
- `validators`: 正规化并校验必填、格式、类型。
- `fillers`: 根据模板映射写入 xlsx，并输出 `FilledCellTrace`。
- `services`: 流程编排。
