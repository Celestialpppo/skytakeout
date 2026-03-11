package com.sky.search.converter;

import com.sky.constant.StatusConstant;
import com.sky.search.model.GoodsSearchDocument;
import com.sky.search.model.SearchSourceRecord;
import com.sky.vo.SearchItemVO;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 搜索模型转换器。
 *
 * 主要职责：
 * 1. 统一生成文档主键规则（bizType:sourceId）；
 * 2. 统一“是否允许入索引”的判定逻辑；
 * 3. 统一 SourceRecord / ES Document / VO 之间的映射。
 */
public final class SearchDocumentConverter {

    private SearchDocumentConverter() {
    }

    /**
     * 生成 ES 文档主键。
     */
    public static String buildDocumentId(String bizType, Long sourceId) {
        String normalizedBizType = normalizeBizType(bizType);
        if (!StringUtils.hasText(normalizedBizType) || sourceId == null) {
            return null;
        }
        return normalizedBizType + ":" + sourceId;
    }

    /**
     * 判断记录是否应保留在索引中。
     *
     * 业务规则：
     * - dish/setmeal 本身必须是启用状态；
     * - 所属分类也必须是启用状态。
     */
    public static boolean shouldIndex(SearchSourceRecord sourceRecord) {
        if (sourceRecord == null) {
            return false;
        }
        return sourceRecord.getSourceId() != null
                && StringUtils.hasText(sourceRecord.getName())
                && StatusConstant.ENABLE.equals(sourceRecord.getStatus())
                && StatusConstant.ENABLE.equals(sourceRecord.getCategoryStatus());
    }

    /**
     * MySQL 源记录 -> ES 文档。
     */
    public static GoodsSearchDocument toDocument(SearchSourceRecord sourceRecord) {
        if (sourceRecord == null) {
            return null;
        }
        return GoodsSearchDocument.builder()
                .id(buildDocumentId(sourceRecord.getBizType(), sourceRecord.getSourceId()))
                .bizType(normalizeBizType(sourceRecord.getBizType()))
                .sourceId(sourceRecord.getSourceId())
                .name(sourceRecord.getName())
                .description(sourceRecord.getDescription())
                .categoryId(sourceRecord.getCategoryId())
                .categoryName(sourceRecord.getCategoryName())
                .price(sourceRecord.getPrice())
                .image(sourceRecord.getImage())
                .status(sourceRecord.getStatus())
                .categoryStatus(sourceRecord.getCategoryStatus())
                .updateTime(sourceRecord.getUpdateTime())
                .build();
    }

    /**
     * ES 文档 -> API 返回 VO。
     */
    public static SearchItemVO toSearchItemVO(GoodsSearchDocument document) {
        if (document == null) {
            return null;
        }
        return SearchItemVO.builder()
                .id(document.getId())
                .bizType(document.getBizType())
                .sourceId(document.getSourceId())
                .name(document.getName())
                .description(document.getDescription())
                .categoryId(document.getCategoryId())
                .categoryName(document.getCategoryName())
                .price(document.getPrice())
                .image(document.getImage())
                .build();
    }

    /**
     * 规范化业务类型，避免大小写导致的文档主键不一致。
     */
    public static String normalizeBizType(String bizType) {
        if (!StringUtils.hasText(bizType)) {
            return null;
        }
        return bizType.trim().toLowerCase(Locale.ROOT);
    }
}
