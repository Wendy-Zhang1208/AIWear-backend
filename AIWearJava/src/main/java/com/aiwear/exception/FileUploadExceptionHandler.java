package com.aiwear.exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import com.aiwear.common.Result;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class FileUploadExceptionHandler {

    // 参数校验在进入Controller前执行，统一返回用户能理解的提示。
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().isEmpty()
                ? "提交的信息不完整，请检查后重试"
                : exception.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("请求参数校验未通过，字段={}", exception.getBindingResult().getFieldErrors()
                .stream().map(error -> error.getField()).toList());
        return Result.clientError(message);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    public Result<Void> handleRequestException(Exception exception) {
        log.warn("请求内容解析失败，异常类型={}", exception.getClass().getSimpleName());
        if (exception instanceof MissingRequestHeaderException headerException
                && "Authorization".equalsIgnoreCase(headerException.getHeaderName())) {
            return new Result<>(401, "请先登录后再操作", null);
        }
        return Result.clientError("提交的信息不完整或格式不正确，请检查后重试");
    }

    // Multipart 解析可能发生在找到 Controller 之前，因此按路径限制处理范围。
    @ExceptionHandler(MultipartException.class)
    public Result<Void> handleMultipartException(MultipartException exception, HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!"POST".equals(request.getMethod())
                || !("/api/file/upload/image".equals(path) || "/api/file/search".equals(path)
                        || "/api/file/search/image".equals(path))) {
            throw exception;
        }
        log.warn("图片请求解析失败，异常类型={}", exception.getClass().getSimpleName());
        if (exception instanceof MaxUploadSizeExceededException) {
            return Result.clientError("每张图片不能超过10MB，请选择较小的图片");
        }
        return Result.clientError("上传请求不正确，请使用 multipart/form-data 并传入 file");
    }
}
