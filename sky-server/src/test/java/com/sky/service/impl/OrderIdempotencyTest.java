package com.sky.service.impl;

import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.Orders;
import com.sky.enums.OrderStatus;
import com.sky.mapper.OrderMapper;
import com.sky.service.IdempotentService;
import com.sky.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Slf4j
@SpringBootTest
@Transactional
@Rollback
public class OrderIdempotencyTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private IdempotentService idempotentService;

    @Autowired
    private OrderMapper orderMapper;

    private OrdersSubmitDTO ordersSubmitDTO;
    private String submitToken;

    @BeforeEach
    public void setUp() {
        // 生成幂等token
        submitToken = idempotentService.generateToken();

        // 构建测试订单数据
        ordersSubmitDTO = new OrdersSubmitDTO();
        ordersSubmitDTO.setAddressBookId(1L);
        ordersSubmitDTO.setPayMethod(1);
        ordersSubmitDTO.setRemark("测试订单");
        ordersSubmitDTO.setToken(submitToken);
    }

    @Test
    public void testOrderSubmitIdempotency() {
        // 第一次提交订单
        Long orderId1 = orderService.submitOrder(ordersSubmitDTO);
        assertNotNull(orderId1);

        // 第二次提交相同token的订单
        Long orderId2 = orderService.submitOrder(ordersSubmitDTO);
        assertNull(orderId2); // 应该返回null，表示订单已存在

        // 验证数据库中只有一个订单
        Orders order = orderMapper.getOrderByOrderId(orderId1);
        assertNotNull(order);
        assertEquals(OrderStatus.PENDING_PAYMENT.getState(), order.getStatus());
    }

    @Test
    public void testConcurrentOrderStatusUpdate() throws InterruptedException {
        // 创建订单
        Long orderId = orderService.submitOrder(ordersSubmitDTO);
        Orders order = orderMapper.getOrderByOrderId(orderId);
        assertNotNull(order);

        // 模拟并发更新订单状态
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    // 尝试更新订单状态为已支付
                    order.setPayStatus(Orders.PAID);
                    order.setStatus(OrderStatus.TO_BE_CONFIRMED.getState());
                    order.setCheckoutTime(LocalDateTime.now());
                    
                    int result = orderMapper.updateWithCondition(order, OrderStatus.PENDING_PAYMENT.getState());
                    if (result > 0) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("更新订单状态失败", e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // 验证只有一个线程成功更新
        assertEquals(1, successCount.get());

        // 验证订单状态已更新
        Orders updatedOrder = orderMapper.getOrderByOrderId(orderId);
        assertEquals(OrderStatus.TO_BE_CONFIRMED.getState(), updatedOrder.getStatus());
        assertEquals(Orders.PAID, updatedOrder.getPayStatus());
    }

    @Test
    public void testPaymentCallbackIdempotency() {
        // 创建订单
        Long orderId = orderService.submitOrder(ordersSubmitDTO);
        Orders order = orderMapper.getOrderByOrderId(orderId);
        assertNotNull(order);

        // 模拟支付回调
        PayCallbackServiceImpl payCallbackService = new PayCallbackServiceImpl();
        
        // 第一次回调
        boolean firstCallback = payCallbackService.handlePayCallback(order.getNumber(), "SUCCESS");
        assertTrue(firstCallback);

        // 第二次相同订单的回调
        boolean secondCallback = payCallbackService.handlePayCallback(order.getNumber(), "SUCCESS");
        assertFalse(secondCallback); // 应该返回false，表示已处理过

        // 验证订单状态已更新
        Orders updatedOrder = orderMapper.getOrderByOrderId(orderId);
        assertEquals(Orders.PAID, updatedOrder.getPayStatus());
    }
}
