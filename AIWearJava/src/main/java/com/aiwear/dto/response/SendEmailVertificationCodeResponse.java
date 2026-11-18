package com.aiwear.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * SendEmailVertificationCodeResult
 * 发送邮箱验证码响应
 */
@Data
//不需要手动编写构造方法和对象属性设置代码。
//通过 build() 最终创建对象。
@Builder
public class SendEmailVertificationCodeResponse {
    private String sendTo;
    private Integer expireTime;
}
