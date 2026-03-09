package com.sky.service.state;

import com.sky.entity.Orders;
import com.sky.enums.OrderEvent;
import com.sky.enums.OrderStatus;
import com.sky.exception.OrderBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.statemachine.StateMachine;
import org.springframework.stereotype.Service;

/**
 * 订单状态上下文。
 *
 * 这一版是“无状态上下文”实现，避免了旧实现中把 order/stateMachine
 * 存在成员变量造成的并发串单风险。
 *
 * 使用方式：
 * - 每次调用都把 order + stateMachine 作为参数传入；
 * - 上下文仅负责“根据订单当前状态，路由到对应状态处理器”。
 */
@Service
public class OrderStateContext {

    @Autowired
    private PendingPaymentState pendingPaymentState;

    @Autowired
    private ToBeConfirmedState toBeConfirmedState;

    @Autowired
    private ConfirmedState confirmedState;

    @Autowired
    private CanceledState canceledState;

    @Autowired
    private DeliveryState deliveryState;

    @Autowired
    private CompleteState completeState;

    /**
     * 根据订单当前状态，选择具体状态处理器。
     */
    private IOrderState<OrderStatus, OrderEvent> getOrderState(Orders order) {
        OrderStatus orderStatus = OrderStatus.fromState(order.getStatus());
        switch (orderStatus) {
            case PENDING_PAYMENT:
                return pendingPaymentState;
            case TO_BE_CONFIRMED:
                return toBeConfirmedState;
            case CONFIRMED:
                return confirmedState;
            case DELIVERY_IN_PROGRESS:
                return deliveryState;
            case COMPLETED:
                return completeState;
            case CANCELLED:
                return canceledState;
            default:
                // 理论上不会走到这里；如果走到，说明订单状态值非法。
                throw new OrderBusinessException("不存在的订单状态");
        }
    }

    /**
     * 触发支付事件（PAY）。
     */
    public void pay(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        getOrderState(order).pay(order, stateMachine);
    }

    /**
     * 触发用户取消事件（USER_CANCEL）。
     */
    public void userCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        getOrderState(order).userCancel(order, stateMachine);
    }

    /**
     * 触发商家接单事件（CONFIRMED）。
     */
    public void confirmOrder(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        getOrderState(order).confirmOrder(order, stateMachine);
    }

    /**
     * 触发后台取消/拒单事件（ADMIN_CANCEL）。
     */
    public void adminCancel(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine, String cancelReason) {
        getOrderState(order).adminCancel(order, stateMachine, cancelReason);
    }

    /**
     * 触发派送事件（DELIVERY）。
     */
    public void delivery(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        getOrderState(order).delivery(order, stateMachine);
    }

    /**
     * 触发完成事件（RECEIVE）。
     */
    public void complete(Orders order, StateMachine<OrderStatus, OrderEvent> stateMachine) {
        getOrderState(order).complete(order, stateMachine);
    }
}
