package com.sky.constant;

/**
 * Redis key 常量。
 */
public final class RedisConstant {

    private RedisConstant() {
    }

    /** 店铺营业状态。 */
    public static final String SHOP_STATUS_KEY = "sky:shop:status";

    /** 旧版：菜品按分类缓存（Hash key）。 */
    public static final String SHOP_CATEGORY_DISHES = "sky:shop:category:dishes";

    /** 旧版：套餐按分类缓存（Hash key）。 */
    public static final String SHOP_CATEGORY_SETMEALS = "sky:shop:category:setmeals";

    /** 分类列表缓存（String key）。 */
    public static final String SHOP_CATEGORY_LIST_KEY = "sky:shop:category:list";

    /** 菜品列表缓存前缀：按分类。 */
    public static final String SHOP_DISH_LIST_BY_CATEGORY_PREFIX = "sky:shop:dish:list:category:";

    /** 套餐列表缓存前缀：按分类。 */
    public static final String SHOP_SETMEAL_LIST_BY_CATEGORY_PREFIX = "sky:shop:setmeal:list:category:";

    /** 套餐菜品明细缓存前缀。 */
    public static final String SHOP_SETMEAL_DISH_LIST_PREFIX = "sky:shop:setmeal:dish:list:";

    /** 分类 ID BloomFilter key。 */
    public static final String CATEGORY_ID_BLOOM_KEY = "sky:bloom:category:id";

    /** 套餐 ID BloomFilter key。 */
    public static final String SETMEAL_ID_BLOOM_KEY = "sky:bloom:setmeal:id";
}
