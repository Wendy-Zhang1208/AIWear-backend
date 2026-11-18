package com.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MergeImageRequest {
    @NotBlank(message="图片1不能为空")
    private String image1Url;
    @NotBlank(message="图片2不能为空")
    private String image2Url;
    @NotBlank(message="合并指令不能为空")
    @Size(max = 1000, message = "创作描述不能超过1000个字符")
    private String instruction;
}
