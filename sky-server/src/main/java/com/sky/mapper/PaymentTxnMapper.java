package com.sky.mapper;

import com.sky.entity.PaymentTxn;
import org.apache.ibatis.annotations.*;

/**
 * 支付流水 Mapper。
 *
 * 说明：
 * - 通过 order_number 做支付发起幂等；
 * - 通过 updateById 在回调时回填交易信息与状态。
 */
@Mapper
public interface PaymentTxnMapper {

    /**
     * 按订单号查询支付流水。
     *
     * 用途：
     * - payment 接口判断是否已存在待回调/成功记录；
     * - callback 接口拿到流水并更新最终状态。
     */
    @Select("select * from payment_txn where order_number = #{orderNumber} limit 1")
    PaymentTxn getByOrderNumber(String orderNumber);

    /**
     * 新建支付流水。
     */
    @Insert("insert into payment_txn (pay_request_id, order_number, user_id, pay_amount, pay_method, transaction_id, " +
            "status, channel, fail_reason, request_time, callback_time, create_time, update_time) " +
            "values (#{payRequestId}, #{orderNumber}, #{userId}, #{payAmount}, #{payMethod}, #{transactionId}, " +
            "#{status}, #{channel}, #{failReason}, #{requestTime}, #{callbackTime}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PaymentTxn paymentTxn);

    /**
     * 按主键更新支付流水。
     *
     * 用途：
     * - 回调成功时更新 transactionId/status/callbackTime；
     * - 失败重试时更新 payRequestId/status/requestTime。
     */
    @Update("update payment_txn set pay_request_id = #{payRequestId}, pay_method = #{payMethod}, transaction_id = #{transactionId}, " +
            "status = #{status}, channel = #{channel}, fail_reason = #{failReason}, request_time = #{requestTime}, " +
            "callback_time = #{callbackTime}, update_time = #{updateTime} where id = #{id}")
    int updateById(PaymentTxn paymentTxn);
}
