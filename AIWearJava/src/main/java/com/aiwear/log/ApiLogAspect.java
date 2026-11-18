package com.aiwear.log;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.aiwear.common.Result;
import com.aiwear.exception.FileUploadException;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

// API 日志切面
@Component
@Aspect
@Slf4j
public class ApiLogAspect {
    // 定义切点
    @Pointcut("@within(com.aiwear.log.ApiLog)")
    public void apiPointCut(){}

    //环绕通知
    @Around("apiPointCut()")
    public Object around(ProceedingJoinPoint joinPoint){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes.getRequest();
        String path = request.getRequestURI();
        String methodName = joinPoint.getSignature().toShortString();
        long startTime = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (FileUploadException exception) {
            // 保留图片业务的错误码和中文提示，登录失效不再被改成通用500。
            log.warn("图片请求未完成，path={}，code={}，原因={}", path, exception.getCode(), exception.getMessage());
            result = new Result<>(exception.getCode(), exception.getMessage(), null);
        } catch (JwtException exception) {
            log.warn("登录凭据校验失败，path={}，异常类型={}", path, exception.getClass().getSimpleName());
            result = new Result<>(401, "登录状态已失效，请重新登录", null);
        } catch (Throwable exception) {
            // 异常详情只留在服务端，前端返回通用提示。
            log.error("接口执行失败，method={}，path={}", methodName, path, exception);
            result = Result.serverError("服务暂时不可用，请稍后重试");
        }
        // 只记录定位问题需要的信息，避免密码、验证码和登录凭据进入日志。
        Integer code = result instanceof Result<?> response ? response.getCode() : null;
        log.info("API请求结束，method={}，path={}，code={}，耗时={}ms",
                methodName, path, code, System.currentTimeMillis() - startTime);
        return result;
    } 
}
