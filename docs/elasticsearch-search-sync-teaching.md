# Elasticsearch 搜索与同步教学文档（深入版）

> 适用项目：`sky-take-out-master`  
> 面向读者：第一次接触该模块、希望不仅“会用”，还要“看懂为什么这样设计”的同学。

---

## 1. 文档使用方式（先看这里）

这份文档不是只告诉你“有哪些类”，而是按**开发流程**带你理解：

1. 先明确业务问题与目标
2. 再看架构拆分与模块职责
3. 然后逐个功能讲“实现 + 原因 + 模块联系”
4. 最后给联调流程、排障路径、扩展方向

如果你是第一次接触，建议按顺序完整阅读。

---

## 2. 业务问题与改造目标

### 2.1 原业务痛点

在外卖系统里，用户要找商品时会遇到两类需求：

1. 按关键词搜（如“鸡”“无糖”“牛肉”）
2. 跨业务类型搜（菜品 `dish` + 套餐 `setmeal`）

如果仅靠 MySQL 的 `like`：

1. 灵活检索能力弱
2. 高并发下会给业务主库造成压力
3. 后续做排序、相关性、搜索体验优化困难

### 2.2 本次改造目标

1. 提供统一搜索接口：`GET /user/search`
2. 支持关键词、分页、类型过滤、分类过滤
3. 建立 MySQL -> ES 的稳定同步链路
4. 保证“可恢复、可排错、可运维”

### 2.3 非目标（避免误解）

本次没有做：

1. 拼音搜索
2. 同义词扩展
3. 高亮返回
4. 多语言分析器定制

这些可在后续增强阶段扩展。

---

## 3. 总体架构与设计思想

### 3.1 两条链路

系统拆成两条链路，互不干扰：

1. 读链路：用户搜索时只查 Elasticsearch
2. 写链路：业务数据变更后异步同步到 Elasticsearch

这样做的核心收益是：

1. 搜索性能与主业务库解耦
2. 搜索功能升级不影响核心交易链路
3. 同步失败可重试和排查，不会静默丢失

### 3.2 关键设计原则

1. 统一索引：`goods_search`
2. 统一主键：`bizType:sourceId`
3. 增量消息最小化：只传 `tableName + operation + id + eventTime`
4. 消费端不信消息体内容，必须回查 MySQL 最新状态
5. 消费失败进入死信队列（DLQ）

### 3.3 模块关系图（文字版）

1. 用户侧查询
`UserSearchController` -> `GoodsSearchServiceImpl` -> `Elasticsearch`

2. 管理端全量
`AdminSearchController` -> `SearchSyncServiceImpl.rebuildAll()` -> `MySQL + Elasticsearch`

3. 增量同步
`Canal` -> `SearchCanalClientRunner` -> `SearchSyncMessageProducer` -> `RabbitMQ` -> `SearchSyncMessageConsumer` -> `SearchSyncServiceImpl.syncIncrementalChange()` -> `MySQL + Elasticsearch`

---

## 4. 开发流程总览（按实际落地顺序）

这部分是“你以后自己实现类似模块”的操作模板。

### 4.1 第一步：先定边界和统一模型

先回答三个问题：

1. 搜索对象是什么：`dish + setmeal`
2. 统一索引是否可行：可行
3. 怎么避免主键冲突：`bizType:sourceId`

对应代码：

1. `GoodsSearchDocument`
2. `SearchSourceRecord`
3. `SearchDocumentConverter`

### 4.2 第二步：配置化 + 开关化

不要先写业务逻辑，先把配置与开关建好。

对应代码：

1. `SearchProperties`
2. `SearchSyncRabbitConfig`
3. `SearchInfrastructureConfig`
4. `application.yml` 中 `sky.search.*`

这样后续每个能力都能独立打开/关闭。

### 4.3 第三步：先打通读链路

先让用户能搜，再做增量同步。

对应代码：

1. `SearchQueryDTO`
2. `SearchItemVO`
3. `GoodsSearchServiceImpl`
4. `UserSearchController`

### 4.4 第四步：实现全量重建

这是增量链路的“安全底座”。

对应代码：

1. `SearchSyncMapper + XML`
2. `SearchSyncServiceImpl.rebuildAll()`
3. `AdminSearchController`

### 4.5 第五步：实现增量链路

顺序是：采集 -> 投递 -> 消费 -> 同步。

对应代码：

1. `SearchSyncMessage`
2. `SearchCanalClientRunner`
3. `SearchSyncMessageProducer`
4. `SearchSyncMessageConsumer`
5. `SearchSyncServiceImpl.syncIncrementalChange()`

### 4.6 第六步：补运维入口与启动策略

对应代码：

1. `SearchIndexInitRunner`（可选自动重建）
2. `/admin/search/rebuild`（人工可控）

---

## 5. 模块逐个教学（功能 + 为什么 + 与谁协作）

下面每个模块都从 4 个角度讲：

1. 功能
2. 输入输出
3. 为什么这么设计
4. 和其他模块的关系

### 5.1 `SearchProperties`（配置中枢）

文件：`sky-server/src/main/java/com/sky/config/SearchProperties.java`

功能：

1. 聚合搜索模块全部配置
2. 提供分层配置对象：`index/sync/canal`

输入输出：

1. 输入：`application.yml` 中 `sky.search.*`
2. 输出：给 Service、MQ、Canal 配置类读取

为什么这么设计：

1. 防止硬编码
2. 方便多环境切换
3. 开关可控，避免基础设施未就绪时启动失败

与谁协作：

1. `GoodsSearchServiceImpl` 读取索引名和分页上限
2. `SearchSyncRabbitConfig` 读取队列交换机配置
3. `SearchCanalClientRunner` 读取 Canal 连接和订阅配置

### 5.2 `SearchInfrastructureConfig`（消息序列化）

文件：`sky-server/src/main/java/com/sky/config/SearchInfrastructureConfig.java`

功能：

1. 注册 `Jackson2JsonMessageConverter`

为什么这么设计：

1. 生产者直接发 Java 对象
2. 消费者自动反序列化为 Java 对象
3. 消息结构可读、便于排障

与谁协作：

1. `SearchSyncMessageProducer`
2. `SearchSyncMessageConsumer`

### 5.3 `SearchSyncRabbitConfig`（MQ 拓扑）

文件：`sky-server/src/main/java/com/sky/config/SearchSyncRabbitConfig.java`

功能：

1. 创建主交换机、主队列
2. 绑定 `dish/setmeal/category` 三种 routing key
3. 配置死信交换机和死信队列

为什么这么设计：

1. 一条队列统一消费，简化消费逻辑
2. routing key 仍可区分来源类型
3. 消费异常可进入 DLQ，问题可追踪

与谁协作：

1. 生产者发送到主交换机
2. 消费者监听主队列
3. 失败消息进入死信队列

### 5.4 `SearchQueryDTO` 与 `SearchItemVO`（接口契约）

文件：

1. `sky-pojo/src/main/java/com/sky/dto/SearchQueryDTO.java`
2. `sky-pojo/src/main/java/com/sky/vo/SearchItemVO.java`

功能：

1. 定义搜索请求参数
2. 定义统一返回结构

为什么这么设计：

1. DTO 做参数校验（页码、type 取值等）
2. VO 屏蔽 ES 内部字段，仅暴露前端需要数据
3. 统一契约后前端接入成本低

与谁协作：

1. `UserSearchController`
2. `GoodsSearchServiceImpl`

### 5.5 `GoodsSearchDocument`（索引文档）

文件：`sky-server/src/main/java/com/sky/search/model/GoodsSearchDocument.java`

功能：

1. 定义 ES 存储结构
2. 包含搜索字段、过滤字段、排序字段

关键字段与目的：

1. `id`: `bizType:sourceId`，全局唯一
2. `bizType`: 区分菜品/套餐
3. `status/categoryStatus`: 查询时快速过滤
4. `updateTime`: 排序兜底

为什么这么设计：

1. 统一索引带来统一查询接口
2. 显式状态字段减少查询时二次回库
3. 主键规则固定，增删改幂等更容易

与谁协作：

1. `SearchDocumentConverter`
2. `GoodsSearchServiceImpl`
3. `SearchSyncServiceImpl`

### 5.6 `SearchSourceRecord`（MySQL 联表源模型）

文件：`sky-server/src/main/java/com/sky/search/model/SearchSourceRecord.java`

功能：

1. 承接 Mapper 联表结果
2. 作为 MySQL 到 ES 的中间数据模型

为什么这么设计：

1. 避免直接拿 entity 拼文档
2. 联表字段（如 `categoryName`）在 SQL 层一次取齐
3. 转换链路清晰：`SourceRecord -> Document`

与谁协作：

1. `SearchSyncMapper.xml`
2. `SearchDocumentConverter`
3. `SearchSyncServiceImpl`

### 5.7 `SearchDocumentConverter`（转换和规则中枢）

文件：`sky-server/src/main/java/com/sky/search/converter/SearchDocumentConverter.java`

功能：

1. 生成文档 ID
2. 判定是否应入索引 `shouldIndex`
3. 模型转换

为什么这么设计：

1. 规则集中，避免分散在多个 service 中
2. 降低修改成本（规则变更只改一处）
3. 统一行为，减少不同链路判定不一致

与谁协作：

1. `SearchSyncServiceImpl`
2. `GoodsSearchServiceImpl`

### 5.8 `SearchSyncMapper` + `SearchSyncMapper.xml`（数据来源）

文件：

1. `sky-server/src/main/java/com/sky/mapper/SearchSyncMapper.java`
2. `sky-server/src/main/resources/mapper/SearchSyncMapper.xml`

功能：

1. 提供全量查询
2. 提供单条按 ID 回查
3. 提供分类影响范围查询

为什么这么设计：

1. 增量消费时必须回查最新状态
2. 分类变化会影响其下多个商品，需批量找受影响 ID
3. SQL 与业务逻辑分离，便于优化与排查

与谁协作：

1. `SearchSyncServiceImpl`

### 5.9 `GoodsSearchServiceImpl`（读链路核心）

文件：`sky-server/src/main/java/com/sky/service/impl/GoodsSearchServiceImpl.java`

功能：

1. 构建 ES bool 查询
2. 处理分页与页大小上限
3. 做状态过滤和排序
4. 返回统一分页结果

核心查询策略：

1. 有关键词：`multiMatch(name,description,categoryName)`
2. 无关键词：`match_all`
3. 必加过滤：`status=1 && categoryStatus=1`
4. 排序：关键词模式下 `_score desc + updateTime desc`，无关键词按 `updateTime desc`

为什么这么设计：

1. 关键词模式优先相关性
2. 无关键词模式优先新数据
3. `filter` 比 `must` 更适合精确筛选（不参与打分）
4. 限制 `pageSize` 防止滥用查询

与谁协作：

1. `UserSearchController`
2. `SearchProperties`
3. `SearchDocumentConverter`

### 5.10 `UserSearchController`（用户入口）

文件：`sky-server/src/main/java/com/sky/controller/user/UserSearchController.java`

功能：

1. 暴露 `GET /user/search`
2. 接收 DTO，调用 service
3. 返回统一 `Result<PageResult<SearchItemVO>>`

为什么这么设计：

1. Controller 保持薄层，只做协议转换
2. 业务规则集中在 Service，便于测试与维护

与谁协作：

1. `GoodsSearchService`

### 5.11 `SearchSyncServiceImpl`（同步核心大脑）

文件：`sky-server/src/main/java/com/sky/service/impl/SearchSyncServiceImpl.java`

功能：

1. `rebuildAll()`：全量重建
2. `syncIncrementalChange()`：按表处理增量
3. `syncCategoryImpactById()`：处理分类级联影响

为什么这么设计：

1. 全量与增量共用同一套转换和判定规则
2. 分类影响是业务关键点，单独封装可读性更强
3. 统一入口便于后续接入其它事件源

与谁协作：

1. `SearchSyncMapper`
2. `SearchDocumentConverter`
3. `ElasticsearchOperations`
4. `SearchSyncMessageConsumer`
5. `AdminSearchController`

### 5.12 `SearchSyncMessage`（最小事件模型）

文件：`sky-server/src/main/java/com/sky/search/model/SearchSyncMessage.java`

功能：

1. 承载表变更最小信息

字段含义：

1. `tableName`：来源表
2. `operation`：INSERT/UPDATE/DELETE
3. `id`：主键
4. `eventTime`：事件发生时间

为什么这么设计：

1. 消息越小越稳定
2. 传输成本低
3. 防止把业务快照塞进消息导致过时数据写入

与谁协作：

1. `SearchCanalClientRunner`
2. `SearchSyncMessageProducer`
3. `SearchSyncMessageConsumer`

### 5.13 `SearchSyncMessageProducer`（增量消息发送）

文件：`sky-server/src/main/java/com/sky/search/mq/SearchSyncMessageProducer.java`

功能：

1. 按表名映射 routing key
2. 发送消息到主交换机

为什么这么设计：

1. 发送逻辑独立，Canal 只关心采集
2. 路由规则集中，后续加表更容易

与谁协作：

1. `SearchCanalClientRunner`
2. `RabbitTemplate`

### 5.14 `SearchSyncMessageConsumer`（增量消息消费）

文件：`sky-server/src/main/java/com/sky/search/mq/SearchSyncMessageConsumer.java`

功能：

1. 监听主队列
2. 调用同步服务处理
3. 出错时抛异常触发 DLQ

为什么这么设计：

1. 消费端只负责“接入”和“异常策略”
2. 真正业务逻辑集中在 `SearchSyncServiceImpl`
3. 失败进入死信，便于人工补偿

与谁协作：

1. `SearchSyncService`
2. RabbitMQ 监听容器

### 5.15 `SearchCanalClientRunner`（Binlog 采集器）

文件：`sky-server/src/main/java/com/sky/search/canal/SearchCanalClientRunner.java`

功能：

1. 启动后连接 Canal
2. 订阅 `dish/setmeal/category`
3. 解析批次消息，提取主键
4. 转发给 MQ 生产者

为什么这么设计：

1. 与业务应用同进程，接入快
2. 批次 ack/rollback 控制清晰
3. 异常后可休眠重试，避免空转

与谁协作：

1. Canal Server
2. `SearchSyncMessageProducer`
3. `SearchProperties`

### 5.16 `AdminSearchController` 与 `SearchIndexInitRunner`（运维控制）

文件：

1. `sky-server/src/main/java/com/sky/controller/admin/AdminSearchController.java`
2. `sky-server/src/main/java/com/sky/search/task/SearchIndexInitRunner.java`

功能：

1. 管理端手动重建：`POST /admin/search/rebuild`
2. 启动自动重建（可选）

为什么这么设计：

1. 生产优先手动可控
2. 开发测试可自动初始化提效
3. 同一套重建逻辑，避免两套实现偏差

与谁协作：

1. `SearchSyncService`
2. `SearchProperties`

---

## 6. 最关键的设计决策（重点理解）

### 6.1 为什么用统一索引，不拆成 dish/setmeal 两个索引

统一索引优点：

1. 前端只调一个接口
2. 统一排序与分页逻辑
3. 过滤 `bizType` 即可做类型维度区分

代价：

1. mapping 设计要兼容两类数据
2. 某些类型特有字段需要谨慎处理

在当前业务里，字段结构高度相似，因此统一索引更合适。

### 6.2 为什么消息不直接带商品完整数据

因为消息是“事件通知”，不是“权威快照”。

如果把整行数据塞进消息，遇到乱序会出现脏写。  
本方案改为：消息只传主键，消费端回查 MySQL 最新状态。

### 6.3 为什么分类变更要级联刷新 dish/setmeal

分类有两个关键字段会影响搜索：

1. 分类名称
2. 分类状态

分类一变，关联商品在 ES 的展示字段和可见性都可能改变，必须级联处理。

### 6.4 为什么加死信队列

没有 DLQ 时，消费异常容易被忽略，最终变成“数据悄悄不一致”。  
有 DLQ 后：

1. 失败消息可追踪
2. 可人工排查并补偿
3. 系统透明度提升

### 6.5 为什么全量重建是“删索引再重建”

好处：

1. 映射和数据一起重置
2. 避免旧 mapping 残留问题

代价：

1. 重建期间索引不可用（或结果不完整）

所以生产建议：

1. 低峰执行
2. 后续可进化为 alias 切换方案

---

## 7. 时序教学（用真实场景理解链路）

### 7.1 场景 A：用户搜索“鸡”

1. 请求进入 `/user/search`
2. `GoodsSearchServiceImpl` 构建 ES 查询
3. 强制过滤 `status=1 && categoryStatus=1`
4. 返回分页结果

你要关注：

1. 搜索只读 ES，不回 MySQL
2. 搜索正确性依赖同步链路质量

### 7.2 场景 B：菜品更新（UPDATE dish）

1. MySQL 更新 `dish`
2. Canal 捕获 binlog
3. 生产者发消息：`table=dish, operation=UPDATE, id=xxx`
4. 消费者收到后调用 `syncIncrementalChange`
5. 服务按 `id` 回查 MySQL 最新数据
6. 通过 `shouldIndex` 决定 upsert 或 delete

你要关注：

1. 业务真相在 MySQL
2. ES 是投影副本

### 7.3 场景 C：分类禁用（UPDATE category.status=0）

1. Canal 发送 `category` 变更消息
2. 同步服务查出该分类下所有 `dishId` + `setmealId`
3. 逐条回查并执行 `shouldIndex`
4. 因 `categoryStatus=0`，对应文档会被删掉或不再入索引

你要关注：

1. 分类是跨实体影响点
2. 这里体现“级联一致性”设计

### 7.4 场景 D：消费失败

1. 消费者抛异常
2. RabbitMQ 按队列死信参数路由到 `search.sync.dlq`
3. 开发/运维可定位失败消息并补偿

你要关注：

1. 失败不可怕，可见且可修复最重要

---

## 8. 配置开关矩阵（按场景启用）

配置文件：`sky-server/src/main/resources/application.yml`

### 8.1 只开搜索，不开增量

1. `sky.search.enabled=true`
2. `sky.search.sync.enabled=false`
3. `sky.search.canal.enabled=false`

适合：先验证读链路。

### 8.2 开完整链路

1. `sky.search.enabled=true`
2. `sky.search.sync.enabled=true`
3. `sky.search.canal.enabled=true`

适合：联调与生产。

### 8.3 启动自动重建（仅建议开发测试）

1. `sky.search.index.auto-init-on-startup=true`

适合：本地快速重建索引。

---

## 9. 第一次联调的完整操作手册

### 9.1 准备阶段

1. 确认 ES 可连
2. 确认 RabbitMQ 可连
3. 确认 Canal 与 MySQL binlog 配置正确

### 9.2 基础验证（先读后写）

1. 开启 `search.enabled=true`
2. 关闭 `sync.enabled/canal.enabled`
3. 调 `POST /admin/search/rebuild`
4. 调 `GET /user/search` 验证结果

### 9.3 增量验证

1. 开启 `sync.enabled=true`
2. 开启 `canal.enabled=true`
3. 修改一条 dish，观察搜索结果是否更新
4. 修改 category.status，观察关联数据是否消失

### 9.4 故障演练

1. 人为让消费逻辑抛错
2. 确认消息进入 `search.sync.dlq`
3. 恢复后执行补偿

---

## 10. 常见问题排障地图（症状 -> 原因 -> 检查）

### 10.1 症状：搜索结果为空

可能原因：

1. 未执行全量重建
2. 文档被 `status/categoryStatus` 过滤
3. 查询参数不符合预期（type/categoryId）

检查顺序：

1. 看重建日志
2. 查 ES 文档
3. 打印请求参数

### 10.2 症状：MySQL 改了，搜索没变

可能原因：

1. Canal 没采集到
2. 生产者没发出
3. 消费者报错进 DLQ
4. 回查数据不满足 `shouldIndex`

检查顺序：

1. Canal 日志
2. Rabbit 队列堆积
3. 消费日志和死信队列
4. MySQL 当前数据状态

### 10.3 症状：分类禁用后还能搜到商品

可能原因：

1. category 变更未被采集
2. 分类级联逻辑未执行成功
3. 历史文档未删除

检查顺序：

1. 看是否收到 `table=category` 消息
2. 看 `syncCategoryImpactById` 日志
3. 查目标文档 `categoryStatus` 字段

### 10.4 症状：接口很慢

可能原因：

1. `pageSize` 过大
2. 索引字段映射不合理
3. ES 资源不足

检查顺序：

1. 参数与 `max-page-size`
2. ES query profile
3. 节点 CPU/heap

---

## 11. 你应该掌握的“设计思想”总结

真正要学会的不是“类名”，而是这些原则：

1. **读写分离**：查询走 ES，写入仍以 MySQL 为准
2. **最终一致性**：接受短暂延迟，但必须可恢复
3. **最小事件模型**：消息只传主键和动作
4. **消费端回查**：避免消息乱序带来的脏写
5. **统一规则中心化**：转换器统一主键与入索引规则
6. **失败可观测**：DLQ 让问题可见、可处理
7. **开关治理**：分阶段启用能力，降低上线风险

---

## 12. 学习路径（建议你按这个练）

### 12.1 第一轮：理解结构

按顺序阅读：

1. `SearchProperties`
2. `GoodsSearchDocument`
3. `SearchDocumentConverter`
4. `GoodsSearchServiceImpl`
5. `SearchSyncServiceImpl`
6. `SearchCanalClientRunner`
7. `SearchSyncMessageConsumer`

目标：能说清“请求怎么查、数据怎么同步”。

### 12.2 第二轮：做最小实验

1. 只开搜索能力
2. 手工重建索引
3. 调查询接口

目标：确认读链路独立可运行。

### 12.3 第三轮：做增量实验

1. 开启 sync+canal
2. 修改 dish / category
3. 观察 ES 变化与日志

目标：确认端到端同步链路。

### 12.4 第四轮：做异常实验

1. 消费端抛异常
2. 验证 DLQ
3. 手工补偿

目标：建立“故障不可怕，可控最关键”的思维。

---

## 13. 后续可扩展方向（你彻底吃透后再做）

1. 搜索高亮（返回命中片段）
2. 同义词与分词器优化（提升召回）
3. 热门词缓存与推荐
4. 基于别名的零停机重建（index alias）
5. 消费重试与幂等增强（如重试队列）
6. 监控看板（同步延迟、DLQ 堆积、重建耗时）

---

## 14. 最后一句话：怎么判断你真的学会了

如果你现在可以独立回答下面 4 个问题，说明你已经真正掌握：

1. 为什么消息里不放完整商品数据，而只放主键？
2. 为什么分类变更会触发 dish/setmeal 的级联同步？
3. 为什么要有全量重建接口，即使有增量同步？
4. 为什么同步失败不应该直接吞掉，而要进死信队列？

能答清楚这 4 个“为什么”，你就不仅实现了功能，也具备了可复用的架构思维。

