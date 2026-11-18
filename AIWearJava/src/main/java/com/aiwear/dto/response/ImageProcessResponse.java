package com.aiwear.dto.response;

import lombok.Data;

@Data
public class ImageProcessResponse {

    private String description;
    private Integer embeddingDim;
    private String imageId;
    private Boolean success;
    private String message;
}
