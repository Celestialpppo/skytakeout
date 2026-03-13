package com.sky.cache;

import com.sky.constant.RedisConstant;

/**
 * 缓存 key 生成器。
 */
public final class CacheKeyBuilder {

    private CacheKeyBuilder() {
    }

    public static String categoryListKey() {
        return RedisConstant.SHOP_CATEGORY_LIST_KEY;
    }

    public static String dishListByCategoryKey(Long categoryId) {
        return RedisConstant.SHOP_DISH_LIST_BY_CATEGORY_PREFIX + categoryId;
    }

    public static String setmealListByCategoryKey(Long categoryId) {
        return RedisConstant.SHOP_SETMEAL_LIST_BY_CATEGORY_PREFIX + categoryId;
    }

    public static String setmealDishListKey(Long setmealId) {
        return RedisConstant.SHOP_SETMEAL_DISH_LIST_PREFIX + setmealId;
    }
}
