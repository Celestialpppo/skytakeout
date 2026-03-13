package com.sky.service.impl;

import com.sky.config.SearchProperties;
import com.sky.mapper.SearchSyncMapper;
import com.sky.search.converter.SearchDocumentConverter;
import com.sky.search.model.GoodsSearchDocument;
import com.sky.search.model.SearchSourceRecord;
import com.sky.service.SearchSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.document.Document;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 搜索同步服务实现。
 *
 * 核心原则：
 * 1. 消费端永远回查 MySQL 最新状态，再决定 ES upsert/delete；
 * 2. 文档主键统一为 bizType:sourceId；
 * 3. 全量重建时先删后建索引，确保映射和数据一体重置。
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "sky.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class    SearchSyncServiceImpl implements SearchSyncService {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private SearchProperties searchProperties;

    @Autowired
    private SearchSyncMapper searchSyncMapper;

    @Override
    public void rebuildAll() {
        IndexCoordinates indexCoordinates = getIndexCoordinates(); //先根据配置拿到索引名
        IndexOperations indexOperations = elasticsearchOperations.indexOps(indexCoordinates); //再拿到“针对这个索引的操作器”

        // 1) 重建索引结构：删除旧索引 -> 创建新索引 -> 写入 mapping。
        if (indexOperations.exists()) {
            boolean deleted = indexOperations.delete();
            log.info("删除旧搜索索引，index={}, deleted={}", indexCoordinates.getIndexName(), deleted);
        }

        boolean created = indexOperations.create();
        if (!created) {
            throw new IllegalStateException("创建搜索索引失败: " + indexCoordinates.getIndexName());
        }

        Document mapping = indexOperations.createMapping(GoodsSearchDocument.class);
        indexOperations.putMapping(mapping);

        // 2) 拉取全量源数据（dish + setmeal）。
        List<SearchSourceRecord> sourceRecords = new ArrayList<>();
        List<SearchSourceRecord> dishRecords = searchSyncMapper.listAllDishRecords();
        if (!CollectionUtils.isEmpty(dishRecords)) {
            sourceRecords.addAll(dishRecords);
        }
        List<SearchSourceRecord> setmealRecords = searchSyncMapper.listAllSetmealRecords();
        if (!CollectionUtils.isEmpty(setmealRecords)) {
            sourceRecords.addAll(setmealRecords);
        }

        // 3) 过滤掉不该入索引的数据（停售/分类禁用/关键字段缺失）。
        List<GoodsSearchDocument> documents = sourceRecords.stream()
                .filter(SearchDocumentConverter::shouldIndex)
                .map(SearchDocumentConverter::toDocument)
                .filter(document -> document != null && StringUtils.hasText(document.getId()))
                .collect(Collectors.toList());

        // 4) 批量写入新索引。
        if (!CollectionUtils.isEmpty(documents)) {
            for (GoodsSearchDocument document : documents) {
                elasticsearchOperations.save(document, indexCoordinates);
            }
        }

        // 5) 刷新索引，确保重建接口返回后立刻可查。
        indexOperations.refresh();
        log.info("搜索索引全量重建完成，index={}, sourceCount={}, indexedCount={}",
                indexCoordinates.getIndexName(), sourceRecords.size(), documents.size());
    }

    /**
     *
     * @param tableName 来源表
     * @param operation 操作类型（INSERT/UPDATE/DELETE）
     * @param id 主键
     */
    @Override
    public void syncIncrementalChange(String tableName, String operation, Long id) {
        if (id == null) {
            log.warn("收到无效增量消息，id为空，tableName={}, operation={}", tableName, operation);
            return;
        }

        String normalizedTableName = normalize(tableName);
        String normalizedOperation = normalize(operation);

        switch (normalizedTableName) {
            case "dish":
                syncDishById(id, "delete".equals(normalizedOperation));
                break;
            case "setmeal":
                syncSetmealById(id, "delete".equals(normalizedOperation));
                break;
            case "category":
                syncCategoryImpactById(id);
                break;
            default:
                log.debug("忽略不在搜索同步范围内的表变更，tableName={}, id={}", tableName, id);
        }
    }

    /**
     * 同步菜品文档。
     */
    private void syncDishById(Long id, boolean forceDelete) {
        if (forceDelete) {
            deleteDocument("dish", id);
            return;
        }
        SearchSourceRecord sourceRecord = searchSyncMapper.getDishRecordById(id);
        upsertOrDeleteByLatestSource(sourceRecord, "dish", id);
    }

    /**
     * 同步套餐文档。
     */
    private void syncSetmealById(Long id, boolean forceDelete) {
        if (forceDelete) {
            deleteDocument("setmeal", id);
            return;
        }
        SearchSourceRecord sourceRecord = searchSyncMapper.getSetmealRecordById(id);
        upsertOrDeleteByLatestSource(sourceRecord, "setmeal", id);
    }

    /**
     * 同步分类影响范围。
     *
     * 分类变更（名称/状态）会影响其下 dish 与 setmeal 的搜索展示，
     * 因此需要查出受影响主键并逐条刷新。
     */
    private void syncCategoryImpactById(Long categoryId) {
        List<Long> dishIds = searchSyncMapper.listDishIdsByCategoryId(categoryId);
        if (!CollectionUtils.isEmpty(dishIds)) {
            for (Long dishId : dishIds) {
                syncDishById(dishId, false);
            }
        }

        List<Long> setmealIds = searchSyncMapper.listSetmealIdsByCategoryId(categoryId);
        if (!CollectionUtils.isEmpty(setmealIds)) {
            for (Long setmealId : setmealIds) {
                syncSetmealById(setmealId, false);
            }
        }
    }

    /**
     * 基于“回查后的最新源数据”执行 upsert 或 delete。
     */
    private void upsertOrDeleteByLatestSource(SearchSourceRecord sourceRecord, String bizType, Long sourceId) {
        if (!SearchDocumentConverter.shouldIndex(sourceRecord)) {
            deleteDocument(bizType, sourceId);
            return;
        }

        GoodsSearchDocument document = SearchDocumentConverter.toDocument(sourceRecord);
        if (document == null || !StringUtils.hasText(document.getId())) {
            deleteDocument(bizType, sourceId);
            return;
        }

        elasticsearchOperations.save(document, getIndexCoordinates());
    }

    /**
     * 删除文档（删除不存在文档时 ES 会安全忽略）。
     */
    private void deleteDocument(String bizType, Long sourceId) {
        String documentId = SearchDocumentConverter.buildDocumentId(bizType, sourceId);
        if (!StringUtils.hasText(documentId)) {
            return;
        }
        elasticsearchOperations.delete(documentId, getIndexCoordinates());
    }

    private IndexCoordinates getIndexCoordinates() {
        return IndexCoordinates.of(searchProperties.getIndex().getGoodsIndex());
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }
}
