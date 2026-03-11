package com.sky.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付回调幂等日志实体。
 *
 * 设计目的：
 * 1. 防止第三方重复回调导致重复改状态；
 * 2. 保留原始回调报文用于问题回放；
 * 3. 记录“处理中/成功/失败”便于排障。
 */
@Data
public class PaymentCallbackLog implements Serializable {

    /**
     * 处理中：回调正在被消费处理。
     */
    public static final Integer PROCESSING = 0;

    /**
     * 成功：该回调已经成功处理完毕（后续重复回调可直接幂等返回）。
     */
    public static final Integer SUCCESS = 1;

    /**
     * 失败：本次处理失败，通常需要第三方重试回调。
     */
    public static final Integer FAIL = 2;

    /**
     * 主键ID。
     */
    private Long id;

    /**
     * 回调幂等键（通常由 orderNumber + transactionId 哈希而来）。
     */
    private String callbackId;

    /**
     * 订单号。
     */
    private String orderNumber;

    /**
     * 第三方交易号。
     */
    private String transactionId;

    /**
     * 支付渠道（wechat / alipay）。
     */
    private String channel;

    /**
     * 处理状态（PROCESSING / SUCCESS / FAIL）。
     */
    private Integer status;

    /**
     * 回调原始报文（截断保存，避免超长）。
     */
    private String rawPayload;

    /**
     * 失败原因（失败时记录）。
     */
    private String failReason;

    /**
     * 处理完成时间。
     */
    private LocalDateTime processTime;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
