package com.aiwear.dto.response;

import lombok.Data;

/**
 * SearchImageResponse
 */
@Data
public class SearchImageResponse {
    private String filePath;
    private String fileName;
    private Double similarity;
}
