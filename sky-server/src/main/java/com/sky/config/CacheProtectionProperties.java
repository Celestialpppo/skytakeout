package com.sky.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存防护配置。
 *
 * 这一组配置同时覆盖：
 * 1. 布隆过滤器防止不存在 ID 反复穿透缓存；
 * 2. TTL + 抖动降低批量缓存同时失效带来的雪崩风险；
 * 3. 空值缓存拦截短期重复回源。
 */
@Data
@Component
@ConfigurationProperties(prefix = "sky.cache.protection")
public class CacheProtectionProperties {

    /**
     * 总开关。
     */
    private boolean enabled = true;

    /**
     * 布隆过滤器配置。
     */
    private Bloom bloom = new Bloom();

    /**
     * TTL 配置。
     */
    private Ttl ttl = new Ttl();

    @Data
    public static class Bloom {

        /**
         * 布隆过滤器开关。
         */
        private boolean enabled = true;

        private Filter category = new Filter();

        private Filter setmeal = new Filter();
    }

    @Data
    public static class Filter {

        /**
         * 预估容量。
         */
        private long expectedInsertions = 10000L;

        /**
         * 可接受误判率。
         */
        private double falseProbability = 0.01D;
    }

    @Data
    public static class Ttl {

        private long categoryListSeconds = 1800L;

        private long dishListSeconds = 1800L;

        private long setmealListSeconds = 1800L;

        private long setmealDishSeconds = 1800L;

        /**
         * 正常缓存 TTL 的随机抖动范围。
         */
        private long jitterSeconds = 300L;

        /**
         * 空值缓存 TTL。
         */
        private long nullTtlSeconds = 120L;
    }
}
