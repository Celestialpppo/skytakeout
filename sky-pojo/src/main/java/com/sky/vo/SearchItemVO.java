package com.sky.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 统一搜索返回项。
 *
 * 约定：
 * - bizType: 业务类型（dish / setmeal）；
 * - sourceId: 原始业务表主键；
 * - id: ES 文档主键（bizType:sourceId），用于排查同步问题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiModel("统一搜索结果项")
public class SearchItemVO implements Serializable {

    @ApiModelProperty("ES文档ID，格式：bizType:sourceId")
    private String id;

    @ApiModelProperty("业务类型：dish 或 setmeal")
    private String bizType;

    @ApiModelProperty("业务主键ID")
    private Long sourceId;

    @ApiModelProperty("名称")
    private String name;

    @ApiModelProperty("描述")
    private String description;

    @ApiModelProperty("分类ID")
    private Long categoryId;

    @ApiModelProperty("分类名称")
    private String categoryName;

    @ApiModelProperty("价格")
    private BigDecimal price;

    @ApiModelProperty("图片")
    private String image;
}
