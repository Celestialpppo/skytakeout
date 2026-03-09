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
 * 订单状态：已完成（COMPLETED）。
 *
 * 这个状态下不允许再支付、接单、派送、完成。
 * 如需售后退款，可走后台取消/退款事件（具体以业务规则为准）。
 */
@Service
@Slf4j
public class CompleteState implements IOrderState<OrderStatus, OrderEvent> {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单已完成支付，不要重复付款");
    }

    @Override
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("请联系商家进行退款");
    }

    @Override
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单已完成，不要重复接单");
    }

    @Override
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        // 已完成订单，如果允许退款，走 ADMIN_CANCEL 事件。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            // 退款标识 + 取消原因。
            order.setPayStatus(Orders.REFUND);
            order.setCancelReason(cancelReason);
            order.setCancelTime(LocalDateTime.now());

            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单退款状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}取消成功", order.getNumber());
            return;
        }

        throw new OrderBusinessException("当前订单状态不支持退款");
    }

    @Override
    public void delivery(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单已完成，不要重复操作");
    }

    @Override
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单已完成，不要重复操作");
    }
}
