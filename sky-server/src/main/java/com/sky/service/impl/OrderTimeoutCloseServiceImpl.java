package com.sky.service.impl;

import com.sky.config.OrderTimeoutProperties;
import com.sky.entity.OrderTimeoutCloseMessage;
import com.sky.entity.Orders;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.mapper.OrderMapper;
import com.sky.service.OrderTimeoutCloseService;
import com.sky.service.state.OrderStateContext;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Redisson 延时队列订单超时关闭服务。
 *
 * 这是“基于 RDelayedQueue + 状态机的超时订单取消方案”的核心实现。
 * 它负责把下单成功后的订单接入延时消息主链路，并在消息到期后执行真正的关单动作。
 *
 * 这里同时解决了三类关键问题：
 * - 时序问题：通过 afterCommit 保证订单事务先提交，再投递消息；
 * - 并发问题：通过订单级分布式锁串行化“支付”和“关单”竞争；
 * - 业务问题：通过状态机和 CAS 更新保证状态流转合法且不会误覆盖。
 */
@Service
@Slf4j
public class OrderTimeoutCloseServiceImpl implements OrderTimeoutCloseService {

    /**
     * 支付相关分布式锁前缀。
     *
     * 超时关单与支付回调共用同一把锁，确保同一订单的支付和取消不会并发写库。
     */
    private static final String ORDER_PAY_LOCK_PREFIX = "order:pay:lock:";

    /**
     * 默认取消原因。
     */
    private static final String DEFAULT_CANCEL_REASON = "支付超时，系统自动取消订单";

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderStateContext orderStateContext;

    @Autowired
    private StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    @Autowired
    private OrderTimeoutProperties orderTimeoutProperties;

    /**
     * 把提交后的订单加入队列，设置超时任务
     * @param order
     */
    @Override
    public void enqueueAfterCommit(Orders order) {
        // 主链路总开关关闭时，直接返回，后续仅依赖定时补偿任务。
        if (!orderTimeoutProperties.getDelayQueue().isEnabled()) {
            return;
        }

        // 只构造最小消息体，消费时一律回库查询最新订单状态。
        OrderTimeoutCloseMessage message = OrderTimeoutCloseMessage.builder()
                .orderNumber(order.getNumber())
                .userId(order.getUserId())
                .triggerAt(LocalDateTime.now().plusMinutes(orderTimeoutProperties.getTimeoutMinutes())) //创建时间
                .cancelReason(DEFAULT_CANCEL_REASON)
                .build();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 存在事务时，不能立刻入队。
            // 否则事务回滚后，Redis 中会遗留一条找不到订单的脏消息。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { //在订单之后执行
                    try {
                        // 只有事务提交成功后，才真正投递延时关单消息。
                        enqueue(message, orderTimeoutProperties.getTimeoutMinutes());
                    } catch (Exception ex) {
                        // 这里不能再影响主业务，只能记录日志并等待定时补偿兜底。
                        log.error("订单事务提交后投递超时关闭消息失败，orderNumber={}", order.getNumber(), ex);
                    }
                }
            });
            return;
        }

        // 非事务场景直接入队。
        enqueue(message, orderTimeoutProperties.getTimeoutMinutes());
    }

    /**
     * 将订单超时的消息加入队列
     * @param message
     * @param delayMinutes
     */
    @Override
    public void enqueue(OrderTimeoutCloseMessage message, long delayMinutes) {
        // 功能关闭时不进行任何 Redis 队列操作。
        if (!orderTimeoutProperties.getDelayQueue().isEnabled()) {
            return;
        }
        // Redisson delayed queue 的使用方式是：
        // 1. 先获取实际消费的 blocking deque；
        // 2. 再基于它包装 delayed queue；
        // 3. 到期后 Redisson 自动把消息搬运到 blocking deque。
        RBlockingDeque<OrderTimeoutCloseMessage> blockingDeque = redissonClient
                .getBlockingDeque(orderTimeoutProperties.getDelayQueue().getQueueName());
        RDelayedQueue<OrderTimeoutCloseMessage> delayedQueue = redissonClient.getDelayedQueue(blockingDeque);
        delayedQueue.offer(message, delayMinutes, java.util.concurrent.TimeUnit.MINUTES);
        log.info("投递订单超时关闭消息成功，orderNumber={}, delayMinutes={}", message.getOrderNumber(), delayMinutes);
    }

    @Override
    public void processTimeoutClose(OrderTimeoutCloseMessage message) {
        // 消费前先做最基本的防御性校验，避免非法消息进入主逻辑。
        if (message == null || !StringUtils.hasText(message.getOrderNumber())) {
            log.warn("忽略非法的订单超时关闭消息: {}", message);
            return;
        }

        String orderNumber = message.getOrderNumber();
        RLock lock = redissonClient.getLock(ORDER_PAY_LOCK_PREFIX + orderNumber); //支付锁
        // 使用 tryLock 而不是一直阻塞等待。
        // 若支付线程当前持有锁，则本次先放弃，后续由低频补偿任务再次扫描兜底。
        boolean locked = lock.tryLock();
        if (!locked) {
            log.info("订单超时关闭获取支付锁失败，等待兜底补偿，orderNumber={}", orderNumber);
            return;
        }

        try {
            // 始终以数据库当前状态为准，不信任消息中的旧快照。
            Orders order = orderMapper.getOrderByOrderNumber(null, orderNumber);
            if (order == null) {
                log.warn("订单超时关闭跳过，订单不存在，orderNumber={}", orderNumber);
                return;
            }

            // 只有“仍处于待付款”的订单才应该被自动取消。
            if (!Objects.equals(order.getStatus(), Orders.PENDING_PAYMENT)) {
                log.info("订单超时关闭跳过，当前状态无需关单，orderNumber={}, status={}", orderNumber, order.getStatus());
                return;
            }

            // 统一走状态机，而不是在这里手写 update status = CANCELLED。
            // 这样超时取消和人工取消共用同一套状态流转规则。
            StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
            orderStateContext.adminCancel(order, stateMachine, defaultCancelReason(message));
            log.info("订单超时关闭成功，orderNumber={}", orderNumber);
        } catch (Exception ex) {
            log.error("订单超时关闭失败，orderNumber={}", orderNumber, ex);
        } finally {
            // 只在当前线程确实持有锁时才解锁，避免误释放。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String defaultCancelReason(OrderTimeoutCloseMessage message) {
        return StringUtils.hasText(message.getCancelReason()) ? message.getCancelReason() : DEFAULT_CANCEL_REASON;
    }

    private StateMachine<OrderStatus, OrderEvent> buildOrderStateMachine(Orders order) {
        // 使用订单号作为 stateMachineId，方便按订单维度观察日志。
        StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(order.getNumber());
        // 状态机实例使用前先 reset 到数据库当前状态。
        // 这样后续发送 ADMIN_CANCEL 时，判断依据一定是订单此刻的真实状态。
        stateMachine.stopReactively().block();
        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> access.resetStateMachineReactively(
                        new DefaultStateMachineContext<>(OrderStatus.fromState(order.getStatus()), null, null, null)
                ).block());
        stateMachine.startReactively().block();
        return stateMachine;
    }
}
