package com.aiwear.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiwear.dto.request.LoginRequest;
import com.aiwear.dto.request.SendEmailVertificationCodeRequest;
import com.aiwear.dto.response.LoginResponse;
import com.aiwear.dto.response.SendEmailVertificationCodeResponse;
import com.aiwear.log.ApiLog;
import com.aiwear.service.UserService;
import com.aiwear.common.Result;

@ApiLog
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    UserController(UserService userService) {
        this.userService = userService;
    }

    //发送邮箱验证码
    @RequestMapping("/sendEmailCode")
    public Result<SendEmailVertificationCodeResponse> sendEmailVertificationCode(@RequestBody @Valid SendEmailVertificationCodeRequest request) {
        return userService.sendVerificationCode(request);
    }

    //登录
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return userService.login(request);
    }

    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader(value="Authorization") String authorization) {
        boolean success = userService.logut(authorization);
        if(success){
            return Result.success(null,"退出成功");
        }else{
            return Result.serverError("退出失败，请稍后重试");
        }
    }
}
