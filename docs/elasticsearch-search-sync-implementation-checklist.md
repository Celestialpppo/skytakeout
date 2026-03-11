# Elasticsearch 搜索改造实施清单（实现对齐版）

## 1. 依赖与配置

- [x] `sky-server/pom.xml` 增加 Elasticsearch 依赖
- [x] `sky-server/pom.xml` 增加 RabbitMQ 依赖
- [x] `sky-server/pom.xml` 增加 Canal Client 依赖
- [x] `application.yml` 增加 `spring.elasticsearch` 配置
- [x] `application.yml` 增加 `spring.rabbitmq` 配置
- [x] `application.yml` 增加 `sky.search` 配置和功能开关

## 2. 搜索读链路

- [x] 新增统一搜索入参 `SearchQueryDTO`
- [x] 新增统一搜索结果 `SearchItemVO`
- [x] 新增搜索控制器 `/user/search`
- [x] 新增搜索服务 `GoodsSearchServiceImpl`
- [x] 支持关键词、分页、类型过滤、分类过滤

## 3. 索引与数据模型

- [x] 新增 Elasticsearch 文档模型 `GoodsSearchDocument`
- [x] 新增 MySQL 联表源模型 `SearchSourceRecord`
- [x] 新增转换器 `SearchDocumentConverter`
- [x] 文档主键规则统一为 `bizType:sourceId`

## 4. 全量与增量同步

- [x] 新增同步服务接口 `SearchSyncService`
- [x] 新增同步服务实现 `SearchSyncServiceImpl`
- [x] 新增同步 Mapper `SearchSyncMapper + XML`
- [x] 支持全量重建索引
- [x] 支持按表处理增量变更（dish/setmeal/category）

## 5. RabbitMQ 与 Canal

- [x] 新增 RabbitMQ 基础配置与交换机队列绑定
- [x] 新增同步消息模型 `SearchSyncMessage`
- [x] 新增消息生产者 `SearchSyncMessageProducer`
- [x] 新增消息消费者 `SearchSyncMessageConsumer`
- [x] 新增 Canal 客户端运行器 `SearchCanalClientRunner`

## 6. 运维与维护接口

- [x] 新增管理端手动重建接口 `/admin/search/rebuild`
- [x] 新增启动自动重建 Runner（默认关闭）
- [x] 增量链路增加死信队列兜底

## 7. 文档交付

- [x] 方案文档：`docs/elasticsearch-search-sync-plan.md`
- [x] 实施清单：`docs/elasticsearch-search-sync-implementation-checklist.md`
- [x] 教学文档：`docs/elasticsearch-search-sync-teaching.md`
