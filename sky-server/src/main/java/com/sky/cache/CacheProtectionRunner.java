package com.sky.cache;

import com.sky.service.BloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动时预热缓存防护基础设施。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "sky.cache.protection", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CacheProtectionRunner implements ApplicationRunner {

    @Autowired
    private BloomFilterService bloomFilterService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始初始化缓存防护组件");
        bloomFilterService.initialize();
        log.info("缓存防护组件初始化完成");
    }
}
