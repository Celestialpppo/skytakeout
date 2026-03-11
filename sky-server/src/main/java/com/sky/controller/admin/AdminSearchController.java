package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.SearchSyncService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端搜索维护接口。
 */
@RestController
@Slf4j
@RequestMapping("/admin/search")
@Api(tags = "搜索维护接口")
@ConditionalOnProperty(prefix = "sky.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdminSearchController {

    @Autowired
    private SearchSyncService searchSyncService;

    /**
     * 手动触发全量重建。
     */
    @PostMapping("/rebuild")
    @ApiOperation("手动重建搜索索引")
    public Result<?> rebuild() {
        log.info("管理端触发搜索索引全量重建");
        searchSyncService.rebuildAll();
        return Result.success();
    }
}
