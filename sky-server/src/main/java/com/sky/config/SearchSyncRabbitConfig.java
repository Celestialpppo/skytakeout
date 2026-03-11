package com.sky.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 搜索增量同步 RabbitMQ 拓扑配置。
 *
 * 拓扑结构：
 * 1. 主交换机（direct） + 主队列；
 * 2. 主队列绑定死信交换机/队列；
 * 3. dish/setmeal/category 各用 routing key 进入同一个主队列消费。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "sky.search",
        name = {"enabled", "sync.enabled"},
        havingValue = "true"
)
public class SearchSyncRabbitConfig {

    /**
     * 主交换机。
     */
    @Bean
    public DirectExchange searchSyncExchange(SearchProperties searchProperties) {
        return new DirectExchange(searchProperties.getSync().getExchange(), true, false);
    }

    /**
     * 死信交换机。
     */
    @Bean
    public DirectExchange searchSyncDeadLetterExchange(SearchProperties searchProperties) {
        return new DirectExchange(searchProperties.getSync().getDeadLetterExchange(), true, false);
    }

    /**
     * 主消费队列。
     *
     * 消费异常的消息会进入死信交换机，防止消息静默丢失。
     */
    @Bean
    public Queue searchSyncQueue(SearchProperties searchProperties) {
        return QueueBuilder.durable(searchProperties.getSync().getQueue())
                .withArgument("x-dead-letter-exchange", searchProperties.getSync().getDeadLetterExchange())
                .withArgument("x-dead-letter-routing-key", searchProperties.getSync().getDeadLetterQueue())
                .build();
    }

    /**
     * 死信队列。
     */
    @Bean
    public Queue searchSyncDeadLetterQueue(SearchProperties searchProperties) {
        return QueueBuilder.durable(searchProperties.getSync().getDeadLetterQueue()).build();
    }

    /**
     * dish 路由绑定。
     */
    @Bean
    public Binding dishBinding(
            SearchProperties searchProperties,
            @Qualifier("searchSyncExchange") DirectExchange searchSyncExchange,
            @Qualifier("searchSyncQueue") Queue searchSyncQueue
    ) {
        return BindingBuilder.bind(searchSyncQueue)
                .to(searchSyncExchange)
                .with(searchProperties.getSync().getRoutingKeyDish());
    }

    /**
     * setmeal 路由绑定。
     */
    @Bean
    public Binding setmealBinding(
            SearchProperties searchProperties,
            @Qualifier("searchSyncExchange") DirectExchange searchSyncExchange,
            @Qualifier("searchSyncQueue") Queue searchSyncQueue
    ) {
        return BindingBuilder.bind(searchSyncQueue)
                .to(searchSyncExchange)
                .with(searchProperties.getSync().getRoutingKeySetmeal());
    }

    /**
     * category 路由绑定。
     */
    @Bean
    public Binding categoryBinding(
            SearchProperties searchProperties,
            @Qualifier("searchSyncExchange") DirectExchange searchSyncExchange,
            @Qualifier("searchSyncQueue") Queue searchSyncQueue
    ) {
        return BindingBuilder.bind(searchSyncQueue)
                .to(searchSyncExchange)
                .with(searchProperties.getSync().getRoutingKeyCategory());
    }

    /**
     * 死信队列绑定。
     */
    @Bean
    public Binding deadLetterBinding(
            SearchProperties searchProperties,
            @Qualifier("searchSyncDeadLetterExchange") DirectExchange searchSyncDeadLetterExchange,
            @Qualifier("searchSyncDeadLetterQueue") Queue searchSyncDeadLetterQueue
    ) {
        return BindingBuilder.bind(searchSyncDeadLetterQueue)
                .to(searchSyncDeadLetterExchange)
                .with(searchProperties.getSync().getDeadLetterQueue());
    }
}
