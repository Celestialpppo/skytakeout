package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.PayCallbackService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付回调控制器
 */
@RestController
@Slf4j
@Api(tags = "支付回调相关接口")
@RequestMapping("/user/pay/callback")
public class PayCallbackController {
    
    @Autowired
    private PayCallbackService payCallbackService;
    
    @PostMapping("/wechat")
    @ApiOperation("微信支付回调")
    public String wechatCallback(@RequestBody Map<String, Object> callbackData) {
        log.info("微信支付回调：{}", callbackData);
        return payCallbackService.handleWechatCallback(callbackData);
    }
    
    @PostMapping("/alipay")
    @ApiOperation("支付宝支付回调")
    public String alipayCallback(@RequestBody Map<String, Object> callbackData) {
        log.info("支付宝支付回调：{}", callbackData);
        return payCallbackService.handleAlipayCallback(callbackData);
    }
}
