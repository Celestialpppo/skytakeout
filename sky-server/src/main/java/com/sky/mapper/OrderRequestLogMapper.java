package com.sky.mapper;

import com.sky.entity.OrderRequestLog;
import org.apache.ibatis.annotations.*;

/**
 * 下单幂等日志 Mapper。
 *
 * 说明：
 * - 该 Mapper 全部是“按幂等键/主键更新”的基础操作；
 * - 上层服务组合这些操作实现“先查幂等 -> 再执行业务 -> 再写结果”。
 */
@Mapper
public interface OrderRequestLogMapper {

    /**
     * 按 userId + submitToken 查询幂等日志。
     *
     * 使用场景：
     * - 请求入口先判断是否已经处理过；
     * - 若已经 SUCCESS，则直接返回已有订单结果。
     */
    @Select("select * from order_request_log where user_id = #{userId} and submit_token = #{submitToken} limit 1")
    OrderRequestLog getByUserIdAndSubmitToken(@Param("userId") Long userId, @Param("submitToken") String submitToken);

    /**
     * 插入“处理中”日志。
     *
     * 说明：
     * - 该操作依赖数据库唯一索引兜底；
     * - 若并发重复写同一 token，会触发唯一键异常，由上层转成幂等返回。
     */
    @Insert("insert into order_request_log (user_id, submit_token, cart_digest, status, create_time, update_time) " +
            "values (#{userId}, #{submitToken}, #{cartDigest}, #{status}, #{createTime}, #{updateTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderRequestLog orderRequestLog);

    /**
     * 按主键更新处理结果。
     *
     * 典型更新：
     * - PROCESSING -> SUCCESS（写入 orderId/orderNumber）
     * - PROCESSING -> FAIL（写入 failReason）
     */
    @Update("update order_request_log set status = #{status}, cart_digest = #{cartDigest}, order_id = #{orderId}, " +
            "order_number = #{orderNumber}, fail_reason = #{failReason}, update_time = #{updateTime} where id = #{id}")
    int updateById(OrderRequestLog orderRequestLog);
}
