package com.aiwear.dto.request;

import org.springframework.web.multipart.MultipartFile;

import lombok.Data;

@Data
public class SearchImageRequest {
    private String query;
    private MultipartFile file;
}
