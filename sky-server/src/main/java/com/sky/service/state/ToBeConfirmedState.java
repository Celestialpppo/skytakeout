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
 * 订单状态：待接单（TO_BE_CONFIRMED）。
 *
 * 这个状态下允许：
 * - 用户取消（通常需要退款）；
 * - 商家接单；
 * - 商家拒单/取消（通常需要退款）。
 */
@Service
@Slf4j
public class ToBeConfirmedState implements IOrderState<OrderStatus, OrderEvent> {

    @Autowired
    private OrderMapper orderMapper;

    @Override
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 待接单说明已经支付成功，不允许再次支付。
        throw new OrderBusinessException("订单已完成支付，不要重复付款");
    }

    @Override
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 1) 先让状态机判断“当前状态 + USER_CANCEL”是否合法。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.USER_CANCEL)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            // 2) 记录旧状态，后续做 CAS 条件更新，防止并发覆盖。
            Integer oldStatus = order.getStatus();

            // 3) 写入流转后的目标状态。
            order.setStatus(stateMachine.getState().getId().getState());

            // 4) 待接单取消通常涉及退款标记。
            order.setPayStatus(Orders.REFUND);
            order.setCancelReason("用户取消");
            order.setCancelTime(LocalDateTime.now());

            // 5) CAS 更新：只有数据库中 still = oldStatus 才更新成功。
            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                // result <= 0 说明状态被其他线程先一步改了，当前请求不再覆盖。
                log.warn("{}订单取消状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}取消成功", order.getNumber());
            return;
        }

        // 状态机不接受该事件，说明状态流转不合法。
        throw new OrderBusinessException("当前订单状态不支持用户取消");
    }

    @Override
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 商家接单事件。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.CONFIRMED)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单接单状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}已接单", order.getNumber());
            return;
        }

        throw new OrderBusinessException("当前订单状态不支持接单");
    }

    @Override
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        // 商家拒单/后台取消，走 ADMIN_CANCEL 事件。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order)
                .build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());

            // 拒单后通常也要退款。
            order.setPayStatus(Orders.REFUND);
            order.setRejectionReason(cancelReason);
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
        // 待接单不能直接派送。
        throw new OrderBusinessException("订单未接单，无法操作");
    }

    @Override
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 待接单不能直接完成。
        throw new OrderBusinessException("订单未接单，无法操作");
    }
}
