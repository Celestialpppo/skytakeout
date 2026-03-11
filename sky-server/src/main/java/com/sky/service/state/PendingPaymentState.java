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
        // 先把 PAY 事件交给状态机判断是否合法，而不是直接 setStatus。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.PAY)
                .setHeader("order", order)
                .build();

        Boolean acceptedObj = stateMachine.sendEvent(Mono.just(event))
                .any(result -> result.getResultType() == StateMachineEventResult.ResultType.ACCEPTED)
                .block();

        boolean accepted = Boolean.TRUE.equals(acceptedObj);

        if (accepted) {
            // 状态机接受事件后，真正落库时仍然要做 CAS，防止并发覆盖。
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
        // 待付款状态下允许用户主动取消。
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
        // 超时自动取消最终会落到这里：
        // OrderTimeoutCloseServiceImpl -> OrderStateContext.adminCancel -> PendingPaymentState.adminCancel
        //
        // 也就是说，超时取消和后台人工取消复用的是同一套状态机事件流转代码，
        // 区别只是传入的取消原因不同。
        Message<OrderEvent> event = MessageBuilder.withPayload(OrderEvent.ADMIN_CANCEL)
                .setHeader("order", order).build();
        boolean accepted = stateMachine.sendEvent(event);
        if (accepted) {
            Integer oldStatus = order.getStatus();
            order.setStatus(stateMachine.getState().getId().getState());
            order.setCancelReason(cancelReason);
            order.setCancelTime(LocalDateTime.now());
            // CAS 更新用于防止典型竞态：
            // 1. 当前线程判断订单仍待付款；
            // 2. 支付线程先一步把订单改成已支付；
            // 3. 如果这里无条件 update，就会把已支付订单错误改成已取消。
            int rowsAffected = orderMapper.updateWithCondition(order, oldStatus);
            if (rowsAffected <= 0) {
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
