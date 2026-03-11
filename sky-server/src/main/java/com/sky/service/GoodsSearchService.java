package com.sky.service;

import com.sky.dto.SearchQueryDTO;
import com.sky.result.PageResult;
import com.sky.vo.SearchItemVO;

/**
 * 搜索读链路服务。
 */
public interface GoodsSearchService {

    /**
     * 统一搜索。
     */
    PageResult<SearchItemVO> search(SearchQueryDTO queryDTO);
}
