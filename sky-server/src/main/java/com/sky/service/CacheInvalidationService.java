package com.sky.service;

import java.util.Collection;

/**
 * 统一缓存失效服务。
 */
public interface CacheInvalidationService {

    /**
     * 分类列表缓存失效。
     */
    void evictCategoryList();

    /**
     * 分类关联的商品列表缓存失效。
     */
    void evictDishListByCategoryId(Long categoryId);

    /**
     * 套餐列表缓存失效。
     */
    void evictSetmealListByCategoryId(Long categoryId);

    /**
     * 套餐包含菜品缓存失效。
     */
    void evictSetmealDishList(Long setmealId);

    /**
     * 批量清理菜品列表缓存。
     */
    void evictDishListByCategoryIds(Collection<Long> categoryIds);

    /**
     * 批量清理套餐列表缓存。
     */
    void evictSetmealListByCategoryIds(Collection<Long> categoryIds);

    /**
     * 批量清理套餐菜品缓存。
     */
    void evictSetmealDishLists(Collection<Long> setmealIds);
}
