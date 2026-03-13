package com.sky.service.impl;

import com.alibaba.fastjson.JSON;
import com.sky.config.CacheProtectionProperties;
import com.sky.service.CacheSupportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 列表缓存统一封装。
 */
@Service
@Slf4j
public class CacheSupportServiceImpl implements CacheSupportService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheProtectionProperties cacheProtectionProperties;

    @Override
    public <T> List<T> getOrLoadList(String key, Class<T> itemType, long ttlSeconds, Supplier<List<T>> loader) {
        if (!cacheProtectionProperties.isEnabled()) {
            List<T> loaded = loader.get();
            return loaded == null ? Collections.emptyList() : loaded;
        }
        try {
            String cached = stringRedisTemplate.opsForValue().get(key);
            if (cached != null) {
                // 命中缓存后直接返回。
                // 注意：这里 cached 可能是“空列表占位值”，用于拦截缓存穿透。
                log.debug("缓存命中，key={}", key);
                return JSON.parseArray(cached, itemType);
            }
        } catch (Exception ex) {
            log.warn("缓存读取失败，降级回库，key={}", key, ex);
        }

        // 缓存未命中时走回源查询，再回填缓存。
        // 当前实现是普通 Cache-Aside，未包含互斥重建逻辑。
        log.debug("缓存未命中，回源数据库，key={}", key);
        List<T> loaded = loader.get();
        List<T> safeResult = loaded == null ? Collections.emptyList() : loaded;
        writeListCache(key, safeResult, ttlSeconds);
        return safeResult;
    }

    private <T> void writeListCache(String key, List<T> list, long ttlSeconds) {
        try {
            // 空结果使用更短 TTL 作为“空值缓存”，防止不存在数据被反复回源查询（缓存穿透）。
            // 非空结果在基础 TTL 上加随机抖动，降低同批 key 同时过期带来的压力峰值。
            long expireSeconds = CollectionUtils.isEmpty(list)
                    ? cacheProtectionProperties.getTtl().getNullTtlSeconds()
                    : ttlSeconds + buildJitterSeconds();
            stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(list), expireSeconds, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("缓存写入失败，key={}", key, ex);
        }
    }

    private long buildJitterSeconds() {
        long jitter = cacheProtectionProperties.getTtl().getJitterSeconds();
        if (jitter <= 0) {
            return 0L;
        }
        // 生成 [0, jitter] 的随机秒数，分散缓存失效时刻。
        return ThreadLocalRandom.current().nextLong(jitter + 1);
    }
}
