package com.aiwear.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    // 使用地域 Endpoint 主机名，不填写协议、Bucket、路径或签名参数。
    @NotBlank
    @Pattern(regexp = "oss-[a-z0-9-]+\\.aliyuncs\\.com")
    private String endpoint;

    private String accessKeyId;
    private String accessKeySecret;

    @NotBlank
    @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
    private String bucketName;

    @Min(1)
    @Max(10485760)
    private long maxSize;

    // 限制解码后的像素总数，避免体积很小、分辨率极大的图片耗尽内存。
    @Min(1)
    @Max(25000000)
    private long maxPixels;
}
