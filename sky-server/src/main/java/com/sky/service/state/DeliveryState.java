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
 * 订单状态：派送中（DELIVERY_IN_PROGRESS）。
 *
 * 这个状态下允许：
 * - 商家取消（极端场景）；
 * - 完成订单（用户收货/系统完成）。
 */
@Service
@Slf4j
public class DeliveryState implements IOrderState<OrderStatus, OrderEvent> {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 派送中说明已支付，不允许再次支付。
        throw new OrderBusinessException("订单已完成支付，不要重复付款");
    }

    @Override
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 派送中用户端不允许直接取消。
        throw new OrderBusinessException("请联系商家沟通取消订单");
    }

    @Override
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 派送中不允许重复接单。
        throw new OrderBusinessException("订单派送中，不要重复接单");
    }

    @Override
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        // 后台取消（ADMIN_CANCEL）。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            // 已支付订单取消，支付状态需标记退款。
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
        // 派送中不允许重复派送。
        throw new OrderBusinessException("订单已经派送中，不要重复操作");
    }

    @Override
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 完成事件（RECEIVE）。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.RECEIVE)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            // 完成时记录送达/完成时间。
            order.setDeliveryTime(LocalDateTime.now());

            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单完成状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}已完成", order.getNumber());
            return;
        }

        throw new OrderBusinessException("当前订单状态不支持完成");
    }
}
