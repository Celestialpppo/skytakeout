package com.sky.search.mq;

import com.sky.search.model.SearchSyncMessage;
import com.sky.service.SearchSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 搜索增量同步消息消费者。
 *
 * 说明：
 * - 消费失败会抛异常，由 RabbitMQ 投递到死信队列；
 * - 业务更新由 SearchSyncService 统一处理。
 */
@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "sky.search",
        name = {"enabled", "sync.enabled"},
        havingValue = "true"
)
public class SearchSyncMessageConsumer {

    @Autowired
    private SearchSyncService searchSyncService;

    @RabbitListener(queues = "${sky.search.sync.queue}")
    public void consume(SearchSyncMessage message) {
        if (message == null || message.getId() == null || !StringUtils.hasText(message.getTableName())) {
            log.warn("收到无效搜索增量消息，直接忽略：{}", message);
            return;
        }

        try {
            searchSyncService.syncIncrementalChange(message.getTableName(), message.getOperation(), message.getId());
            log.debug("搜索增量消息消费完成，tableName={}, operation={}, id={}",
                    message.getTableName(), message.getOperation(), message.getId());
        } catch (Exception ex) {
            // 抛出异常给监听容器，触发 reject -> dead letter。
            log.error("搜索增量消息消费失败，tableName={}, operation={}, id={}",
                    message.getTableName(), message.getOperation(), message.getId(), ex);
            throw ex;
        }
    }
}
