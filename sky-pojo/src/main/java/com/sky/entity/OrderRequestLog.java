package com.sky.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 提交订单幂等日志（数据库兜底层）
 *
 * 设计目的：
 * 1. Redis token 解决“高频瞬时重复提交”，但 Redis 数据可能过期/丢失；
 * 2. 这张表用于做“最终幂等落库”，保证同一 token 不会创建多单；
 * 3. 同时可用于故障排查（看失败原因、处理状态）。
 */
@Data
public class OrderRequestLog implements Serializable {

    /**
     * 处理中：刚拿到请求，订单还未最终创建成功。
     */
    public static final Integer PROCESSING = 0;

    /**
     * 成功：订单已创建，且幂等日志已绑定到订单。
     */
    public static final Integer SUCCESS = 1;

    /**
     * 失败：本次处理异常（例如地址不存在、购物车为空等）。
     */
    public static final Integer FAIL = 2;

    /**
     * 主键ID。
     */
    private Long id;

    /**
     * 用户ID。
     */
    private Long userId;

    /**
     * 提交令牌（前端确认页获取）。
     *
     * 注意：
     * - 与 userId 共同组成唯一约束，避免同一用户同一 token 重复入库。
     */
    private String submitToken;

    /**
     * 购物车摘要（内容哈希），用于辅助定位“提交时购物车快照”。
     */
    private String cartDigest;

    /**
     * 成功后绑定的订单ID。
     */
    private Long orderId;

    /**
     * 成功后绑定的订单号。
     */
    private String orderNumber;

    /**
     * 处理状态（PROCESSING / SUCCESS / FAIL）。
     */
    private Integer status;

    /**
     * 失败原因（用于排障和审计）。
     */
    private String failReason;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
