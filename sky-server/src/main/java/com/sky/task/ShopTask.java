package com.sky.task;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
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
 * 注意：
 * - 这里也存在并发竞争（例如刚好支付成功与超时关单同时发生）；
 * - 所以本次改造后统一使用条件更新（CAS）避免错误覆盖状态。
 */
@Component
@Slf4j
public class ShopTask {

    @Autowired
    private OrderMapper orderMapper;

    /**
     * 每分钟扫描一次“待付款且超时”的订单并尝试关单。
     */
    @Scheduled(cron = "0 * * * * ?")
    public void dealWithTimeoutOrder() {
        log.info("定时处理超时待付款的订单");

        // 1) 查出符合“超时 + 待付款”条件的订单。
        List<Orders> orders = orderMapper.findTimeoutOrders();
        if (!CollectionUtils.isEmpty(orders)) {
            for (Orders order : orders) {
                // 2) 构造目标状态（取消）。
                order.setStatus(Orders.CANCELLED);
                order.setCancelTime(LocalDateTime.now());
                order.setCancelReason("支付超时，取消订单");

                // 3) CAS 更新，只有原状态仍是 PENDING_PAYMENT 才允许改成 CANCELLED。
                //    这样可以防止“刚支付成功却被定时任务误关单”。
                orderMapper.updateWithCondition(order, Orders.PENDING_PAYMENT);
            }
        }
    }

    /**
     * 每天凌晨 1 点扫描“派送中超时很久”的订单并尝试自动完成。
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
                orderMapper.updateWithCondition(order, Orders.DELIVERY_IN_PROGRESS);
            }
        }
    }
}
