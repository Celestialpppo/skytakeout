package com.sky.service;

import com.sky.entity.OrderTimeoutCloseMessage;
import com.sky.entity.Orders;

/**
 * 订单超时关闭服务。
 *
 * 接口把“投递任务”和“执行任务”拆开，目的是让不同调用方复用同一条处理链路：
 * - 下单成功后，调用 enqueueAfterCommit / enqueue 投递延时消息；
 * - Redisson 消费线程或 Spring 补偿任务，统一调用 processTimeoutClose 真正关单。
 */
public interface OrderTimeoutCloseService {

    /**
     * 在订单事务提交后投递延时关单消息。
     *
     * 这是正常下单链路应该使用的入口，避免订单事务回滚后 Redis 中还残留脏消息。
     */
    void enqueueAfterCommit(Orders order);

    /**
     * 直接投递一条延时关单消息。
     *
     * 适合无事务场景或需要手工补投时使用。
     */
    void enqueue(OrderTimeoutCloseMessage message, long delayMinutes);

    /**
     * 处理一条超时关单任务。
     *
     * 实现必须满足幂等性，因为调用方既可能是队列消费者，也可能是定时补偿任务。
     */
    void processTimeoutClose(OrderTimeoutCloseMessage message);
}
