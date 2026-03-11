# 苍穹外卖基于 Redisson `RDelayedQueue` + 状态机的超时订单取消方案

## 1. 目标

将苍穹外卖当前“每分钟扫描一次待支付超时订单”的处理方式，升级为：

- 下单成功后立即投递一条 Redisson 延时消息
- 到达超时时间后自动消费消息
- 消费时通过订单状态机执行取消
- 数据库层继续用 CAS 条件更新兜底并发一致性

这套方案借鉴了 `congomall` 的核心思路：

- 下单后异步延迟关闭订单
- 消费时二次校验订单状态
- 只有订单仍然是待支付时才执行真正关单

但基础设施没有照搬它的 RocketMQ，而是改造成更适合苍穹外卖单体架构的 `Redisson RDelayedQueue`。

---

## 2. 原项目中已有的旧实现

旧实现位于：

- `sky-server/src/main/java/com/sky/task/ShopTask.java`

原来的超时关单逻辑是：

1. 每分钟执行一次定时任务
2. 扫描 `orders` 表里 `order_time <= now - 15min` 且 `status = PENDING_PAYMENT` 的订单
3. 直接调用 `orderMapper.updateWithCondition(order, Orders.PENDING_PAYMENT)` 改成已取消

这个方案的问题是：

- 有轮询延迟，不是订单级精准触发
- 所有订单都要被周期性扫描，数据库压力不必要
- 主链路是定时任务，不适合拿来讲“延时消息/异步关闭订单”的工程能力

---

## 3. 本次新增了什么

### 3.1 新增超时关闭消息模型

新增文件：

- `sky-pojo/src/main/java/com/sky/entity/OrderTimeoutCloseMessage.java`

作用：

- 作为 Redis 延时队列中的消息体
- 避免直接把 `Orders` 实体完整塞进队列

字段：

- `orderNumber`
- `userId`
- `triggerAt`
- `cancelReason`

---

### 3.2 新增订单超时配置类

新增文件：

- `sky-server/src/main/java/com/sky/config/OrderTimeoutProperties.java`

配套配置新增到：

- `sky-server/src/main/resources/application.yml`

新增配置项：

- `sky.order.timeout-minutes=15`
- `sky.order.delay-queue.enabled=true`
- `sky.order.delay-queue.queue-name=order:timeout:close`
- `sky.order.delay-queue.fallback-cron=0 */30 * * * ?`

作用：

- 统一管理订单超时时间
- 统一管理 Redisson 延时队列开关和队列名
- 统一管理低频兜底补偿任务 cron

---

### 3.3 新增统一的超时关单服务

新增文件：

- `sky-server/src/main/java/com/sky/service/OrderTimeoutCloseService.java`
- `sky-server/src/main/java/com/sky/service/impl/OrderTimeoutCloseServiceImpl.java`

职责：

1. 下单成功后投递延时关单消息
2. 消费到点消息并执行超时取消
3. 让兜底补偿扫描复用同一套取消逻辑

核心方法：

- `enqueueAfterCommit(Orders order)`
- `enqueue(OrderTimeoutCloseMessage message, long delayMinutes)`
- `processTimeoutClose(OrderTimeoutCloseMessage message)`

---

### 3.4 新增 Redisson 延时队列消费者

新增文件：

- `sky-server/src/main/java/com/sky/task/OrderTimeoutCloseQueueConsumer.java`

作用：

- 项目启动后拉起单线程消费者
- 从 `RBlockingDeque` 阻塞取消息
- Redisson 内部通过 `RDelayedQueue` 在消息到期后把消息转移到阻塞队列
- 消费后统一调用 `OrderTimeoutCloseService.processTimeoutClose(...)`

---

## 4. 本次修改了什么

### 4.1 修改下单成功后的处理链路

修改文件：

- `sky-server/src/main/java/com/sky/service/impl/OrderServiceImpl.java`

修改点：

- 在 `submit()` 方法中，订单、订单明细、购物车清理、幂等日志全部成功后
- 新增 `orderTimeoutCloseService.enqueueAfterCommit(orders);`

设计原因：

- 不是在事务里直接投递延时消息
- 而是在事务提交成功后再投递
- 防止订单回滚了，但 Redis 里已经存在一条脏的超时取消任务

---

### 4.2 修改原来的高频超时扫描任务

修改文件：

- `sky-server/src/main/java/com/sky/task/ShopTask.java`

原逻辑：

- 每分钟扫描一次超时订单
- 直接更新订单状态为已取消

改造后：

- 定时任务仍保留，但改为**低频兜底补偿**
- cron 从固定 `0 * * * * ?` 改成读取配置 `sky.order.delay-queue.fallback-cron`
- 不再直接 `updateWithCondition`
- 改成调用统一的 `orderTimeoutCloseService.processTimeoutClose(...)`

这意味着：

- **原来的高频扫描主逻辑已停用**
- **新的主链路是 Redisson 延时队列**
- `ShopTask` 只作为 Redis 队列异常、入队失败时的最终兜底补偿

---

### 4.3 修改超时订单查询方式

修改文件：

- `sky-server/src/main/java/com/sky/mapper/OrderMapper.java`

原来的代码：

- 写死了 `INTERVAL 15 MINUTE`
- 方法名是 `findTimeoutOrders()`

改造后：

- 删除了原来的写死查询
- 新增 `findTimeoutOrdersBefore(LocalDateTime timeoutBefore)`

作用：

- 不再把“15 分钟”写死在 SQL 里
- 由 Service 按配置算出超时截止时间，再传给 Mapper

---

## 5. 本次没有新增什么

为了保持和苍穹外卖当前单体架构匹配，这次**没有新增**以下内容：

1. 没有新增数据库表
2. 没有新增 MQ 中间件
3. 没有新增 outbox 消息表
4. 没有新增分布式事务
5. 没有新增新的状态机事件

本次复用的是现有能力：

- `RedissonClient`
- `OrderStateContext`
- `OrderStateMachineConfig`
- `PendingPaymentState.adminCancel(...)`
- `orderMapper.updateWithCondition(...)`

---

## 6. 超时取消最终执行链路

### 主链路

1. 用户提交订单
2. 订单事务提交成功
3. `enqueueAfterCommit()` 投递一条 15 分钟延时消息
4. 15 分钟后消息进入阻塞队列
5. 消费者拿到消息
6. `processTimeoutClose()` 开始处理
7. 对 `order:pay:lock:{orderNumber}` 加锁
8. 查询订单状态
9. 若订单不是待支付，直接幂等跳过
10. 若订单仍是待支付，则构建状态机
11. 调用 `orderStateContext.adminCancel(order, stateMachine, cancelReason)`
12. 最终由状态机 + `updateWithCondition` 完成 CAS 落库

### 兜底补偿链路

1. 定时任务低频扫描待支付超时订单
2. 对每个订单构造 `OrderTimeoutCloseMessage`
3. 调用同一个 `processTimeoutClose(...)`
4. 复用主链路逻辑执行取消

---

## 7. 并发一致性怎么保证

### 第一层：支付锁

在 `OrderTimeoutCloseServiceImpl.processTimeoutClose()` 中，先拿：

- `order:pay:lock:{orderNumber}`

这样可以和以下链路共用同一把锁：

- 用户发起支付
- 支付回调
- 超时自动取消

避免三条链路同时修改同一订单状态。

### 第二层：状态机

超时取消不是直接修改数据库状态，而是走：

- `orderStateContext.adminCancel(...)`

这样可以保证：

- 只有合法状态才允许取消
- 已支付、已取消、已完成等状态不会被错误推进

### 第三层：数据库 CAS

最终落库仍然依赖：

- `orderMapper.updateWithCondition(order, oldStatus)`

也就是：

- `update ... where id = ? and status = oldStatus`

即使存在极端并发，最终也只能有一个流程真正修改成功。

---

## 8. 借鉴 congomall 的地方

这次借鉴 `congomall` 的，不是它的 RocketMQ 代码本身，而是它的业务设计思想：

### 借鉴点 1：延迟关闭订单

`congomall` 里存在：

- `DelayCloseOrderConsumer`

思路是：

- 下单后投递延迟关闭事件
- 到期后消费并执行关单

苍穹外卖这次改造沿用了这个业务模型。

### 借鉴点 2：消费前二次校验订单状态

`congomall` 的消费者不是盲目关单，而是：

- 重新查订单状态
- 只有仍是待支付才关闭

苍穹外卖这次也沿用了同样原则：

- 查询订单
- 如果不是 `PENDING_PAYMENT`，直接幂等跳过

### 借鉴点 3：把超时关闭当作异步链路，而不是数据库扫描主链路

`congomall` 更强调“事件驱动的延迟关闭”，而不是“定时轮询主处理”。
这次苍穹外卖也做了同样调整：

- 延时队列负责主链路
- 定时任务只做兜底补偿

---

## 9. 最终结论

这次改造后，苍穹外卖的超时订单取消能力已经从：

- **高频数据库轮询关单**

升级为：

- **Redisson 延时队列精准触发 + 状态机合法流转 + 数据库 CAS 兜底**

同时也明确做了职责拆分：

- **新增**：延时消息模型、配置类、超时关单服务、延时队列消费者
- **修改**：下单成功后接入 after-commit 投递、ShopTask 改为低频补偿、OrderMapper 改为配置化查询
- **停用旧主逻辑**：原来的“每分钟扫描并直接关单”不再作为主链路

如果要写到简历里，可以概括成一句：

**基于 Redisson `RDelayedQueue`、Spring StateMachine 与 MyBatis 条件更新，实现订单超时自动取消能力，将原有分钟级轮询升级为订单级延迟触发，并通过支付锁、状态机和 CAS 保证支付与关单并发场景下的一致性。**

---

## 10. 简历 STAR 一句话版

针对苍穹外卖原有“每分钟轮询超时订单”存在触发不精准、数据库扫描成本高及并发场景下易与支付链路冲突的问题，我基于 **Redisson `RDelayedQueue`、Spring StateMachine、MyBatis 条件更新、Redis 分布式锁** 设计并落地订单超时自动取消方案，将超时关单改为“下单后延迟投递、到点自动消费”，并通过状态机 + CAS 保证订单只被合法取消一次。

---

## 11. 项目经历精炼描述

负责苍穹外卖订单超时取消链路改造，基于 **Redisson 延时队列、订单状态机、支付锁、数据库条件更新** 将原有分钟级轮询关单升级为订单级延迟触发；通过 after-commit 投递、消费前状态二次校验及低频补偿扫描，保证待支付订单超时自动关闭，并避免与支付成功链路发生状态覆盖。

---

## 12. 口述背诵版

我在苍穹外卖里做过一个订单超时自动取消改造。原来项目是靠定时任务每分钟扫描一次超时未支付订单，然后直接改数据库状态，这种方式有两个问题，一个是触发不够精准，另一个是数据库会一直被轮询，而且在用户刚好支付成功的瞬间，容易和关单任务发生并发冲突。后来我参考了 `congomall` 延迟关闭订单的思路，把它改成了基于 Redisson `RDelayedQueue` 的方案。具体做法是，下单成功后不再依赖高频扫描，而是在事务提交成功后投递一条 15 分钟后的延时消息；消息到点后由消费者拉取，再去查订单当前状态，如果订单还是待支付，就复用现有状态机走 `adminCancel` 事件取消订单；如果已经支付或者已经取消，就直接幂等跳过。为了避免支付和超时取消同时改同一笔订单，我复用了支付链路的分布式锁；最终落库时继续使用 `update ... where status = oldStatus` 做 CAS 兜底。这样整套链路就从“轮询关单”升级成了“延时触发 + 状态机流转 + CAS 保一致”。

---

## 13. 面试问答

### 13.1 为什么不继续用定时任务扫描，而要换成延时队列？

**答：**
定时任务扫描的优点是简单，但缺点也明显。第一，触发不精准，订单可能要等到下一个扫描周期才被处理；第二，会持续扫描数据库，订单量大了之后开销比较高；第三，它不适合表达“订单创建后延迟执行某件事”的业务语义。延时队列更贴合超时关单场景，因为它是订单创建时就注册一个未来动作，到点后精确执行。

### 13.2 为什么选择 Redisson `RDelayedQueue`，而不是 MQ？

**答：**
因为苍穹外卖当前是单体项目，已经接了 Redis 和 Redisson，没有 MQ 基础设施。这个场景只需要一套轻量可用的延时能力，`RDelayedQueue` 足够支撑，而且接入成本很低。如果项目后续变成微服务、还要做库存回补、跨服务通知，再升级成 MQ 延迟消息会更合理。

### 13.3 为什么下单成功后要 after-commit 再投递？

**答：**
因为如果在事务里直接投递延时消息，可能出现订单事务最终回滚，但 Redis 里已经有一条待执行的超时取消任务，这就变成脏消息了。所以我把投递放到事务提交成功之后，只有订单真正落库成功，才注册超时关单任务。

### 13.4 为什么超时取消还要走状态机，不能直接 update 吗？

**答：**
不能直接 update。直接改状态最大的问题是没有业务约束，容易把已经支付或者其他非法状态的订单也错误改掉。状态机的价值是先定义“哪些状态允许取消、哪些状态不允许取消”，让所有订单状态推进都走统一入口。这样超时取消不会绕开业务规则。

### 13.5 为什么超时取消和支付要共用同一把锁？

**答：**
因为它们竞争的是同一笔订单的状态修改权。比如订单在第 15 分钟刚好支付成功，这时候支付回调和超时取消都可能来改状态。如果两边各用一套锁，还是可能并发覆盖。共用 `order:pay:lock:{orderNumber}` 之后，支付、回调、超时取消会被串行化处理，能把并发冲突收敛到同一个入口。

### 13.6 有了锁和状态机，为什么还要数据库 CAS？

**答：**
因为锁和状态机都不是最后一道物理保证。锁有超时和异常释放的问题，状态机更多是业务规则层面的约束。真正落库时，还是要用 `update ... where status = oldStatus` 来保证数据库层只允许一个线程成功更新。这样即使出现极端并发，最终也不会出现两个线程都把订单状态改掉的情况。

### 13.7 如果延时队列消息丢了怎么办？

**答：**
我保留了一个低频补偿扫描任务。它不再承担主链路职责，只负责兜底。如果出现延时消息投递失败、Redis 队列临时异常或者消费者挂掉，补偿任务还会扫描待支付超时订单，并复用同一套 `processTimeoutClose(...)` 逻辑进行取消。这样主链路是事件驱动，可靠性靠补偿兜底。

### 13.8 为什么补偿任务不能继续直接更新数据库？

**答：**
因为如果补偿任务直接更新数据库，就会重新变成两套超时取消逻辑：一套是延时队列，一套是定时任务。后续维护时容易分叉，行为不一致。最稳的做法是让补偿任务也调用统一的超时关单服务，保证所有取消都走同一条链路。

### 13.9 这次借鉴 `congomall` 借鉴了什么？

**答：**
主要借鉴的是“延迟关闭订单 + 消费前重新校验订单状态”的业务设计思想，不是直接抄它的 RocketMQ 代码。`congomall` 更偏微服务和消息驱动，苍穹外卖是单体项目，所以我把这个思路换成了更轻量的 Redisson 延时队列实现。

### 13.10 这个方案最大的亮点是什么？

**答：**
最大的亮点不是“用了 Redisson”，而是把超时关单从数据库轮询升级成了事件驱动的延迟触发，并且和原有支付、状态机、CAS 体系打通了。也就是说，这不是单独加了一个延时队列，而是把订单并发一致性方案扩展到了“支付成功”和“超时关单”两个互斥链路上。
