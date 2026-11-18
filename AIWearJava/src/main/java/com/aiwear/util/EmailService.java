package com.aiwear.util;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;


@Service
@Slf4j
public class EmailService {
    private JavaMailSender mailSender;
    @Value("${spring.mail.username}")
    private String fromEmail;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // 发送验证码邮件
    public boolean sendVerificationCode(String toEmail, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(toEmail);
            message.setSubject("AIWear邮箱登录验证码");
            message.setText("尊敬的用户，您好！\n\n" +
            "您的登录验证码是：" + code + "\n\n" +
            "验证码有效期5分钟。"
            );
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("验证码发送失败，收件人{}，异常信息{}", toEmail, e.getMessage());
            return false;
        }
    }
}
