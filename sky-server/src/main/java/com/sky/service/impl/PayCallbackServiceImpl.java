package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.entity.Orders;
import com.sky.entity.PaymentCallbackLog;
import com.sky.entity.PaymentTxn;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.PaymentCallbackLogMapper;
import com.sky.mapper.PaymentTxnMapper;
import com.sky.service.PayCallbackService;
import com.sky.service.state.OrderStateContext;
import com.sky.utils.Md5Util;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 支付回调服务实现类
 */
@Service
@Slf4j
public class PayCallbackServiceImpl implements PayCallbackService {

    /**
     * 支付锁前缀。
     * 与 payment 发起接口保持一致，确保“发起支付”和“处理回调”不会并发互相覆盖。
     */
    private static final String ORDER_PAY_LOCK_PREFIX = "order:pay:lock:";

    /**
     * 微信渠道标识（写入支付流水与回调日志）。
     */
    private static final String CHANNEL_WECHAT = "wechat";

    /**
     * 支付宝渠道标识（写入支付流水与回调日志）。
     */
    private static final String CHANNEL_ALIPAY = "alipay";

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StateMachineFactory<OrderStatus, OrderEvent> stateMachineFactory;

    @Autowired
    private OrderStateContext orderStateContext;

    @Autowired
    private PaymentCallbackLogMapper paymentCallbackLogMapper;

    @Autowired
    private PaymentTxnMapper paymentTxnMapper;

    @Override
    public String handleWechatCallback(Map<String, Object> callbackData) {
        // 微信回调成功时按微信协议返回 XML success。
        boolean success = processCallback(callbackData, CHANNEL_WECHAT, "out_trade_no", "transaction_id");
        if (success) {
            return "<xml><return_code><![CDATA[SUCCESS]]></return_code><return_msg><![CDATA[OK]]></return_msg></xml>";
        }
        // 返回 FAIL 让微信侧按策略重试，避免因为临时异常丢回调。
        return "<xml><return_code><![CDATA[FAIL]]></return_code><return_msg><![CDATA[RETRY]]></return_msg></xml>";
    }

    @Override
    public String handleAlipayCallback(Map<String, Object> callbackData) {
        // 支付宝约定返回 success/fail 文本。
        boolean success = processCallback(callbackData, CHANNEL_ALIPAY, "out_trade_no", "trade_no");
        return success ? "success" : "fail";
    }

    private boolean processCallback(Map<String, Object> callbackData,
                                    String channel,
                                    String orderNumberField,
                                    String transactionIdField) {
        // 1) 从回调参数中抽取订单号与三方交易号。
        String orderNumber = asString(callbackData.get(orderNumberField));
        String transactionId = asString(callbackData.get(transactionIdField));
        if (!StringUtils.hasText(orderNumber) || !StringUtils.hasText(transactionId)) {
            log.error("支付回调参数缺失，channel={}, data={}", channel, callbackData);
            return false; //有可能抢到锁的订单还在处理
        }

        // 2) 构造回调幂等键（同订单 + 同交易号 = 同一次回调事实）。
        // 防止重复通知，重复回调。查数据库，如果数据库返回的是该订单没有被处理成功，则两个线程都继续。
        String callbackId = generateCallbackId(orderNumber, transactionId); //回调id唯一
        PaymentCallbackLog existed = paymentCallbackLogMapper.getByCallbackId(callbackId); //查回调数据表
        if (existed != null && PaymentCallbackLog.SUCCESS.equals(existed.getStatus())) {
            // 若已成功处理，直接幂等返回成功。
            log.info("支付回调已处理，channel={}, orderNumber={}", channel, orderNumber);
            return true;
        }

        // 3) 订单级分布式锁：确保同一订单回调串行处理。
        // 防止多线程对同一订单回调进行处理
        RLock lock = redissonClient.getLock(ORDER_PAY_LOCK_PREFIX + orderNumber);
        if (!lock.tryLock()) {
            // 不立刻返回 success，避免“本节点没处理成功却告诉平台成功”的丢单风险。
            log.warn("支付回调获取订单锁失败，等待重试，channel={}, orderNumber={}", channel, orderNumber);
            return false;
        }

        PaymentCallbackLog callbackLog = null;
        PaymentTxn paymentTxn = null;
        try {
            // 4) 双重检查：拿到锁后再查一次幂等日志，防止锁等待期间已被别的线程处理。
            // 因为在你抢锁之前，可能发生了这件事：
            //线程 A 先查幂等日志，没成功
            //线程 B 也查幂等日志，没成功
            //线程 A 抢到锁，处理成功，写入 SUCCESS
            //线程 A 释放锁
            //线程 B 这时终于拿到了锁
            //如果 B 不再查一次，就会把已经成功处理过的回调再处理一遍。
            existed = paymentCallbackLogMapper.getByCallbackId(callbackId);
            if (existed != null && PaymentCallbackLog.SUCCESS.equals(existed.getStatus())) {
                return true;
            }

            // 5) 将回调日志置为 processing（首次插入或重试更新）。
            callbackLog = initOrUpdateProcessingLog(existed, callbackId, orderNumber, transactionId, channel, callbackData);

            // 6) 查询订单主记录。
            // 既然回调里说“某个订单支付成功了”，那你得先确认你自己系统里真有这个订单。
            Orders order = orderMapper.getOrderByOrderNumber(null, orderNumber);
            if (order == null) {
                // 订单不存在通常不应返回 success，避免上游误认为处理完成。
                markCallbackFail(callbackLog, "订单不存在");
                return false;
            }

            // 7) 查询/补齐支付流水（兼容先回调后发起记录的极端场景）。
            // 正常情况下，这条记录应该在用户点“去支付”的 payment() 阶段就已经插入了。
            // 为什么回调阶段还要补建？
            //因为真实系统里可能出现极端时序：
            //前端发起支付
            //还没来得及把支付流水完整写好
            //回调先到了
            paymentTxn = paymentTxnMapper.getByOrderNumber(orderNumber);
            if (paymentTxn == null) {
                paymentTxn = new PaymentTxn();
                paymentTxn.setPayRequestId(UUID.randomUUID().toString().replace("-", ""));
                paymentTxn.setOrderNumber(orderNumber);
                paymentTxn.setUserId(order.getUserId());
                paymentTxn.setPayAmount(order.getAmount());
                paymentTxn.setPayMethod(order.getPayMethod());
                paymentTxn.setStatus(PaymentTxn.WAITING);
                paymentTxn.setRequestTime(LocalDateTime.now());
                paymentTxn.setCreateTime(LocalDateTime.now());
                paymentTxn.setUpdateTime(LocalDateTime.now());
                paymentTxnMapper.insert(paymentTxn);
            }

            // 8) 若订单已不是待支付，说明状态已被其他流程推进（例如并发回调）。
            //    这里幂等收敛为成功即可，避免重复报错。
            if (!Orders.PENDING_PAYMENT.equals(order.getStatus())) {
                markPaymentSuccess(paymentTxn, transactionId, channel);
                markCallbackSuccess(callbackLog);
                return true;
            }

            // 9) 真正执行状态机流转：PENDING_PAYMENT -> TO_BE_CONFIRMED。
            StateMachine<OrderStatus, OrderEvent> stateMachine = buildOrderStateMachine(order);
            orderStateContext.pay(order, stateMachine);

            // 10) 状态流转成功后，更新支付流水与回调日志为成功。
            markPaymentSuccess(paymentTxn, transactionId, channel);
            markCallbackSuccess(callbackLog);

            log.info("支付回调处理成功，channel={}, orderNumber={}", channel, orderNumber);
            return true;
        } catch (Exception ex) {
            // 11) 并发容错：如果异常后再次查询发现状态已推进，说明可能是并发线程先成功了。
            Orders latestOrder = orderMapper.getOrderByOrderNumber(null, orderNumber);
            if (latestOrder != null && !Orders.PENDING_PAYMENT.equals(latestOrder.getStatus())) {
                if (paymentTxn != null) {
                    markPaymentSuccess(paymentTxn, transactionId, channel);
                }
                if (callbackLog != null) {
                    markCallbackSuccess(callbackLog);
                }
                log.warn("支付回调并发场景转成功，channel={}, orderNumber={}", channel, orderNumber);
                return true;
            }

            // 12) 真失败则记录失败日志，返回 false 让支付平台重试。
            if (callbackLog != null) {
                markCallbackFail(callbackLog, ex.getMessage());
            }
            log.error("支付回调处理失败，channel={}, orderNumber={}", channel, orderNumber, ex);
            return false;
        } finally {
            // 13) 释放锁。
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private PaymentCallbackLog initOrUpdateProcessingLog(PaymentCallbackLog existed,
                                                         String callbackId,
                                                         String orderNumber,
                                                         String transactionId,
                                                         String channel,
                                                         Map<String, Object> callbackData) {
        LocalDateTime now = LocalDateTime.now();
        if (existed == null) {
            // 首次回调：新建 processing 日志，保存原始报文快照。
            PaymentCallbackLog callbackLog = new PaymentCallbackLog();
            callbackLog.setCallbackId(callbackId);
            callbackLog.setOrderNumber(orderNumber);
            callbackLog.setTransactionId(transactionId);
            callbackLog.setChannel(channel);
            callbackLog.setStatus(PaymentCallbackLog.PROCESSING);
            callbackLog.setRawPayload(truncate(JSON.toJSONString(callbackData), 2000));
            callbackLog.setCreateTime(now);
            callbackLog.setUpdateTime(now);
            paymentCallbackLogMapper.insert(callbackLog);
            return callbackLog;
        }

        // 非首次回调：把状态重置为 processing 并清理旧失败原因，准备重试处理。
        existed.setStatus(PaymentCallbackLog.PROCESSING);
        existed.setFailReason(null);
        existed.setUpdateTime(now);
        paymentCallbackLogMapper.updateByCallbackId(existed);
        return existed;
    }

    private void markCallbackSuccess(PaymentCallbackLog callbackLog) {
        // 回调处理成功：记录完成时间，后续同 callbackId 请求可直接幂等通过。
        callbackLog.setStatus(PaymentCallbackLog.SUCCESS);
        callbackLog.setFailReason(null);
        callbackLog.setProcessTime(LocalDateTime.now());
        callbackLog.setUpdateTime(LocalDateTime.now());
        paymentCallbackLogMapper.updateByCallbackId(callbackLog);
    }

    private void markCallbackFail(PaymentCallbackLog callbackLog, String failReason) {
        // 回调处理失败：记录失败原因，便于排查，同时保留给上游重试。
        callbackLog.setStatus(PaymentCallbackLog.FAIL);
        callbackLog.setFailReason(truncate(failReason, 255));
        callbackLog.setProcessTime(LocalDateTime.now());
        callbackLog.setUpdateTime(LocalDateTime.now());
        paymentCallbackLogMapper.updateByCallbackId(callbackLog);
    }

    private void markPaymentSuccess(PaymentTxn paymentTxn, String transactionId, String channel) {
        // 支付流水落成功：写三方交易号、渠道、回调时间。
        paymentTxn.setTransactionId(transactionId);
        paymentTxn.setChannel(channel);
        paymentTxn.setStatus(PaymentTxn.SUCCESS);
        paymentTxn.setFailReason(null);
        paymentTxn.setCallbackTime(LocalDateTime.now());
        paymentTxn.setUpdateTime(LocalDateTime.now());
        paymentTxnMapper.updateById(paymentTxn);
    }

    private String generateCallbackId(String orderNumber, String transactionId) {
        // 使用 md5 生成固定长度幂等键，便于建唯一索引和日志查询。
        return Md5Util.md5(orderNumber + "|" + transactionId);
    }

    private String asString(Object value) {
        // 统一容错转换，避免 ClassCastException。
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String source, int maxLength) {
        // 截断字符串，防止写库超过字段长度导致二次异常。
        if (!StringUtils.hasText(source)) {
            return null;
        }
        return source.length() <= maxLength ? source : source.substring(0, maxLength);
    }

    private StateMachine<OrderStatus, OrderEvent> buildOrderStateMachine(Orders order) {
        // 1) 基于订单号获取状态机实例。
        StateMachine<OrderStatus, OrderEvent> stateMachine = stateMachineFactory.getStateMachine(order.getNumber());
        // 2) 先停止并重置到数据库状态，确保内存状态与数据库一致。
        stateMachine.stopReactively().block();
        stateMachine.getStateMachineAccessor()
                .doWithAllRegions(access -> access.resetStateMachineReactively(
                        new DefaultStateMachineContext<>(OrderStatus.fromState(order.getStatus()), null, null, null)
                ).block());
        // 3) 再启动用于处理事件。
        stateMachine.startReactively().block();
        return stateMachine;
    }
}
