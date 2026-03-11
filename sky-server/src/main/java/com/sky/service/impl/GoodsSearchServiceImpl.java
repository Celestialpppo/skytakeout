package com.sky.service.impl;

import com.sky.config.SearchProperties;
import com.sky.constant.StatusConstant;
import com.sky.dto.SearchQueryDTO;
import com.sky.result.PageResult;
import com.sky.search.converter.SearchDocumentConverter;
import com.sky.search.model.GoodsSearchDocument;
import com.sky.service.GoodsSearchService;
import com.sky.vo.SearchItemVO;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 统一搜索服务实现。
 *
 * 读链路策略：
 * 1. 一次查询统一索引 goods_search；
 * 2. 支持关键词、类型、分类过滤；
 * 3. 固定过滤 status/categoryStatus=1，避免返回停售或分类禁用数据；
 * 4. 页大小在服务层二次兜底，防止极端参数拉垮 ES。
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "sky.search", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GoodsSearchServiceImpl implements GoodsSearchService {

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Autowired
    private SearchProperties searchProperties;

    @Override
    public PageResult<SearchItemVO> search(SearchQueryDTO queryDTO) {
        SearchQueryDTO safeQuery = queryDTO == null ? new SearchQueryDTO() : queryDTO;

        int pageNum = normalizePageNum(safeQuery.getPage());
        int pageSize = normalizePageSize(safeQuery.getPageSize());

        // 统一构建 bool 查询：
        // - must：关键词匹配（未传关键词则 match_all）；
        // - filter：类型、分类、状态过滤（不参与评分，性能更稳定）。
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        if (StringUtils.hasText(safeQuery.getKeyword())) {
            boolQueryBuilder.must(QueryBuilders.multiMatchQuery(
                    safeQuery.getKeyword(),
                    "name",
                    "description",
                    "categoryName"
            ));
        } else {
            boolQueryBuilder.must(QueryBuilders.matchAllQuery());
        }

        String normalizedType = SearchDocumentConverter.normalizeBizType(safeQuery.getType());
        if (StringUtils.hasText(normalizedType)) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("bizType", normalizedType));
        }
        if (safeQuery.getCategoryId() != null) {
            boolQueryBuilder.filter(QueryBuilders.termQuery("categoryId", safeQuery.getCategoryId()));
        }

        // 只查询可售数据，避免索引未及时删文档时把脏数据返回给用户端。
        boolQueryBuilder.filter(QueryBuilders.termQuery("status", StatusConstant.ENABLE));
        boolQueryBuilder.filter(QueryBuilders.termQuery("categoryStatus", StatusConstant.ENABLE));

        NativeSearchQueryBuilder queryBuilder = new NativeSearchQueryBuilder()
                .withQuery(boolQueryBuilder)
                .withPageable(PageRequest.of(pageNum - 1, pageSize));

        // 关键词检索时先按 _score，再按更新时间兜底；
        // 无关键词时直接按更新时间倒序。
        if (StringUtils.hasText(safeQuery.getKeyword())) {
            queryBuilder.withSort(SortBuilders.scoreSort().order(SortOrder.DESC));
        }
        queryBuilder.withSort(SortBuilders.fieldSort("updateTime").order(SortOrder.DESC));

        NativeSearchQuery searchQuery = queryBuilder.build();
        IndexCoordinates indexCoordinates = IndexCoordinates.of(searchProperties.getIndex().getGoodsIndex());
        SearchHits<GoodsSearchDocument> searchHits =
                elasticsearchOperations.search(searchQuery, GoodsSearchDocument.class, indexCoordinates);

        List<SearchItemVO> records = searchHits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .map(SearchDocumentConverter::toSearchItemVO)
                .collect(Collectors.toList());

        log.debug("用户搜索完成，keyword={}, type={}, categoryId={}, total={}",
                safeQuery.getKeyword(), safeQuery.getType(), safeQuery.getCategoryId(), searchHits.getTotalHits());

        return new PageResult<>(searchHits.getTotalHits(), records, pageSize, pageNum);
    }

    /**
     * 页码兜底。
     */
    private int normalizePageNum(Integer pageNum) {
        if (pageNum == null || pageNum < 1) {
            return 1;
        }
        return pageNum;
    }

    /**
     * 页大小兜底并做上限控制。
     */
    private int normalizePageSize(Integer pageSize) {
        int defaultSize = 10;
        int maxPageSize = searchProperties.getIndex().getMaxPageSize();
        int safeMaxPageSize = maxPageSize < 1 ? 50 : maxPageSize;

        if (pageSize == null || pageSize < 1) {
            return defaultSize;
        }
        return Math.min(pageSize, safeMaxPageSize);
    }
}
