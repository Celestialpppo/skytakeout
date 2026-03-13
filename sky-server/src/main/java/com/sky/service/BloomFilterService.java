package com.sky.service;

/**
 * 布隆过滤器服务。
 */
public interface BloomFilterService {

    /**
     * 启动时初始化布隆过滤器。
     */
    void initialize();

    /**
     * categoryId 是否可能存在。
     */
    boolean mightContainCategory(Long categoryId);

    /**
     * setmealId 是否可能存在。
     */
    boolean mightContainSetmeal(Long setmealId);

    /**
     * 新增分类后补充到布隆过滤器。
     */
    void addCategoryId(Long categoryId);

    /**
     * 新增套餐后补充到布隆过滤器。
     */
    void addSetmealId(Long setmealId);
}
