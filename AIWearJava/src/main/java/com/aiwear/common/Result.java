package com.aiwear.common;

import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Result<T> implements Serializable {
    private Integer code;
    @JsonAlias("message")
    private String msg;
    private T data;

    public static <T> Result<T> success(T data, String msg) {
        return new Result<>(200, msg, data);
    }

    //客户端失败
    public static <T> Result<T> clientError(String msg) {
        return new Result<>(400, msg, null);
    }

    //服务器端失败
    public static <T> Result<T> serverError(String msg) {
        return new Result<>(500, msg, null);
    }
}
