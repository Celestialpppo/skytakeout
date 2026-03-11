package com.sky.search.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * MySQL 联表后的搜索源记录。
 *
 * 来源：SearchSyncMapper.xml
 * - dish/setmeal 表作为主表；
 * - left join category 拿到分类名称与状态；
 * - 统一映射后交给转换器转成 ES 文档。
 */
@Data
public class SearchSourceRecord {

    private String bizType;

    private Long sourceId;

    private String name;

    private String description;

    private Long categoryId;

    private String categoryName;

    private BigDecimal price;

    private String image;

    private Integer status;

    private Integer categoryStatus;

    private LocalDateTime updateTime;
}
