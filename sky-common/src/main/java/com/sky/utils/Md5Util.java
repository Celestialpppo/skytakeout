package com.sky.utils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5工具类
 * 提供MD5哈希加密功能，用于生成字符串的唯一摘要
 */
public class Md5Util {

    /**
     * 对字符串进行MD5加密
     * @param str 要加密的字符串
     * @return 加密后的32位十六进制字符串
     */
    public static String md5(String str) {
        try {
            // 1. 获取MD5消息摘要实例
            MessageDigest md = MessageDigest.getInstance("MD5");
            
            // 2. 计算字符串的哈希值，返回字节数组
            byte[] bytes = md.digest(str.getBytes());
            
            // 3. 将字节数组转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                // 将字节转换为无符号整数（0-255）
                int value = b & 0xff;
                // 如果值小于16，补零以保证两位十六进制数
                if (value < 16) {
                    sb.append("0");
                }
                // 将整数转换为十六进制字符串并添加到结果中
                sb.append(Integer.toHexString(value));
            }
            // 返回最终的MD5哈希值
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 捕获加密算法不存在的异常，转换为运行时异常
            throw new RuntimeException("MD5加密失败", e);
        }
    }
}
