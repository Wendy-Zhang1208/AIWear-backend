package com.aiwear.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.aiwear.entity.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class jwtUtil {
    private StringRedisTemplate redisTemplate;
    @Value("${jwt.secret}")
    private String secret;
    @Value("${jwt.expiration}")
    private long expirationTime;
    //缓存前缀key
    private final String USER_TOKEN_KEY_PREFIX = "jwt:user:token:";

    public jwtUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generateToken(User user) {
        String tokenKey = USER_TOKEN_KEY_PREFIX + user.getId();
        if(redisTemplate.hasKey(tokenKey)){
            return redisTemplate.opsForValue().get(tokenKey);
        }
        Map<String,Object> claims = new HashMap<>();
        claims.put("userId",user.getId());
        claims.put("username",user.getUsername());
        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expirationTime * 60 * 60 * 1000);
        // 生成密钥对
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        //生成的令牌
        String token = Jwts.builder().setClaims(claims)
                            .setIssuedAt(now).setExpiration(expireDate)
                            .signWith(key,SignatureAlgorithm.HS256).compact();
        //将令牌缓存到Redis中
        redisTemplate.opsForValue().set(tokenKey,token,expirationTime,TimeUnit.HOURS);
        return token;
    }

    //先来获取token
    public String parseToken(String authorization){
        if(authorization == null || authorization.isBlank() || !authorization.startsWith("Bearer ")){
            throw new RuntimeException("缺少请求头令牌！");
        }
        return authorization.substring(7);
    }

    //从token中解析userId
    private Claims getClaims(String token){
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder().setSigningKey(key)
                .build().parseClaimsJws(token).getBody();
    }

    //根据token获取userId
    public Long getUserId(String token){
        Claims claims = getClaims(token);
        Long userId = Long.valueOf(claims.get("userId").toString());
        return userId;
    }  
    
    //删除令牌
    public boolean removeToken(String token){
        if(token==null||token.isBlank()){
            return true;
        }
        Long userId = getUserId(token);
        String tokenKey = USER_TOKEN_KEY_PREFIX+userId;if(redisTemplate.hasKey(tokenKey)){
            return redisTemplate.delete(tokenKey);
        }
        return false;
    }
}
