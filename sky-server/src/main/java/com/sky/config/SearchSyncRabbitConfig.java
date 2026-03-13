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
        //durable=true：RabbitMQ 重启后交换机仍然保留
        //autoDelete=false：不会在“不再使用”时自动删除
        //DirectExchange不同业务类型发不同 routing key，但都能精确映射到你定义的绑定关系。
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
     *      * 当消费者处理失败后，
     *      * 如果消息被 reject/nack，并且 requeue=false，
     *      * 或者满足其它死信条件，
     *      * 它才会进入死信交换机。
     */
    @Bean
    public Queue searchSyncQueue(SearchProperties searchProperties) {
        return QueueBuilder.durable(searchProperties.getSync().getQueue()) //持久化队列
                //当这个主队列里的某条消息变成死信时，把它转发到的死信交换机的名称。
                .withArgument("x-dead-letter-exchange", searchProperties.getSync().getDeadLetterExchange())
                //消息被送到死信交换机时，使用哪个 routing key。
                .withArgument("x-dead-letter-routing-key", searchProperties.getSync().getDeadLetterQueue())
                .build();
    }

    /**
     * 死信队列。
     */
    @Bean
    public Queue searchSyncDeadLetterQueue(SearchProperties searchProperties) {
        //创建一个持久化死信队列，用来真正存放失败消息。
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
