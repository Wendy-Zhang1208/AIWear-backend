package com.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

/**
 * LoginRequest
 * 登录请求
 */
@Data
public class LoginRequest {
    @NotBlank(message="账号不能为空")
    private String account;
    private String password;
    private String verificationCode;
}
