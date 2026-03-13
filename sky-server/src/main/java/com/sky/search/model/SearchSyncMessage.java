package com.sky.search.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 搜索增量同步消息。承载表变更最小信息。
 *
 * 消息体只保留最小必要信息：
 * - tableName: 来源表（dish/setmeal/category）；
 * - operation: 变更类型（INSERT/UPDATE/DELETE）；
 * - id: 变更行主键；
 * - eventTime: 事件时间（用于排查时序问题）。
 *
 * 消费端收到后不会直接使用消息内容更新 ES，而是回查 MySQL 最新状态后再写 ES，
 * 避免消息乱序/重复导致的数据脏写。
 * 为什么这么设计：
 *
 * 消息越小越稳定
 * 传输成本低
 * 防止把业务快照塞进消息导致过时数据写入
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchSyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tableName;

    private String operation;

    private Long id;

    private LocalDateTime eventTime;
}
