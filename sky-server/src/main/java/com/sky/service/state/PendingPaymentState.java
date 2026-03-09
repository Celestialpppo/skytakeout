package com.sky.service.state;

import com.alibaba.fastjson.JSON;
import com.sky.entity.Orders;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.OrderMapper;
import com.sky.websocket.WebSocketServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.StateMachineEventResult;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PendingPaymentState implements IOrderState<OrderStatus, OrderEvent>{

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private WebSocketServer webSocketServer;

    @Override
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {

        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.PAY)
                .setHeader("order", order)
                .build();

        Boolean acceptedObj = stateMachine.sendEvent(Mono.just(event))
                .any(result -> result.getResultType() == StateMachineEventResult.ResultType.ACCEPTED)
                .block();

        boolean accepted = Boolean.TRUE.equals(acceptedObj);

        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());
            order.setPayStatus(Orders.PAID);
            order.setCheckoutTime(LocalDateTime.now());
            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单支付状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}订单支付成功", order.getNumber());

            // 发送WebSocket通知
            Map<String, Object> message = new HashMap<>();
            message.put("type", 1);
            message.put("orderId", order.getId());
            message.put("content", "订单号: " + order.getNumber());

            webSocketServer.sendToAllClient(JSON.toJSONString(message));
        }
    }

    @Override
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        // 待付款状态下取消订单
        // 直接修改订单状态为已取消即可
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.USER_CANCEL)
                .setHeader("order", order).build();

        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());
            order.setCancelReason("用户取消");
            order.setCancelTime(LocalDateTime.now());
            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单取消状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}取消成功", order.getNumber());
        }
    }

    @Override
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("用户未完成付款，接单失败");
    }

    @Override
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        // 商家的取消订单操作
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order).build();
        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());
            order.setCancelReason(cancelReason);
            order.setCancelTime(LocalDateTime.now());
            int result = orderMapper.updateWithCondition(order, oldStatus);
            if (result <= 0) {
                log.warn("{}订单取消状态更新失败，可能已被其他线程处理", order.getNumber());
                throw new OrderBusinessException("订单状态已更新，请刷新页面重试");
            }
            log.info("{}取消成功", order.getNumber());
        }
    }

    @Override
    public void delivery(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单未完成支付，无法操作");
    }

    @Override
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        throw new OrderBusinessException("订单未完成支付，不要重复操作");
    }
}
