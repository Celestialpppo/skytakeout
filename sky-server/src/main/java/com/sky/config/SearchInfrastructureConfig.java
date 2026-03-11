package com.sky.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 搜索模块基础设施配置。
 *
 * 当前提供：
 * - RabbitMQ 消息 JSON 转换器。
 */
@Configuration
public class SearchInfrastructureConfig {

    /**
     * 统一使用 JSON 传输消息，便于排查与跨服务兼容。
     */
    @Bean
    public MessageConverter searchMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
