package com.sky.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 订单超时关闭配置。
 *
 * 这一组配置支撑的是“Redisson 延时队列主链路 + Spring 定时任务兜底链路”。
 * 也就是说，订单超时关闭并不只依赖一种触发方式：
 * - 主链路：订单创建成功后投递延时消息，到点立即消费；
 * - 兜底链路：低频 cron 扫描数据库，把漏处理的超时订单补上。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sky.order")
public class OrderTimeoutProperties {

    /**
     * 待支付订单超时时间，单位：分钟。
     *
     * 它同时影响两处行为：
     * - 下单后延时消息的投递时长；
     * - 兜底扫描时“什么时间之前的订单算超时”的判断阈值。
     */
    private long timeoutMinutes = 15L;

    private DelayQueue delayQueue = new DelayQueue();

    @Data
    public static class DelayQueue {

        /**
         * 是否启用 Redisson 延时队列主链路。
         *
         * 关闭后：
         * - 不再投递 RDelayedQueue 消息；
         * - 启动时不再拉起阻塞消费者线程；
         * - 只能依赖 Spring 定时任务低频补偿。
         */
        private boolean enabled = true;

        /**
         * Redisson 阻塞队列名。
         *
         * Redisson 的 delayed queue 只是“延时视图”，真正被消费者 take() 的是这个阻塞队列。
         */
        private String queueName = "order:timeout:close";

        /**
         * 低频兜底扫描 cron。
         *
         * 它不是为了做到秒级准时，而是处理以下异常场景：
         * - 事务提交后消息入队失败；
         * - Redis 异常；
         * - 消费线程退出；
         * - 锁竞争导致当前消息主动放弃处理。
         */
        private String fallbackCron = "0 */30 * * * ?";
    }
}
