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
        thread.setDaemon(true); //守护线程，有线程工作的时候jvm不会退出，而只剩下它了jvm就会退出
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
                canal.getDestination(), //连 Canal 里的哪一个 instance / 通道。
                canal.getUsername(),//不是 MySQL 账号本身，而是 Canal 客户端连 Canal Server 时用的用户名
                canal.getPassword() //密码。具体是否启用，取决于 Canal 服务端配置。
        );

        try {
            //为什么 Canal 要设计成 getWithoutAck / ack / rollback, 这是它很关键的机制。
            //Canal 官方把这个叫 流式 API：
            //你可以先不断拉消息，再按顺序确认；失败时可以回滚到上次 ack 的位置重新消费。这样设计的好处是：
            connector.connect();
            connector.subscribe(canal.getFilter());//拉一批消息，但先不确认消费成功。
            connector.rollback();//这里不是“出错了才回滚”，而是启动时先把未确认位点回退到最近确认位置。
            // 这样做的意思是：从一个明确的消费位点开始，避免接到一个不确定状态继续跑。
            log.info("Canal 客户端已启动，destination={}, filter={}", canal.getDestination(), canal.getFilter());

            while (running && !Thread.currentThread().isInterrupted()) { //只要线程不中断，就一直拉binlog变更
                Message message = connector.getWithoutAck(canal.getBatchSize());//从 Canal 拉一批消息，但先不确认已经消费成功
                long batchId = message.getId();//这批消息的唯一编号 batchId
                int size = message.getEntries().size();//这批具体的数据项 entries

                if (batchId == -1 || size == 0) {
                    safeSleep(canal.getIdleSleepMs());//空闲等待一小会儿，Canal 客户端通常是轮询式消费，不是每次都有数据。
                    continue;
                }

                try {
                    dispatchMessage(message);//把 Canal 拉到的一批原始 binlog 变更，筛选并解析成项目内部统一的搜索同步消息，然后发送到 RabbitMQ。
                    connector.ack(batchId);//这批batch已经处理成功，确认消费完成
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
            if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) { //如果有行数据变更
                continue; //	entryType 	[事务头BEGIN/事务尾END/数据ROWDATA]
            }
            //entry.getStoreValue() 里还是 Canal 的原始二进制内容。
            CanalEntry.RowChange rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
            CanalEntry.EventType eventType = rowChange.getEventType();

            // 只关心新增、修改、删除。
            if (eventType != CanalEntry.EventType.INSERT
                    && eventType != CanalEntry.EventType.UPDATE
                    && eventType != CanalEntry.EventType.DELETE) {
                continue;
            }

            String tableName = entry.getHeader().getTableName();//这条变更来自哪张表
            LocalDateTime eventTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.getHeader().getExecuteTime()),
                    ZoneId.systemDefault()
            );//这次数据库变更发生的时间

            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Long id = extractPrimaryKey(rowData, eventType); //那张表的主键
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
