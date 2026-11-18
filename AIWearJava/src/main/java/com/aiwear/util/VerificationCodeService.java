package com.aiwear.util;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class VerificationCodeService {

    private final StringRedisTemplate redisTemplate;
    private static final String CODE_KEY_PREFIX = "verification:code:";
    private static final int TIMEOUT_SECONDS = 5 * 60;


    public VerificationCodeService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // 生成6位随机验证码
    public String generateCode() {
        SecureRandom random = new SecureRandom();
        String code = String.format("%06d", random.nextInt(1000000));
        return code;
    }

    // 保存验证码
    public void saveCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        redisTemplate.opsForValue().set(key, code, TIMEOUT_SECONDS,
        TimeUnit.SECONDS);
    }

    // 验证码是否存在
    public boolean hasCode(String email) {
        String key = CODE_KEY_PREFIX + email;
        return redisTemplate.hasKey(key);
    }

    // 校验验证码
    public boolean verifyCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        if (redisTemplate.hasKey(key)) {
            String oldCode = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
            return code.equals(oldCode);
        } else {
            log.warn("验证码不存在或已过期， 邮箱{}", email);
            return false;
        }
    }
}
