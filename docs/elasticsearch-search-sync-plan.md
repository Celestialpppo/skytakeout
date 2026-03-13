# Elasticsearch 搜索与同步方案（实现对齐版）

## 1. 目标

在苍穹外卖中新增统一搜索能力，并让 MySQL 的菜品、套餐、分类变更持续同步到 Elasticsearch。

本方案实现范围：

- 搜索对象：`dish` + `setmeal`
- 搜索能力：关键词、分页、类型过滤、分类过滤
- 同步链路：全量重建 + Canal 增量采集 + RabbitMQ 解耦 + 消费端回查 MySQL 后写 ES

## 2. 关键设计

1. 统一索引：`goods_search`
2. 文档主键：`bizType:sourceId`
3. 增量消息只放最小字段：表名、操作类型、主键、事件时间
4. 消费端不直接信任消息体，回查 MySQL 最新状态后更新索引
5. 同步链路支持死信队列，避免异常时静默丢消息

## 3. 代码模块落点

1. 搜索配置与 RabbitMQ 配置：`com.sky.config`
2. 搜索读链路：`UserSearchController + GoodsSearchServiceImpl`
3. 同步核心：`SearchSyncServiceImpl + SearchSyncMapper`
4. 增量链路：`SearchCanalClientRunner + SearchSyncMessageProducer + SearchSyncMessageConsumer`
5. 维护入口：`AdminSearchController`

## 4. 运行开关

1. `sky.search.enabled`：搜索总开关
2. `sky.search.sync.enabled`：RabbitMQ 同步开关
3. `sky.search.canal.enabled`：Canal 客户端开关
4. `sky.search.index.auto-init-on-startup`：启动自动重建开关（默认 false）

## 5. 当前实现状态

1. 搜索接口已落地
2. 全量重建接口已落地
3. RabbitMQ 同步链路已落地
4. Canal 客户端采集链路已落地
5. 教学文档已生成：`docs/elasticsearch-search-sync-teaching.md`
