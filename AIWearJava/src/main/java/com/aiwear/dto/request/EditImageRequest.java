package com.aiwear.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EditImageRequest {
    //当前图片上传了之后产生的oss地址
    @NotBlank(message="图片不能为空")
    private String imageOssUrl;
    //编辑指令
    @NotBlank(message = "编辑图片的指令不能为空")
    @Size(max = 1000, message = "创作描述不能超过1000个字符")
    private String instruction;
}
