package com.sky.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 统一商品搜索文档。
 *
 * 设计说明：
 * 1. 统一索引承接 dish/setmeal 两类数据，靠 bizType 区分来源；
 * 2. id 使用 bizType:sourceId，避免两张表主键碰撞；
 * 3. status/categoryStatus 同步入索引，查询时可直接过滤掉停售/禁用分类数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "${sky.search.index.goods-index}", createIndex = false)
public class GoodsSearchDocument {

    /**
     * ES 文档主键：bizType:sourceId。
     */
    @Id
    private String id;

    /**
     * 业务类型（dish / setmeal）。
     */
    @Field(type = FieldType.Keyword)
    private String bizType;

    /**
     * 业务表主键。
     */
    @Field(type = FieldType.Long)
    private Long sourceId;

    /**
     * 名称，支持关键词匹配。
     */
    @Field(type = FieldType.Text)
    private String name;

    /**
     * 描述，支持关键词匹配。
     */
    @Field(type = FieldType.Text)
    private String description;

    /**
     * 分类ID，用于过滤。
     */
    @Field(type = FieldType.Long)
    private Long categoryId;

    /**
     * 分类名称，供展示和关键词补充匹配。
     */
    @Field(type = FieldType.Text)
    private String categoryName;

    /**
     * 价格，展示用。
     */
    @Field(type = FieldType.Double)
    private BigDecimal price;

    /**
     * 图片 URL。
     */
    @Field(type = FieldType.Keyword, index = false)
    private String image;

    /**
     * 业务数据状态：1-启用，0-禁用。
     */
    @Field(type = FieldType.Integer)
    private Integer status;

    /**
     * 分类状态：1-启用，0-禁用。
     */
    @Field(type = FieldType.Integer)
    private Integer categoryStatus;

    /**
     * 原始数据更新时间，供排序使用。
     */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime updateTime;
}
