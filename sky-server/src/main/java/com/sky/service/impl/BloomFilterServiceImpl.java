package com.sky.service.impl;

import com.sky.config.CacheProtectionProperties;
import com.sky.constant.RedisConstant;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.SetMealMapper;
import com.sky.service.BloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redisson Bloom 过滤器实现。
 *
 * 失败策略采用 fail-open：
 * Redis / Redisson 不可用时默认放行，避免把缓存层故障升级为业务不可用。
 */
@Service
@Slf4j
public class BloomFilterServiceImpl implements BloomFilterService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private CacheProtectionProperties cacheProtectionProperties;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private SetMealMapper setMealMapper;

    private final AtomicBoolean initialized = new AtomicBoolean(false);

    @Override
    public void initialize() {
        // 总开关或 Bloom 开关关闭时，直接视为“已初始化”。
        // 这样调用方不会被初始化流程阻塞，后续走普通缓存链路即可。
        if (!cacheProtectionProperties.isEnabled() || !cacheProtectionProperties.getBloom().isEnabled()) {
            initialized.set(true);
            return;
        }
        // 仅允许一个线程执行初始化，避免并发重复构建 Bloom。
        if (initialized.compareAndSet(false, true)) {
            try {
                // 按配置初始化“分类ID”布隆过滤器。
                RBloomFilter<Long> categoryBloomFilter = redissonClient.getBloomFilter(RedisConstant.CATEGORY_ID_BLOOM_KEY);
                CacheProtectionProperties.Filter categoryConfig = cacheProtectionProperties.getBloom().getCategory();
                categoryBloomFilter.tryInit(categoryConfig.getExpectedInsertions(), categoryConfig.getFalseProbability());

                // 按配置初始化“套餐ID”布隆过滤器。
                RBloomFilter<Long> setmealBloomFilter = redissonClient.getBloomFilter(RedisConstant.SETMEAL_ID_BLOOM_KEY);
                CacheProtectionProperties.Filter setmealConfig = cacheProtectionProperties.getBloom().getSetmeal();
                setmealBloomFilter.tryInit(setmealConfig.getExpectedInsertions(), setmealConfig.getFalseProbability());

                // 把数据库中的存量 ID 预热到 Bloom，启动后即可拦截明显非法 ID。
                List<Long> categoryIds = categoryMapper.listAllIds();
                if (!CollectionUtils.isEmpty(categoryIds)) {
                    for (Long categoryId : categoryIds) {
                        categoryBloomFilter.add(categoryId);
                    }
                }

                List<Long> setmealIds = setMealMapper.listAllIds();
                if (!CollectionUtils.isEmpty(setmealIds)) {
                    for (Long setmealId : setmealIds) {
                        setmealBloomFilter.add(setmealId);
                    }
                }

                log.info("布隆过滤器初始化完成，categoryCount={}, setmealCount={}",
                        categoryIds == null ? 0 : categoryIds.size(),
                        setmealIds == null ? 0 : setmealIds.size());
            } catch (Exception ex) {
                initialized.set(false);
                log.warn("布隆过滤器初始化失败，后续请求将降级放行", ex);
            }
        }
    }

    @Override
    public boolean mightContainCategory(Long categoryId) {
        return mightContain(RedisConstant.CATEGORY_ID_BLOOM_KEY, categoryId);
    }

    @Override
    public boolean mightContainSetmeal(Long setmealId) {
        return mightContain(RedisConstant.SETMEAL_ID_BLOOM_KEY, setmealId);
    }

    @Override
    public void addCategoryId(Long categoryId) {
        addToBloom(RedisConstant.CATEGORY_ID_BLOOM_KEY, categoryId);
    }

    @Override
    public void addSetmealId(Long setmealId) {
        addToBloom(RedisConstant.SETMEAL_ID_BLOOM_KEY, setmealId);
    }

    private boolean mightContain(String key, Long id) {
        // 关闭防护或入参为空时，默认放行，避免误伤正常请求。
        if (id == null || !cacheProtectionProperties.isEnabled() || !cacheProtectionProperties.getBloom().isEnabled()) {
            return true;
        }
        initialize();
        try {
            // Bloom 的语义：
            // false -> 一定不存在，可直接拦截；
            // true  -> 可能存在，继续走缓存/数据库查询。
            return redissonClient.getBloomFilter(key).contains(id);
        } catch (Exception ex) {
            // fail-open：Bloom 不可用时放行，避免缓存组件故障扩大成业务不可用。
            log.warn("布隆过滤器读取失败，按可能存在放行，key={}, id={}", key, id, ex);
            return true;
        }
    }

    private void addToBloom(String key, Long id) {
        // 防护未开启或参数不合法时，不做写入。
        if (id == null || !cacheProtectionProperties.isEnabled() || !cacheProtectionProperties.getBloom().isEnabled()) {
            return;
        }
        initialize();
        try {
            // 新增数据后把 ID 增量写入 Bloom，降低“新数据被误拦截”的概率。
            redissonClient.getBloomFilter(key).add(id);
        } catch (Exception ex) {
            log.warn("布隆过滤器写入失败，key={}, id={}", key, id, ex);
        }
    }
}
