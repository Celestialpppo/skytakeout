package com.sky.controller.user;

import com.sky.dto.SearchQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.GoodsSearchService;
import com.sky.vo.SearchItemVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 用户端统一搜索接口。
 */
@RestController
@Slf4j
@Validated
@RequestMapping("/user/search")
@Api(tags = "用户搜索接口")
@ConditionalOnProperty(prefix = "sky.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UserSearchController {

    @Autowired
    private GoodsSearchService goodsSearchService;

    /**
     * 统一搜索入口。
     *
     * 请求参数由 SearchQueryDTO 承载，支持关键词、分页、类型过滤、分类过滤。
     */
    @GetMapping
    @ApiOperation("统一搜索")
    public Result<PageResult<SearchItemVO>> search(@Valid SearchQueryDTO searchQueryDTO) {
        log.info("用户统一搜索，参数：{}", searchQueryDTO);
        PageResult<SearchItemVO> pageResult = goodsSearchService.search(searchQueryDTO);
        return Result.success(pageResult);
    }
}
