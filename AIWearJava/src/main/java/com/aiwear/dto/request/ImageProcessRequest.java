package com.aiwear.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ImageProcessRequest {

    private String ossUrl;
    private Long userId;
}
