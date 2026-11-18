package com.aiwear.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * SendEmailVertificationCodeRequest
 * 发送邮箱验证码请求
 */
@Data
public class SendEmailVertificationCodeRequest {
    @Email(message = "邮箱格式错误")
    @NotBlank(message = "邮箱不能为空")
    private String email;
}
