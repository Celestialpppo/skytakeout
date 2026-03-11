package com.sky.service;

/**
 * 搜索索引同步服务。
 */
public interface SearchSyncService {

    /**
     * 全量重建索引。
     */
    void rebuildAll();

    /**
     * 处理单条增量变更。
     *
     * @param tableName 来源表
     * @param operation 操作类型（INSERT/UPDATE/DELETE）
     * @param id 主键
     */
    void syncIncrementalChange(String tableName, String operation, Long id);
}
