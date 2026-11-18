package com.aiwear.service.impl;

import com.aiwear.common.Result;
import com.aiwear.dto.request.LoginRequest;
import com.aiwear.dto.request.SendEmailVertificationCodeRequest;
import com.aiwear.dto.response.LoginResponse;
import com.aiwear.dto.response.SendEmailVertificationCodeResponse;
import com.aiwear.entity.User;
import com.aiwear.mapper.UserMapper;
import com.aiwear.service.UserService;
import com.aiwear.util.EmailService;
import com.aiwear.util.VerificationCodeService;
import com.aiwear.util.jwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;
    private final UserMapper userMapper;
    private final jwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(EmailService emailService, VerificationCodeService verificationCodeService, UserMapper userMapper,jwtUtil jwtUtil) {
        this.emailService = emailService;
        this.verificationCodeService = verificationCodeService;
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil; 
    }

    // 发送验证码邮件
    @Override
    public Result<SendEmailVertificationCodeResponse> sendVerificationCode(SendEmailVertificationCodeRequest request) {
        String email = request.getEmail();
        if (verificationCodeService.hasCode(email)) {
            return Result.clientError("验证码尚未过期，请勿重复发送");
        }
        String code = verificationCodeService.generateCode();
        verificationCodeService.saveCode(email, code);
        if (!emailService.sendVerificationCode(email, code)) {
            return Result.serverError("发送验证码失败，请稍后重试");
        }
         return Result.success(SendEmailVertificationCodeResponse.builder().sendTo(email).expireTime(60 * 5).build(), "发送邮箱验证码成功");
    }

    // 登录
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<LoginResponse> login(@Valid LoginRequest request) {
        //1.获取用戶名
        String account = request.getAccount();
        //2.判断当前认证方式
        boolean isEmail = account.contains("@");
        //3.查询用戶是否存在
        LambdaQueryWrapper<User>queryWrapper=new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername,account);
        User user = userMapper.selectOne(queryWrapper);
        //4.邮箱+验证码的方式
        if(isEmail){
        String code = request.getVerificationCode();
        if(code==null||code.isEmpty()){throw new RuntimeException("验证码不能为空");}
        // 5.验证验证码
        if(!verificationCodeService.verifyCode(account,code)){
            return Result.clientError("验证码不正确或者已经过期");
        }
        //6.处理新老用戶
        if(user == null){
            user = new User();
            user.setUsername(account);
            user.setEmail(account);
            userMapper.insert(user);
            log.info("新用戶注册成功，邮箱{}",user.getEmail());
        }}else{
            //7.用戶名+密码
            if(user==null){ 
                user = new User();
                user.setUsername(account);
                if(request.getPassword()==null||request.getPassword()==""){
                    return Result.clientError("密码不能为空");
                }
                user.setPasswordhash(passwordEncoder.encode(request.getPassword()));
                userMapper.insert(user);
                log.info("新用戶注册成功，邮箱{}",user.getEmail());
            }else{
                if(user.getPasswordhash()==null || !passwordEncoder.matches(request.getPassword(),user.getPasswordhash())){
                    return Result.clientError("用戶名或密码错误");
                }
            }
        }
        return Result.success(createResponse(user), "登录成功");
    }

    //退出登录
    @Override
    public boolean logut(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        return jwtUtil.removeToken(token);
    }

    //返回认证的响应
    private LoginResponse createResponse(User user){
        LoginResponse response = new LoginResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setToken(jwtUtil.generateToken(user));
        return response;
    }
}
