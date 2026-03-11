-- =========================================
-- sky-take-out 幂等改造增量脚本（2026-03-08）
-- =========================================
-- 目标：
-- 1) 下单链路增加数据库幂等日志；
-- 2) 支付链路增加支付流水和回调幂等日志；
-- 3) 订单号增加唯一约束，防止重复落单。
-- =========================================

-- [步骤1] 给订单号加唯一索引。
-- 作用：
-- - 即使上层逻辑异常重入，也不会生成重复订单号；
-- - 作为数据库层最后一道防线。
ALTER TABLE orders
    ADD UNIQUE KEY uk_orders_number (number);

-- [步骤2] 创建下单幂等日志表。
-- 核心唯一键：(user_id, submit_token)
-- 说明：同一用户同一 token 只允许写一条记录。
CREATE TABLE IF NOT EXISTS order_request_log (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id bigint NOT NULL COMMENT '用户ID',
    submit_token varchar(64) NOT NULL COMMENT '提交令牌',
    cart_digest varchar(64) DEFAULT NULL COMMENT '购物车摘要',
    order_id bigint DEFAULT NULL COMMENT '订单ID',
    order_number varchar(50) DEFAULT NULL COMMENT '订单号',
    status tinyint NOT NULL DEFAULT 0 COMMENT '状态 0处理中 1成功 2失败',
    fail_reason varchar(255) DEFAULT NULL COMMENT '失败原因',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_request_user_token (user_id, submit_token)
) COMMENT='下单幂等日志';

-- [步骤3] 创建支付流水表。
-- 设计要点：
-- - order_number 唯一：一个订单只保留一条主支付流水；
-- - pay_request_id 唯一：追踪每次支付发起动作。
CREATE TABLE IF NOT EXISTS payment_txn (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    pay_request_id varchar(64) NOT NULL COMMENT '支付请求ID',
    order_number varchar(50) NOT NULL COMMENT '订单号',
    user_id bigint NOT NULL COMMENT '用户ID',
    pay_amount decimal(10,2) NOT NULL COMMENT '支付金额',
    pay_method tinyint NOT NULL COMMENT '支付方式',
    transaction_id varchar(64) DEFAULT NULL COMMENT '三方交易号',
    status tinyint NOT NULL DEFAULT 0 COMMENT '状态 0待回调 1成功 2失败',
    channel varchar(16) DEFAULT NULL COMMENT '支付渠道',
    fail_reason varchar(255) DEFAULT NULL COMMENT '失败原因',
    request_time datetime DEFAULT NULL COMMENT '发起时间',
    callback_time datetime DEFAULT NULL COMMENT '回调时间',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_txn_order_number (order_number),
    UNIQUE KEY uk_payment_txn_request_id (pay_request_id)
) COMMENT='支付流水表';

-- [步骤4] 创建支付回调幂等日志表。
-- 核心唯一键：callback_id
-- callback_id 建议由 orderNumber + transactionId 哈希得到。
CREATE TABLE IF NOT EXISTS payment_callback_log (
    id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    callback_id varchar(64) NOT NULL COMMENT '回调幂等键',
    order_number varchar(50) NOT NULL COMMENT '订单号',
    transaction_id varchar(64) DEFAULT NULL COMMENT '三方交易号',
    channel varchar(16) DEFAULT NULL COMMENT '支付渠道',
    status tinyint NOT NULL DEFAULT 0 COMMENT '状态 0处理中 1成功 2失败',
    raw_payload varchar(2000) DEFAULT NULL COMMENT '原始回调数据',
    fail_reason varchar(255) DEFAULT NULL COMMENT '失败原因',
    process_time datetime DEFAULT NULL COMMENT '处理时间',
    create_time datetime DEFAULT NULL COMMENT '创建时间',
    update_time datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_callback_id (callback_id)
) COMMENT='支付回调幂等日志';
