package com.sky.controller.user;

import com.sky.context.BaseContext;
import com.sky.result.Result;
import com.sky.service.IdempotentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 幂等性控制器
 */
@RestController("UserIdempotentController")
@Slf4j
@Api(tags = "幂等性相关接口")
@RequestMapping("/user/idempotent")
public class IdempotentController {
    
    @Autowired
    private IdempotentService idempotentService;
    
    @GetMapping("/submitToken")
    @ApiOperation("获取提交订单的幂等性令牌")
    public Result<String> getSubmitToken() {
        Long userId = BaseContext.getCurrentId();
        String token = idempotentService.generateSubmitToken(userId);
        return Result.success(token);
    }
}
