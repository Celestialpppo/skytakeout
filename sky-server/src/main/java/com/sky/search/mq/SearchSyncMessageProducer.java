package com.sky.search.mq;

import com.sky.config.SearchProperties;
import com.sky.search.model.SearchSyncMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 搜索增量同步消息生产者。
 */
@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "sky.search",
        name = {"enabled", "sync.enabled"},
        havingValue = "true"
)
public class SearchSyncMessageProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SearchProperties searchProperties;

    /**
     * 发送增量消息到主交换机。
     */
    public void send(SearchSyncMessage message) {
        if (message == null || message.getId() == null || !StringUtils.hasText(message.getTableName())) {
            log.warn("忽略无效搜索增量消息：{}", message);
            return;
        }

        String routingKey = resolveRoutingKey(message.getTableName());
        if (!StringUtils.hasText(routingKey)) {
            log.debug("忽略不在同步范围内的表变更消息，tableName={}", message.getTableName());
            return;
        }

        //把message发到交换机，交换机按照routingKey去做路由
        rabbitTemplate.convertAndSend(searchProperties.getSync().getExchange(), routingKey, message);
        log.debug("发送搜索增量消息成功，tableName={}, operation={}, id={}, routingKey={}",
                message.getTableName(), message.getOperation(), message.getId(), routingKey);
    }

    private String resolveRoutingKey(String tableName) {
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "dish":
                return searchProperties.getSync().getRoutingKeyDish();
            case "setmeal":
                return searchProperties.getSync().getRoutingKeySetmeal();
            case "category":
                return searchProperties.getSync().getRoutingKeyCategory();
            default:
                return null;
        }
    }
}
