package com.sky.task;

import com.sky.config.OrderTimeoutProperties;
import com.sky.entity.OrderTimeoutCloseMessage;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderTimeoutCloseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单定时任务。
 *
 * 这个类里的任务在超时关单方案中承担的是“低频补偿”角色，而不是主触发器。
 *
 * 主触发器是 Redisson RDelayedQueue：
 * - 下单成功后投递延时消息；
 * - 到期后由阻塞消费者线程立即处理。
 *
 * 保留 @Scheduled 的原因是给主链路做兜底，防止以下异常场景漏单：
 * - afterCommit 投递失败；
 * - Redis 故障；
 * - 消费线程退出；
 * - 锁竞争导致某次消息主动放弃处理。
 *
 * 注意：
 * - 补偿任务依然可能和支付链路并发发生；
 * - 所以最终状态更新仍必须依赖锁和 CAS 避免误覆盖。
 */
@Component
@Slf4j
public class ShopTask {

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTimeoutCloseService orderTimeoutCloseService;

    @Autowired
    private OrderTimeoutProperties orderTimeoutProperties;

    /**
     * 低频补偿扫描“待付款且超时”的订单并尝试关单。
     *
     * 它的定位不是“精确定时关单”，而是“最终一致性补偿器”。
     */
    @Scheduled(cron = "${sky.order.delay-queue.fallback-cron:0 */30 * * * ?}")
    public void dealWithTimeoutOrder() {
        log.info("低频补偿处理超时待付款的订单");

        // 1) 查出符合“超时 + 待付款”条件的订单。
        // timeoutBefore = 当前时间 - 超时时长。
        LocalDateTime timeoutBefore = LocalDateTime.now().minusMinutes(orderTimeoutProperties.getTimeoutMinutes());
        List<Orders> orders = orderMapper.findTimeoutOrdersBefore(timeoutBefore);
        if (!CollectionUtils.isEmpty(orders)) {
            for (Orders order : orders) {
                // 2) 不直接在 task 中改状态，而是复用队列主链路的 service。
                // 这样锁、状态机、CAS 都只维护一套实现。
                orderTimeoutCloseService.processTimeoutClose(OrderTimeoutCloseMessage.builder()
                        .orderNumber(order.getNumber())
                        .userId(order.getUserId())
                        .triggerAt(LocalDateTime.now())
                        .cancelReason("支付超时，系统自动取消订单")
                        .build());
            }
        }
    }

    /**
     * 每天凌晨 1 点扫描“派送中超时很久”的订单并尝试自动完成。
     *
     * 这部分不属于待付款超时取消主链路，但同样体现了“定时任务做状态补偿”的思路。
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void checkDeliveringOrders() {
        log.info("定时处理派送中的订单");

        // 1) 查出符合条件的派送中订单。
        List<Orders> orders = orderMapper.existsOrdersWithStatus();
        if (!CollectionUtils.isEmpty(orders)) {
            for (Orders order : orders) {
                // 2) 构造目标状态（完成）。
                order.setStatus(Orders.COMPLETED);
                order.setDeliveryTime(LocalDateTime.now());

                // 3) CAS 更新，确保只有“仍为派送中”的订单才会被改为完成。
                // 如果这期间订单已被其他流程改状态，本次更新只会影响 0 行。
                orderMapper.updateWithCondition(order, Orders.DELIVERY_IN_PROGRESS);
            }
        }
    }
}
