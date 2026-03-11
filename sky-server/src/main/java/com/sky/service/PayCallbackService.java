package com.sky.service;

import java.util.Map;

/**
 * 支付回调服务接口
 */
public interface PayCallbackService {
    
    /**
     * 处理微信支付回调
     * @param callbackData 回调数据
     * @return 回调响应
     */
    String handleWechatCallback(Map<String, Object> callbackData);
    
    /**
     * 处理支付宝支付回调
     * @param callbackData 回调数据
     * @return 回调响应
     */
    String handleAlipayCallback(Map<String, Object> callbackData);
}
