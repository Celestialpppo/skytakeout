package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单超时关闭延时消息。
 *
 * 这是 Redisson 延时队列里流转的内部消息体，不是给前端返回的数据结构。
 * 订单创建成功后，系统会把一条“delay = 超时时间”的消息放入 RDelayedQueue；
 * 到期后，Redisson 会把这条消息转移到阻塞队列，消费者线程再取出执行真正的关单逻辑。
 *
 * 这里使用“轻量消息体”而不是直接缓存完整订单对象，目的是：
 * - 减少 Redis 中存储的数据量；
 * - 避免消息结构和订单实体强耦合；
 * - 消费时统一回库查询真实订单状态，保证最终以数据库为准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutCloseMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单号。
     *
     * 消费端会基于它重新查询订单，而不是相信消息里携带的状态快照。
     */
    private String orderNumber;

    /**
     * 用户 ID。
     *
     * 当前实现主要用于补充上下文和日志，后续如果需要按用户维度审计或路由也可以复用。
     */
    private Long userId;

    /**
     * 业务上期望触发关单的时间点。
     *
     * 真正的延迟调度依赖 delayedQueue.offer(message, delay, unit)，
     * 这个字段主要用于日志排查，便于比较“理论触发时间”和“实际消费时间”。
     */
    private LocalDateTime triggerAt;

    /**
     * 取消原因。
     *
     * 最终会写入订单取消原因字段，便于后台查询和后续排障。
     */
    private String cancelReason;
}
