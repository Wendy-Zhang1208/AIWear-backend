package com.aiwear.exception;

import lombok.Getter;

@Getter
public class FileUploadException extends RuntimeException {

    private final int code;

    public FileUploadException(int code, String message) {
        super(message);
        this.code = code;
    }
}
