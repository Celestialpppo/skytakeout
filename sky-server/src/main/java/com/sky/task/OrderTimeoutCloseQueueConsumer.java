package com.sky.task;

import com.sky.config.OrderTimeoutProperties;
import com.sky.entity.OrderTimeoutCloseMessage;
import com.sky.service.OrderTimeoutCloseService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingDeque;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redisson 延时队列订单超时关闭消费者。
 *
 * 它不是一个 @Scheduled 轮询任务，而是在应用启动完成后自动拉起的后台阻塞消费者。
 * 整个主链路时序是：
 * 1. 下单成功后，消息进入 RDelayedQueue；
 * 2. 到期后，Redisson 把消息搬运到 RBlockingDeque；
 * 3. 当前线程在 take() 处被唤醒；
 * 4. 交给统一 service 执行超时关单。
 *
 * ApplicationRunner 启动时拉起消费者
 * DisposableBean 停机时停止消费者
 */
@Component
@Slf4j
public class OrderTimeoutCloseQueueConsumer implements ApplicationRunner, DisposableBean {

    private final RedissonClient redissonClient;

    private final OrderTimeoutProperties orderTimeoutProperties;

    private final OrderTimeoutCloseService orderTimeoutCloseService;

    /**
     * 单线程消费者池。
     *
     * 单线程足够覆盖这类低吞吐后台任务，且更方便排查消息处理顺序。
     */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "order-timeout-close-consumer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 停机标记。
     */
    private volatile boolean running = true;

    public OrderTimeoutCloseQueueConsumer(RedissonClient redissonClient,
                                          OrderTimeoutProperties orderTimeoutProperties,
                                          OrderTimeoutCloseService orderTimeoutCloseService) {
        this.redissonClient = redissonClient;
        this.orderTimeoutProperties = orderTimeoutProperties;
        this.orderTimeoutCloseService = orderTimeoutCloseService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ApplicationRunner 会在 Spring Boot 完成启动后执行，这里就是消费者启动点。
        if (!orderTimeoutProperties.getDelayQueue().isEnabled()) {
            log.info("Redisson 订单超时延时队列未启用");
            return;
        }
        // 提交一个长期运行的消费循环任务。
        executorService.submit(this::consumeLoop);
        log.info("Redisson 订单超时延时队列消费者已启动，queueName={}", orderTimeoutProperties.getDelayQueue().getQueueName());
    }

    private void consumeLoop() {
        // 实际消费的是 blocking deque，延时队列只是它的一个延时包装视图。
        RBlockingDeque<OrderTimeoutCloseMessage> blockingDeque = redissonClient
                .getBlockingDeque(orderTimeoutProperties.getDelayQueue().getQueueName());
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                // 没有消息时这里会阻塞等待，不会空转。
                OrderTimeoutCloseMessage message = blockingDeque.take();
                // 与定时兜底任务复用同一条 service 链路。
                orderTimeoutCloseService.processTimeoutClose(message);
            }
        } catch (InterruptedException ex) {
            // destroy() 触发 shutdownNow() 时会打断这里，这是正常退出路径。
            Thread.currentThread().interrupt();
            log.info("Redisson 订单超时延时队列消费者已停止");
        } catch (Exception ex) {
            // 主链路异常退出时，后续只能依赖定时补偿任务继续保障最终一致性。
            log.error("Redisson 订单超时延时队列消费者异常退出", ex);
        }
    }

    @Override
    public void destroy() {
        // 容器销毁时主动中断阻塞线程，避免应用退出时线程残留。
        running = false;
        executorService.shutdownNow();
    }
}
