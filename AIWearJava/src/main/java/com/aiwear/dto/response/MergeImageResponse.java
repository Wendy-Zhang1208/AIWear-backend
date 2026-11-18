package com.aiwear.dto.response;

import lombok.Data;

@Data
public class MergeImageResponse {
    //经过python服务返回的url地址
    private String url;
    //需要保存的oss地址
    private String saveUrl;
}
