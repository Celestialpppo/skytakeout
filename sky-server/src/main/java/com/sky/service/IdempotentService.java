package com.sky.service;

/**
 * 幂等性服务接口
 */
public interface IdempotentService {
    
    /**
     * 生成提交订单的令牌
     * @param userId 用户ID
     * @return 令牌字符串
     */
    String generateSubmitToken(Long userId);
    
    /**
     * 验证并删除令牌
     * @param userId 用户ID
     * @param token 令牌
     * @return 是否验证成功
     */
    boolean validateAndRemoveSubmitToken(Long userId, String token);
    
    /**
     * 生成购物车摘要
     * @param userId 用户ID
     * @return 购物车摘要
     */
    String generateCartDigest(Long userId);
}
