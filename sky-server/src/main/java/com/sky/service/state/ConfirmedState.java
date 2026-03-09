package com.sky.service.state;

import com.sky.entity.Orders;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 订单状态：已接单（CONFIRMED）。
 *
 * 这个状态下允许：
 * - 商家取消（特殊场景）；
 * - 商家发起派送。
 */
@Service
@Slf4j
public class ConfirmedState implements IOrderState<OrderStatus, OrderEvent> {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 已接单意味着已付款，不允许重复支付。
        throw new OrderBusinessException("订单已完成支付，不要重复付款");
    }

    @Override
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 已接单后用户端不允许直接取消。
        throw new OrderBusinessException("请联系商家沟通取消订单");
    }

    @Override
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 已接单状态不允许再次接单。
        throw new OrderBusinessException("订单已接单，不要重复接单");
    }

    @Override
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        // 后台取消（ADMIN_CANCEL）
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            // 已支付订单取消通常需要设置退款标记。
            order.setPayStatus(Orders.REFUND);
            order.setCancelReason(cancelReason);
            order.setCancelTime(LocalDateTime.now());

            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单取消状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}取消成功", order.getNumber());
            return;
        }

        throw new OrderBusinessException("当前订单状态不支持取消");
    }

    @Override
    public void delivery(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 进入派送流程（DELIVERY 事件）。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.DELIVERY)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单派送状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}派送成功", order.getNumber());
            return;
        }

        throw new OrderBusinessException("当前订单状态不支持派送");
    }

    @Override
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 已接单状态不能直接完成，必须先进入派送中再完成。
        throw new OrderBusinessException("订单未派送，无法操作");
    }
}
