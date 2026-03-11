package com.sky.entity;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付流水表对应实体。
 *
 * 设计目的：
 * 1. 支付发起阶段做幂等：同一订单只保留一条有效“待回调/成功/失败”流水；
 * 2. 支付回调阶段可回填三方交易号、回调时间、最终状态；
 * 3. 作为“payment 与 callback 的共享事实来源”，避免仅靠内存或 Redis。
 */
@Data
public class PaymentTxn implements Serializable {

    /**
     * 待回调：已发起支付，但未确认支付成功。
     */
    public static final Integer WAITING = 0;

    /**
     * 成功：支付回调已确认成功。
     */
    public static final Integer SUCCESS = 1;

    /**
     * 失败：支付失败（保留该状态方便重试时复用记录）。
     */
    public static final Integer FAIL = 2;

    /**
     * 主键ID。
     */
    private Long id;

    /**
     * 支付请求ID（系统内生成，便于追踪一次支付发起动作）。
     */
    private String payRequestId;

    /**
     * 订单号（业务主关联键）。
     */
    private String orderNumber;

    /**
     * 用户ID。
     */
    private Long userId;

    /**
     * 支付金额（冗余快照，避免后续金额变更影响支付审计）。
     */
    private BigDecimal payAmount;

    /**
     * 支付方式（1微信 / 2支付宝）。
     */
    private Integer payMethod;

    /**
     * 三方交易号（回调成功后回填）。
     */
    private String transactionId;

    /**
     * 状态（WAITING / SUCCESS / FAIL）。
     */
    private Integer status;

    /**
     * 渠道（wechat / alipay）。
     */
    private String channel;

    /**
     * 失败原因（失败时记录，用于排查）。
     */
    private String failReason;

    /**
     * 发起时间（用户点击支付时）。
     */
    private LocalDateTime requestTime;

    /**
     * 回调时间（支付平台确认回调时）。
     */
    private LocalDateTime callbackTime;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}
