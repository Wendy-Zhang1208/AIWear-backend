package com.aiwear.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * EmailVertificationCodeRequest
 * 邮箱验证码请求
 */
@Data
public class EmailVertificationCodeRequest {
    @Email(message = "邮箱格式错误")
    @NotBlank(message = "邮箱不能为空")
    private String email;
    @NotBlank(message = "验证码不能为空")
    private String code;
}
