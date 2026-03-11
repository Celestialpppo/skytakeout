package com.sky.mapper;

import com.sky.entity.PaymentCallbackLog;
import org.apache.ibatis.annotations.*;

/**
 * 支付回调幂等日志 Mapper。
 *
 * 说明：
 * - callback_id 是幂等主键；
 * - 回调处理流程通常是：查 -> 插入/更新 processing -> 处理业务 -> 更新 success/fail。
 */
@Mapper
public interface PaymentCallbackLogMapper {

    /**
     * 根据回调幂等键查询日志。
     */
    @Select("select * from payment_callback_log where callback_id = #{callbackId} limit 1")
    PaymentCallbackLog getByCallbackId(String callbackId);

    /**
     * 新增回调日志。
     *
     * 通常在第一次收到该 callback_id 时写入，状态置为 PROCESSING。
     */
    @Insert("insert into payment_callback_log (callback_id, order_number, transaction_id, channel, status, raw_payload, create_time, update_time) " +
            "values (#{callbackId}, #{orderNumber}, #{transactionId}, #{channel}, #{status}, #{rawPayload}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PaymentCallbackLog callbackLog);

    /**
     * 根据 callback_id 更新日志状态。
     *
     * 典型更新：
     * - PROCESSING -> SUCCESS
     * - PROCESSING -> FAIL（并记录 failReason）
     */
    @Update("update payment_callback_log set status = #{status}, fail_reason = #{failReason}, process_time = #{processTime}, " +
            "update_time = #{updateTime} where callback_id = #{callbackId}")
    int updateByCallbackId(PaymentCallbackLog callbackLog);
}
