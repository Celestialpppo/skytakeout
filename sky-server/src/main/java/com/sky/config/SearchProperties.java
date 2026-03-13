package com.sky.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 搜索模块配置。
 *
 * 设计目的：
 * 1. 把 Elasticsearch 索引、RabbitMQ、Canal 参数统一外置；
 * 2. 通过开关控制不同运行模式（仅搜索 / 搜索+同步）；
 * 3. 便于在 dev/test/prod 环境按需覆盖配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sky.search")
public class SearchProperties {

    /**
     * 搜索总开关。
     */
    private boolean enabled = true;

    /**
     * 索引配置。
     */
    private Index index = new Index();

    /**
     * RabbitMQ 增量同步配置。
     */
    private Sync sync = new Sync();

    /**
     * Canal 客户端配置。
     */
    private Canal canal = new Canal();

    @Data
    public static class Index {

        /**
         * 统一搜索索引名。
         */
        private String goodsIndex = "goods_search";

        /**
         * 启动时是否自动执行全量重建。
         * 默认 false，避免生产环境误操作。
         */
        private boolean autoInitOnStartup = false;

        /**
         * 搜索接口允许的最大分页大小。
         */
        private int maxPageSize = 50;
    }

    @Data
    public static class Sync {

        /**
         * 增量同步开关。
         */
        private boolean enabled = false;

        /**
         * 主交换机。
         */
        private String exchange = "search.sync.exchange";

        /**
         * 主消费队列。
         */
        private String queue = "search.sync.queue";

        /**
         * 死信交换机。
         */
        private String deadLetterExchange = "search.sync.dlx";

        /**
         * 死信队列。
         */
        private String deadLetterQueue = "search.sync.dlq";

        /**
         * dish 路由键。
         */
        private String routingKeyDish = "search.sync.dish";

        /**
         * setmeal 路由键。
         */
        private String routingKeySetmeal = "search.sync.setmeal";

        /**
         * category 路由键。
         */
        private String routingKeyCategory = "search.sync.category";
    }

    @Data
    public static class Canal {

        /**
         * Canal 客户端开关。
         */
        private boolean enabled = false;

        /**
         * Canal Server 主机。
         */
        private String host = "127.0.0.1";

        /**
         * Canal Server 端口。
         */
        private int port = 11111;

        /**
         * Canal destination。
         */
        private String destination = "example";

        /**
         * Canal 用户名。
         */
        private String username;

        /**
         * Canal 密码。
         */
        private String password;

        /**
         * 订阅规则（库名.表名）。
         * 任意内容.dish
         * 任意内容.setmeal
         * 任意内容.category
         */
        private String filter = ".*\\.(dish|setmeal|category)";

        /**
         * 每次拉取批量大小。
         */
        private int batchSize = 100;

        /**
         * 无消息时轮询休眠（毫秒）。
         */
        private long idleSleepMs = 1000L;

        /**
         * 异常重试休眠（毫秒）。
         */
        private long errorSleepMs = 3000L;
    }
}
