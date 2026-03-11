package com.sky.mapper;

import com.sky.search.model.SearchSourceRecord;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 搜索同步数据访问层。
 *
 * 说明：
 * - 这里只负责取“源数据”，不做业务判定；
 * - 是否应写入索引由 SearchDocumentConverter.shouldIndex 统一判断。
 */
@Mapper
public interface SearchSyncMapper {

    /**
     * 查询全部菜品搜索源数据。
     */
    List<SearchSourceRecord> listAllDishRecords();

    /**
     * 查询全部套餐搜索源数据。
     */
    List<SearchSourceRecord> listAllSetmealRecords();

    /**
     * 按主键查询单条菜品搜索源数据。
     */
    SearchSourceRecord getDishRecordById(Long id);

    /**
     * 按主键查询单条套餐搜索源数据。
     */
    SearchSourceRecord getSetmealRecordById(Long id);

    /**
     * 查询某分类下关联的菜品ID。
     */
    List<Long> listDishIdsByCategoryId(Long categoryId);

    /**
     * 查询某分类下关联的套餐ID。
     */
    List<Long> listSetmealIdsByCategoryId(Long categoryId);
}
