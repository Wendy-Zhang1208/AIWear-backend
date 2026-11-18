package com.aiwear.service;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import com.aiwear.common.Result;
import com.aiwear.dto.request.EditImageRequest;
import com.aiwear.dto.request.ImageProcessRequest;
import com.aiwear.dto.request.MergeImageRequest;
import com.aiwear.dto.request.SearchImageRequest;
import com.aiwear.dto.response.EditImageResponse;
import com.aiwear.dto.response.ImageProcessResponse;
import com.aiwear.dto.response.MergeImageResponse;
import com.aiwear.dto.response.SearchImageResponse;
import com.aiwear.exception.FileUploadException;
import com.aiwear.properties.OssProperties;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PythonImageService {

    private final RestClient client;
    private final OSS ossClient;
    private final OssProperties properties;

    public PythonImageService(
            @Value("${python.service.base-url:http://127.0.0.1:5000}") String baseUrl,
            @Value("${python.service.connect-timeout:0s}") Duration connectTimeout,
            @Value("${python.service.read-timeout:0s}") Duration readTimeout,
            @Lazy OSS ossClient, OssProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        // 0 表示持续等待 Python 完成模型推理与入库。
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.ossClient = ossClient;
        this.properties = properties;
    }

    public ImageProcessResponse uploadImage(String ossUrl, Long userId) {
        String failure;
        try {
            ImageProcessResponse response = client.post().uri("/api/upload-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ImageProcessRequest(ossUrl, userId))
                    .retrieve().body(ImageProcessResponse.class);
            if (response != null && Boolean.TRUE.equals(response.getSuccess())) {
                return response;
            }
            failure = response == null
                    ? "EMPTY_RESPONSE：Python 未返回处理结果"
                    : "PROCESS_FAILED：Python 返回处理失败";
        } catch (RestClientResponseException exception) {
            failure = "HTTP_" + exception.getStatusCode().value() + "：Python HTTP 调用失败";
        } catch (ResourceAccessException exception) {
            failure = "CONNECTION_ERROR：Python 服务连接失败";
        } catch (RuntimeException exception) {
            // 不记录原始 HTTP 请求/异常消息，避免签名 URL 或内部配置进入日志。
            failure = "CLIENT_ERROR：" + exception.getClass().getSimpleName();
        }
        ImageProcessResponse response = new ImageProcessResponse();
        response.setSuccess(false);
        response.setMessage(failure);
        return response;
    }

    public void deleteImage(String ossUrl, Long userId) {
        try {
            // 复用入库请求对象，由Python按相同的图片标识规则清理检索缓存。
            ImageProcessResponse response = client.post().uri("/api/delete-image")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ImageProcessRequest(ossUrl, userId))
                    .retrieve().body(ImageProcessResponse.class);
            if (response == null || !Boolean.TRUE.equals(response.getSuccess())) {
                throw new FileUploadException(502, "图片删除未完成，请稍后重试");
            }
        } catch (RestClientException exception) {
            log.error("图片缓存删除调用失败，userId={}，异常类型={}", userId, exception.getClass().getSimpleName());
            throw new FileUploadException(502, "图片删除服务暂时不可用，请稍后重试");
        }
    }

    public boolean validateImage(MultipartFile file) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", file.getResource());
            ImageProcessResponse response = client.post().uri("/app/validate_image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve().body(ImageProcessResponse.class);
            if (response == null || response.getSuccess() == null) {
                throw new FileUploadException(502, "图片校验服务返回了无效响应");
            }
            return response.getSuccess();
        } catch (RestClientException exception) {
            throw new FileUploadException(502, "图片校验服务调用失败，请稍后重试");
        }
    }

    public List<SearchImageResponse> searchImage(Long userId, SearchImageRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("userId", userId.toString());
        if (request.getFile() != null) {
            // MultipartFile 的 Resource 提供真实内容和文件名，Spring 负责 multipart 编码。
            body.add("file", request.getFile().getResource());
        } else {
            body.add("query", request.getQuery());
        }
        ResponseEntity<Result<List<SearchImageResponse>>> httpResponse;
        try {
            httpResponse = client.post().uri("/api/search-image")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    // Python 的参数错误同样返回 code、message、data，读取后统一判断。
                    .retrieve().onStatus(status -> status.isError(), (httpRequest, errorResponse) -> {})
                    .toEntity(new ParameterizedTypeReference<Result<List<SearchImageResponse>>>() {});
        } catch (RestClientException exception) {
            throw new FileUploadException(502, "图片检索服务调用失败，请稍后重试");
        }
        Result<List<SearchImageResponse>> response = httpResponse.getBody();
        if (response == null) {
            throw new FileUploadException(502, "图片检索服务无响应");
        }

        if (!Integer.valueOf(200).equals(response.getCode())) {
            throw new FileUploadException(502, "图片检索失败");
        }

        return response.getData();
    }

    public EditImageResponse editImage(EditImageRequest editImageRequest) {
        return processImages(List.of(editImageRequest.getImageOssUrl()), editImageRequest.getInstruction());
    }

    public MergeImageResponse mergeImage(MergeImageRequest mergeImageRequest) {
        EditImageResponse result = processImages(
                List.of(mergeImageRequest.getImage1Url(), mergeImageRequest.getImage2Url()),
                mergeImageRequest.getInstruction());
        MergeImageResponse response = new MergeImageResponse();
        response.setUrl(result.getUrl());
        response.setSaveUrl(result.getSaveUrl());
        return response;
    }

    // 编辑与合并仅输入图片数量不同，复用请求、结果保存、失败补偿和清理流程。
    private EditImageResponse processImages(List<String> imageUrls, String instruction) {
        List<Path> sourcePaths = new ArrayList<>();
        Path editPath = null;
        String objectKey = null;
        String operation = "编辑";
        if (imageUrls.size() > 1) {
            operation = "合并";
        }
        String stage = "下载原图";
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            // 单图发送 image1，双图按原顺序发送 image1、image2，均为真实文件内容。
            for (int index = 0; index < imageUrls.size(); index++) {
                if (imageUrls.size() > 1) {
                    stage = "下载原图" + (index + 1);
                }
                Path sourcePath = downloadImage(imageUrls.get(index));
                sourcePaths.add(sourcePath);
                body.add("image" + (index + 1), new FileSystemResource(sourcePath));
            }

            stage = "调用Python" + operation + "接口";
            log.info("开始调用Python图片{}接口，图片数量={}", operation, imageUrls.size());
            body.add("instruction", instruction);
            Result<Map<String, String>> response = client.post().uri("/app/skills")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve().body(new ParameterizedTypeReference<Result<Map<String, String>>>() {});
            if (response == null || !Integer.valueOf(200).equals(response.getCode()) || response.getData() == null) {
                log.warn("Python图片{}接口返回无效响应", operation);
                throw new FileUploadException(502, "图片" + operation + "服务返回无效响应");
            }
            Map<String, String> data = response.getData();
            String imageUrl = data.get("image_url");
            if (!"success".equals(data.get("result")) || !StringUtils.hasText(imageUrl)) {
                log.warn("Python未生成{}图片，result={}，message={}", operation, data.get("result"), data.get("message"));
                throw new FileUploadException(502, "图片" + operation + "未成功，请调整指令后重试");
            }

            stage = "下载" + operation + "结果";
            editPath = downloadImage(imageUrl);
            stage = "校验" + operation + "结果";
            ObjectMetadata metadata = new ObjectMetadata();
            String extension;
            // 根据实际图片内容识别格式，并沿用现有像素上限，避免仅凭 URL 后缀判断。
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(editPath.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) {
                    throw new IOException(operation + "结果不是有效图片");
                }
                ImageReader reader = readers.next();
                try {
                    reader.setInput(imageInput, true, true);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > properties.getMaxPixels()) {
                        throw new IOException(operation + "结果超过现有图片像素限制");
                    }
                    reader.read(0).flush();
                    extension = reader.getFormatName().toLowerCase(Locale.ROOT);
                    metadata.setContentType(reader.getOriginatingProvider().getMIMETypes()[0]);
                } finally {
                    reader.dispose();
                }
            }

            // 使用独立对象名保存结果，不覆盖用户的原图；复用已有 OSS 客户端和配置。
            stage = "上传OSS";
            objectKey = "images/edited/" + UUID.randomUUID() + "." + extension;
            metadata.setContentLength(Files.size(editPath));
            PutObjectRequest upload = new PutObjectRequest(properties.getBucketName(), objectKey, editPath.toFile());
            upload.setMetadata(metadata);
            ossClient.putObject(upload);

            EditImageResponse result = new EditImageResponse();
            result.setUrl(imageUrl);
            result.setSaveUrl("https://" + properties.getBucketName() + "." + properties.getEndpoint() + "/" + objectKey);
            log.info("图片{}已保存至OSS，objectKey={}", operation, objectKey);
            return result;
        } catch (IOException | RestClientException | ClientException | OSSException | IllegalArgumentException exception) {
            String detail = exception.getClass().getSimpleName();
            if (exception instanceof RestClientResponseException httpException) {
                detail = "HTTP_" + httpException.getStatusCode().value();
            } else if (exception instanceof OSSException ossException) {
                detail = ossException.getErrorCode() + "，requestId=" + ossException.getRequestId();
            } else if (exception instanceof IOException) {
                detail = exception.getMessage();
            }
            log.error("图片{}失败，阶段={}，objectKey={}，原因={}", operation, stage, objectKey, detail);
            // 上传超时也可能已写入对象，只补偿删除本次生成的唯一对象名。
            if (objectKey != null) {
                try {
                    ossClient.deleteObject(properties.getBucketName(), objectKey);
                } catch (ClientException | OSSException cleanupException) {
                    log.error("图片{}的OSS补偿删除失败，objectKey={}，异常类型={}",
                            operation, objectKey, cleanupException.getClass().getSimpleName());
                }
            }
            // 阶段详情已经写入日志，用户只需要知道操作失败及重试方式。
            throw new FileUploadException(502, "图片" + operation + "失败，请稍后重试");
        } finally {
            // Java 中转文件在成功和失败时都释放，Python 的重试素材由 Python 自行保留。
            for (Path sourcePath : sourcePaths) {
                deleteTempFile(sourcePath);
            }
            deleteTempFile(editPath);
        }
    }

    private Path downloadImage(String imageUrl) throws IOException {
        URI uri = URI.create(imageUrl);
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("图片地址必须是有效的HTTP或HTTPS地址");
        }
        // 自有 OSS 对象通过 SDK 签名下载，兼容私有 Bucket；模型结果地址保留原有签名参数。
        if ((properties.getBucketName() + "." + properties.getEndpoint()).equalsIgnoreCase(uri.getHost())) {
            uri = URI.create(ossClient.generatePresignedUrl(properties.getBucketName(), uri.getPath().substring(1),
                    new Date(System.currentTimeMillis() + 600_000)).toString());
        }
        log.info("开始下载图片，host={}", uri.getHost());
        byte[] content = client.get().uri(uri).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IOException("图片下载HTTP状态异常：" + response.getStatusCode().value());
            }
            byte[] bytes = response.getBody().readNBytes(Math.toIntExact(properties.getMaxSize() + 1));
            if (bytes.length == 0 || bytes.length > properties.getMaxSize()) {
                throw new IOException("图片为空或超过现有大小限制");
            }
            return bytes;
        });
        Path path = Files.createTempFile("aiwear-edit-", ".img");
        try {
            return Files.write(path, content);
        } catch (IOException exception) {
            deleteTempFile(path);
            throw exception;
        }
    }

    private void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("图片处理临时文件清理失败，path={}，原因={}", path, exception.getMessage());
        }
    }
}

