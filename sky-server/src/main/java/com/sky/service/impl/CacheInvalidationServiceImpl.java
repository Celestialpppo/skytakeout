package com.sky.service.impl;

import com.sky.cache.CacheKeyBuilder;
import com.sky.config.CacheProtectionProperties;
import com.sky.service.CacheInvalidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 缓存失效统一实现。
 */
@Service
@Slf4j
public class CacheInvalidationServiceImpl implements CacheInvalidationService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheProtectionProperties cacheProtectionProperties;

    @Override
    public void evictCategoryList() {
        deleteKey(CacheKeyBuilder.categoryListKey());
    }

    @Override
    public void evictDishListByCategoryId(Long categoryId) {
        if (categoryId != null) {
            deleteKey(CacheKeyBuilder.dishListByCategoryKey(categoryId));
        }
    }

    @Override
    public void evictSetmealListByCategoryId(Long categoryId) {
        if (categoryId != null) {
            deleteKey(CacheKeyBuilder.setmealListByCategoryKey(categoryId));
        }
    }

    @Override
    public void evictSetmealDishList(Long setmealId) {
        if (setmealId != null) {
            deleteKey(CacheKeyBuilder.setmealDishListKey(setmealId));
        }
    }

    @Override
    public void evictDishListByCategoryIds(Collection<Long> categoryIds) {
        if (!CollectionUtils.isEmpty(categoryIds)) {
            for (Long categoryId : new HashSet<>(categoryIds)) {
                evictDishListByCategoryId(categoryId);
            }
        }
    }

    @Override
    public void evictSetmealListByCategoryIds(Collection<Long> categoryIds) {
        if (!CollectionUtils.isEmpty(categoryIds)) {
            for (Long categoryId : new HashSet<>(categoryIds)) {
                evictSetmealListByCategoryId(categoryId);
            }
        }
    }

    @Override
    public void evictSetmealDishLists(Collection<Long> setmealIds) {
        if (!CollectionUtils.isEmpty(setmealIds)) {
            Set<Long> uniqueIds = new HashSet<>(setmealIds);
            for (Long setmealId : uniqueIds) {
                evictSetmealDishList(setmealId);
            }
        }
    }

    private void deleteKey(String key) {
        if (!cacheProtectionProperties.isEnabled()) {
            return;
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ex) {
            log.warn("缓存删除失败，key={}", key, ex);
        }
    }
}
