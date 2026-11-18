package com.aiwear.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import com.aiwear.properties.OssProperties;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OssProperties.class)
public class OssConfig {

    // 首次上传时才初始化；整个应用复用同一个客户端，关闭应用时释放连接。
    @Lazy
    @Bean(destroyMethod = "shutdown")
    public OSS ossClient(OssProperties properties) {
        return new OSSClientBuilder().build(
                "https://" + properties.getEndpoint(),
                properties.getAccessKeyId(),
                properties.getAccessKeySecret());
    }
}
