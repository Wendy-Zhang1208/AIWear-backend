package com.aiwear.service;

import com.aiwear.common.Result;
import com.aiwear.dto.request.LoginRequest;
import com.aiwear.dto.request.SendEmailVertificationCodeRequest;
import com.aiwear.dto.response.LoginResponse;
import com.aiwear.dto.response.SendEmailVertificationCodeResponse;

import jakarta.validation.Valid;

import org.springframework.stereotype.Service;

@Service
public interface UserService {
    Result<SendEmailVertificationCodeResponse> sendVerificationCode(SendEmailVertificationCodeRequest request);
    Result<LoginResponse> login(@Valid LoginRequest request);
    boolean logut(String authorization);
}
