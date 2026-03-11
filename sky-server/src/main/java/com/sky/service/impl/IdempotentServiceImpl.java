package com.sky.service.impl;

import com.sky.entity.ShoppingCart;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.IdempotentService;
import com.sky.utils.Md5Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 幂等性服务实现类。
 *
 * 这个类负责两件核心事情：
 * 1. 生成/消费“提交订单令牌”（防前端重复点击）；
 * 2. 生成购物车摘要（作为分布式锁 key 的一部分，降低误串行范围）。
 */
@Service
public class IdempotentServiceImpl implements IdempotentService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    /**
     * Redis 提交令牌 key 前缀。
     *
     * 实际 key 形态：
     * order:submit:token:{userId}:{token}
     */
    private static final String SUBMIT_TOKEN_KEY_PREFIX = "order:submit:token:";

    /**
     * 提交令牌有效期：5 分钟。
     *
     * 设计考虑：
     * - 太短：用户在确认页停留稍久会误失效；
     * - 太长：token 泄露窗口增大。
     */
    private static final long TOKEN_EXPIRE_TIME = 5 * 60;

    @Override
    public String generateSubmitToken(Long userId) {
        // 1) 生成一个高随机 token，避免可预测。
        String token = UUID.randomUUID().toString();

        // 2) 令牌按“用户 + token”存储，确保不同用户互不干扰。
        String key = SUBMIT_TOKEN_KEY_PREFIX + userId + ":" + token;

        // 3) 写入 Redis 并设置 TTL，过期自动回收。
        redisTemplate.opsForValue().set(key, "", TOKEN_EXPIRE_TIME, TimeUnit.SECONDS);

        // 4) 返回给前端，前端下单时必须携带。
        return token;
    }

    @Override
    public boolean validateAndRemoveSubmitToken(Long userId, String token) {
        // 1) 与生成逻辑使用同一 key 规则，保证校验一致性。
        String key = SUBMIT_TOKEN_KEY_PREFIX + userId + ":" + token;

        // 2) delete 在 Redis 层是原子操作：
        //    - key 存在 -> 删除成功，返回 true（表示本次请求可继续）；
        //    - key 不存在 -> 返回 false（表示重复提交或已过期）。
        Boolean result = redisTemplate.delete(key);
        return result != null && result;
    }

    @Override
    public String generateCartDigest(Long userId) {
        // 1) 查询当前用户购物车数据。
        List<ShoppingCart> cartList = shoppingCartMapper.listShoppingCart(userId);

        // 2) 关键点：先排序再摘要，避免“同样内容不同顺序”得到不同 hash。
        //    这样可以稳定地命中同一个锁 key。
        cartList.sort(Comparator
                .comparing((ShoppingCart each) -> Objects.toString(each.getDishId(), ""))
                .thenComparing(each -> Objects.toString(each.getSetmealId(), ""))
                .thenComparing(each -> Objects.toString(each.getDishFlavor(), ""))
                .thenComparing(each -> Objects.toString(each.getNumber(), "")));

        // 3) 拼接一个稳定字符串快照。
        StringBuilder sb = new StringBuilder();
        for (ShoppingCart cart : cartList) {
            sb.append(cart.getDishId()).append(":")
              .append(cart.getSetmealId()).append(":")
              .append(cart.getDishFlavor()).append(":")
              .append(cart.getNumber())
              .append(",");
        }

        // 4) 计算 md5 作为摘要。
        return Md5Util.md5(sb.toString());
    }
}
