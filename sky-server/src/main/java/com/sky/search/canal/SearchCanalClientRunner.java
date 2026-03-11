package com.sky.search.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.sky.config.SearchProperties;
import com.sky.search.model.SearchSyncMessage;
import com.sky.search.mq.SearchSyncMessageProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Canal 客户端启动器。
 *
 * 角色定位：
 * - 只负责把 MySQL binlog 变更采集出来并投递到 RabbitMQ；
 * - 不直接操作 Elasticsearch；
 * - 真正的数据同步动作由 MQ 消费端回查 MySQL 后执行。
 */
@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "sky.search",
        name = {"enabled", "sync.enabled", "canal.enabled"},
        havingValue = "true"
)
public class SearchCanalClientRunner implements ApplicationRunner, DisposableBean {

    @Autowired
    private SearchProperties searchProperties;

    @Autowired
    private SearchSyncMessageProducer searchSyncMessageProducer;

    /**
     * 单线程消费 Canal，保证同一实例内的消息顺序可追踪。
     */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "search-canal-client");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 运行状态标记。
     */
    private volatile boolean running = true;

    /**
     * Canal 连接对象，供停机时释放连接。
     */
    private volatile CanalConnector connector;

    @Override
    public void run(ApplicationArguments args) {
        executorService.submit(this::consumeLoop);
    }

    private void consumeLoop() {
        SearchProperties.Canal canal = searchProperties.getCanal();
        connector = CanalConnectors.newSingleConnector(
                new InetSocketAddress(canal.getHost(), canal.getPort()),
                canal.getDestination(),
                canal.getUsername(),
                canal.getPassword()
        );

        try {
            connector.connect();
            connector.subscribe(canal.getFilter());
            connector.rollback();
            log.info("Canal 客户端已启动，destination={}, filter={}", canal.getDestination(), canal.getFilter());

            while (running && !Thread.currentThread().isInterrupted()) {
                Message message = connector.getWithoutAck(canal.getBatchSize());
                long batchId = message.getId();
                int size = message.getEntries().size();

                if (batchId == -1 || size == 0) {
                    safeSleep(canal.getIdleSleepMs());
                    continue;
                }

                try {
                    dispatchMessage(message);
                    connector.ack(batchId);
                } catch (Exception ex) {
                    // 当前批次解析或发送失败，回滚后等待下次重试。
                    connector.rollback(batchId);
                    log.error("Canal 批次处理失败，batchId={}, size={}", batchId, size, ex);
                    safeSleep(canal.getErrorSleepMs());
                }
            }
        } catch (Exception ex) {
            if (running) {
                log.error("Canal 客户端异常退出", ex);
            }
        } finally {
            disconnectQuietly();
            log.info("Canal 客户端已停止");
        }
    }

    /**
     * 解析 Canal 批次并投递最小消息到 RabbitMQ。
     */
    private void dispatchMessage(Message message) throws Exception {
        for (CanalEntry.Entry entry : message.getEntries()) {
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) {
                continue;
            }

            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();

            // 只关心新增、修改、删除。
            if (eventType != CanalEntry.EventType.INSERT
                    && eventType != CanalEntry.EventType.UPDATE
                    && eventType != CanalEntry.EventType.DELETE) {
                continue;
            }

            String tableName = entry.getHeader().getTableName();
            LocalDateTime eventTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.getHeader().getExecuteTime()),
                    ZoneId.systemDefault()
            );

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Long id = extractPrimaryKey(rowData, eventType);
                if (id == null) {
                    log.warn("Canal 变更缺少主键，tableName={}, eventType={}", tableName, eventType);
                    continue;
                }

                SearchSyncMessage syncMessage = SearchSyncMessage.builder()
                        .tableName(tableName)
                        .operation(eventType.name())
                        .id(id)
                        .eventTime(eventTime)
                        .build();
                searchSyncMessageProducer.send(syncMessage);
            }
        }
    }

    /**
     * 从行变更中提取主键。
     *
     * DELETE 场景主键在 beforeColumns，INSERT/UPDATE 场景主键在 afterColumns。
     */
    private Long extractPrimaryKey(CanalEntry.RowData rowData, CanalEntry.EventType eventType) {
        List<CanalEntry.Column> columns = eventType == CanalEntry.EventType.DELETE
                ? rowData.getBeforeColumnsList()
                : rowData.getAfterColumnsList();

        // 优先按字段名 id 提取，便于可读。
        for (CanalEntry.Column column : columns) {
            if ("id".equalsIgnoreCase(column.getName())) {
                return parseLong(column.getValue());
            }
        }

        // 兜底：按 Canal 标记的主键列提取。
        for (CanalEntry.Column column : columns) {
            if (column.getIsKey()) {
                return parseLong(column.getValue());
            }
        }
        return null;
    }

    private Long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void safeSleep(long sleepMs) {
        if (sleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void disconnectQuietly() {
        CanalConnector current = this.connector;
        if (current != null) {
            try {
                current.disconnect();
            } catch (Exception ex) {
                log.warn("Canal 连接关闭异常", ex);
            }
        }
    }

    @Override
    public void destroy() {
        running = false;
        executorService.shutdownNow();
        disconnectQuietly();
    }
}
