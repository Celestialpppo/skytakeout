package com.sky.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import org.hibernate.validator.constraints.Range;

import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 统一搜索查询参数。
 *
 * 说明：
 * 1. 搜索入口同时覆盖 dish / setmeal，因此把公共筛选条件聚合在一个 DTO 中；
 * 2. page/pageSize 在 service 层会再次做兜底和上限控制，避免非法参数影响 ES 查询；
 * 3. type 使用固定枚举值（dish/setmeal）保证索引字段过滤的一致性。
 */
@Data
@ApiModel("统一搜索查询参数")
public class SearchQueryDTO implements Serializable {

    @ApiModelProperty("关键词，匹配 name/description/categoryName")
    @Size(max = 50, message = "关键词长度不能超过50")
    private String keyword;

    @ApiModelProperty(value = "页码，从1开始")
    @Range(min = 1, message = "页码最小为1")
    private Integer page = 1;

    @ApiModelProperty(value = "每页数量")
    @Range(min = 1, message = "每页数量最小为1")
    private Integer pageSize = 10;

    @ApiModelProperty(value = "类型过滤：dish/setmeal")
    @Pattern(regexp = "^(dish|setmeal)$", message = "type 只能是 dish 或 setmeal")
    private String type;

    @ApiModelProperty("分类ID过滤")
    private Long categoryId;
}
