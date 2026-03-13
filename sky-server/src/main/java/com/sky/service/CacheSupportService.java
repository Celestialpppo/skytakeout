package com.sky.service;

import java.util.List;
import java.util.function.Supplier;

/**
 * 缓存读写封装。
 */
public interface CacheSupportService {

    /**
     * 读取列表缓存，未命中时加载并回填。
     */
    <T> List<T> getOrLoadList(String key, Class<T> itemType, long ttlSeconds, Supplier<List<T>> loader);
}
