package com.sky.search.task;

import com.sky.service.SearchSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 搜索索引启动初始化任务。
 *
 * 仅在 sky.search.index.auto-init-on-startup=true 时执行，
 * 主要用于开发/测试环境快速构建初始索引。
 */
@Component
@Slf4j
@ConditionalOnProperty(
        prefix = "sky.search",
        name = {"enabled", "index.auto-init-on-startup"},
        havingValue = "true"
)
public class SearchIndexInitRunner implements ApplicationRunner {

    @Autowired
    private SearchSyncService searchSyncService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动自动重建搜索索引任务开始执行");
        searchSyncService.rebuildAll();
        log.info("启动自动重建搜索索引任务执行完成");
    }
}
