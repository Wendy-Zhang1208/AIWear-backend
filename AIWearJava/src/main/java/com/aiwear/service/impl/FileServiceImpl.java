package com.aiwear.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.aiwear.dto.request.EditImageRequest;
import com.aiwear.dto.request.MergeImageRequest;
import com.aiwear.dto.request.SearchImageRequest;
import com.aiwear.dto.response.EditImageResponse;
import com.aiwear.dto.response.FileUploadResponse;
import com.aiwear.dto.response.ImageProcessResponse;
import com.aiwear.dto.response.MergeImageResponse;
import com.aiwear.dto.response.SearchImageResponse;
import com.aiwear.entity.FileEntity;
import com.aiwear.entity.RecordEntity;
import com.aiwear.exception.FileUploadException;
import com.aiwear.mapper.FileMapper;
import com.aiwear.mapper.RecordMapper;
import com.aiwear.properties.OssProperties;
import com.aiwear.service.FileService;
import com.aiwear.service.PythonImageService;
import com.aiwear.util.jwtUtil;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class FileServiceImpl implements FileService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png");
    private static final DateTimeFormatter DIRECTORY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final FileMapper fileMapper;
    private final jwtUtil jwtUtil;
    private final StringRedisTemplate redisTemplate;
    private final OSS ossClient;
    private final OssProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final RecordMapper recordMapper;

    @Autowired
    private PythonImageService pythonImageService;

    @Value("${python.service.sign-oss-url:false}")
    private boolean signOssUrl;

    public FileServiceImpl(FileMapper fileMapper, jwtUtil jwtUtil, StringRedisTemplate redisTemplate,
            @Lazy OSS ossClient, OssProperties properties, PlatformTransactionManager transactionManager, RecordMapper recordMapper) {
        this.fileMapper = fileMapper;
        this.jwtUtil = jwtUtil;
        this.redisTemplate = redisTemplate;
        this.ossClient = ossClient;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // 确保 executeWithoutResult 返回前事务已提交，不加入调用方尚未提交的事务。
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.recordMapper = recordMapper;
    }

    @Override
    public FileUploadResponse uploadImage(MultipartFile file, String authorization) {
        Long userId = getCurrentUserId(authorization);
        if (file == null || file.isEmpty()) {
            throw new FileUploadException(400, "图片文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new FileUploadException(400, "图片文件名不能为空");
        }
        // 文件大小由 Spring multipart 的现有配置限制；这里只保留落库所需的文件名处理。
        String fileName = StringUtils.getFilename(originalName.replace('\\', '/'))
                .replaceAll("[\\p{Cntrl}\\p{Cf}<>:\"/\\\\|?*]", "_").strip();
        if (fileName.length() > 255 || fileName.lastIndexOf('.') <= 0) {
            throw new FileUploadException(400, "图片文件名不合法或超过255个字符");
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        validateImageContent(file, extension);

        if (!StringUtils.hasText(properties.getAccessKeyId())
                || !StringUtils.hasText(properties.getAccessKeySecret())) {
            throw new FileUploadException(500, "图片存储服务尚未配置，请联系管理员");
        }

        String objectKey = "images/" + LocalDate.now().format(DIRECTORY_FORMAT)
                + "/" + UUID.randomUUID() + "." + extension;
        String url = "https://" + properties.getBucketName() + "." + properties.getEndpoint() + "/" + objectKey;
        if (!pythonImageService.validateImage(file)) {
            throw new FileUploadException(500, "图片内容不符合要求，上传失败。");
        }

        uploadToOss(file, extension, objectKey);

        FileEntity entity = new FileEntity();
        entity.setUserId(userId);
        entity.setFileName(fileName);
        // MultipartFile 的大小来自服务端解析后的文件部分，不取客户端 Content-Length。
        entity.setFileSize(file.getSize());
        entity.setOssUrl(url);
        try {
            // OSS 网络请求完成后才开启短事务；此处同时捕获插入失败和最终提交失败。
            transactionTemplate.executeWithoutResult(status -> {
                if (fileMapper.insert(entity) != 1) {
                    throw new IllegalStateException("图片记录插入条数不正确");
                }
            });
        } catch (RuntimeException exception) {
            log.error("图片记录保存或提交失败，objectKey={}，异常类型={}",
                    objectKey, exception.getClass().getSimpleName());
            deleteUploadedObject(objectKey);
            throw new FileUploadException(500, "图片信息保存失败，请稍后重试");
        }
        // executeWithoutResult 已完成独立事务提交。索引失败不参与上面的 OSS 补偿与事务回滚。
        try {
            // 私有 Bucket 复用现有 OSS SDK 签名，原上传响应与 MySQL 仍保存原始地址。
            String processUrl = signOssUrl
                    ? ossClient.generatePresignedUrl(properties.getBucketName(), objectKey,
                            new Date(System.currentTimeMillis() + 600_000)).toString()
                    : url;
            ImageProcessResponse result = pythonImageService.uploadImage(processUrl, userId);
            // 用户可能在索引生成期间删除素材；等待删除事务结束后再确认，防止缓存被重新写回。
            transactionTemplate.executeWithoutResult(status -> {
                FileEntity current = fileMapper.selectOne(new LambdaQueryWrapper<FileEntity>()
                        .eq(FileEntity::getId, entity.getId()).last("FOR UPDATE"));
                if (current == null) {
                    pythonImageService.deleteImage(url, userId);
                }
            });
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                log.error("图片已上传但未完成检索索引，userId={}，fileId={}，objectKey={}，原因={}",
                        userId, entity.getId(), objectKey, result.getMessage());
            } else {
                log.info("图片检索索引已生成，userId={}，fileId={}，imageId={}",
                        userId, entity.getId(), result.getImageId());
            }
        } catch (RuntimeException exception) {
            log.error("图片已上传但索引调用异常，userId={}，fileId={}，objectKey={}，异常类型={}",
                    userId, entity.getId(), objectKey, exception.getClass().getSimpleName());
        }
        return new FileUploadResponse(url, fileName, file.getSize());
    }

    private Long getCurrentUserId(String authorization) {
        String token;
        Long userId;
        try {
            token = jwtUtil.parseToken(authorization);
            userId = jwtUtil.getUserId(token);
            if (userId <= 0) {
                throw new IllegalArgumentException("用户ID无效");
            }
        } catch (RuntimeException exception) {
            log.warn("图片业务登录校验未通过，异常类型={}", exception.getClass().getSimpleName());
            throw new FileUploadException(401, "登录状态已失效，请重新登录");
        }
        try {
            // 图片相关接口共用登录态校验，退出后的旧登录凭据不能继续访问。
            String activeToken = redisTemplate.opsForValue().get("jwt:user:token:" + userId);
            if (!token.equals(activeToken)) {
                log.warn("图片业务登录状态已失效，userId={}", userId);
                throw new FileUploadException(401, "登录状态已失效，请重新登录");
            }
        } catch (DataAccessException exception) {
            log.error("图片业务登录态校验失败，异常类型={}", exception.getClass().getSimpleName());
            throw new FileUploadException(500, "登录状态校验失败，请稍后重试");
        }
        return userId;
    }

    private void validateImageContent(MultipartFile file, String extension) {
        if (!IMAGE_EXTENSIONS.contains(extension)) {
            throw new FileUploadException(400, "仅支持 JPG、JPEG、PNG 图片");
        }
        try (InputStream input = file.getInputStream();
                MemoryCacheImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new FileUploadException(400, "文件内容不是有效图片");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String expectedFormat = "png".equals(extension) ? "png" : "jpeg";
                if (!expectedFormat.equals(format)) {
                    throw new FileUploadException(400, "图片内容与文件后缀不一致");
                }
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > properties.getMaxPixels()) {
                    throw new FileUploadException(400, "图片像素总数不能超过 " + properties.getMaxPixels());
                }
                // 完整解码，不能只凭扩展名、Content-Type 或几个文件头字节判断。
                reader.read(0).flush();
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new FileUploadException(400, "图片内容无效或已损坏");
        }
    }

    private void uploadToOss(MultipartFile file, String extension, String objectKey) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType("png".equals(extension) ? "image/png" : "image/jpeg");
        boolean uploadStarted = false;
        try (InputStream input = file.getInputStream()) {
            uploadStarted = true;
            ossClient.putObject(properties.getBucketName(), objectKey, input, metadata);
        } catch (IOException | RuntimeException exception) {
            logOssFailure("图片上传失败", objectKey, exception);
            // 网络超时也可能已经写入 OSS；只清理本次生成的唯一 Object Key。
            if (uploadStarted) {
                deleteUploadedObject(objectKey);
            }
            throw new FileUploadException(500, "图片上传失败，请稍后重试");
        }
    }

    private void deleteUploadedObject(String objectKey) {
        try {
            ossClient.deleteObject(properties.getBucketName(), objectKey);
        } catch (RuntimeException exception) {
            logOssFailure("OSS补偿删除失败，需要人工清理", objectKey, exception);
        }
    }

    private void logOssFailure(String operation, String objectKey, Exception exception) {
        // 不记录原始 SDK 异常消息或请求，避免泄露凭据、签名及内部细节。
        if (exception instanceof OSSException ossException) {
            log.error("{}，objectKey={}，errorCode={}，requestId={}",
                    operation, objectKey, ossException.getErrorCode(), ossException.getRequestId());
        } else {
            log.error("{}，objectKey={}，异常类型={}", operation, objectKey, exception.getClass().getSimpleName());
        }
    }

    @Override
    public List<FileEntity> myImages(String authorization) {
        Long userId = getCurrentUserId(authorization);
        LambdaQueryWrapper<FileEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FileEntity::getUserId,userId).orderByDesc(FileEntity::getId);
        return fileMapper.selectList(queryWrapper);
    }

    @Override
    public void deleteImage(String authorization, Long fileId) {
        Long userId = getCurrentUserId(authorization);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 按用户和文件ID锁定素材，避免越权删除或同一图片被同时删除。
                FileEntity image = fileMapper.selectOne(new LambdaQueryWrapper<FileEntity>()
                        .eq(FileEntity::getId, fileId).eq(FileEntity::getUserId, userId).last("FOR UPDATE"));
                if (image == null) {
                    throw new FileUploadException(404, "图片不存在或已删除");
                }
                if (fileMapper.deleteById(fileId) != 1) {
                    throw new IllegalStateException("素材删除条数不正确");
                }
                // 缓存删除失败会使数据库事务回滚；没有缓存的素材也允许正常删除。
                pythonImageService.deleteImage(image.getOssUrl(), userId);
            });
        } catch (FileUploadException exception) {
            log.warn("素材删除未完成，userId={}，fileId={}，原因={}", userId, fileId, exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            log.error("素材删除失败，userId={}，fileId={}", userId, fileId, exception);
            throw new FileUploadException(500, "图片删除失败，请稍后重试");
        }
        log.info("素材及检索缓存已删除，userId={}，fileId={}", userId, fileId);
    }

    @Override
    public List<SearchImageResponse> searchImage(String authorization, SearchImageRequest request) {
        Long userId = getCurrentUserId(authorization);
        boolean hasQuery = request.getQuery() != null;
        boolean hasFile = request.getFile() != null;
        if (hasQuery == hasFile) {
            throw new FileUploadException(400, "请输入搜索文字或选择一张图片，一次只能使用一种搜索方式");
        }
        if (hasQuery) {
            String query = request.getQuery().strip();
            if (query.isEmpty()) {
                throw new FileUploadException(400, "查询文本不能为空");
            }
            request.setQuery(query);
        } else if (request.getFile().isEmpty() || !StringUtils.hasText(request.getFile().getOriginalFilename())) {
            throw new FileUploadException(400, "查询图片不能为空");
        }
        return pythonImageService.searchImage(userId, request);
    }

    @Override
    public EditImageResponse editImage(String authorization, EditImageRequest editImageRequest) {
        Long userId = getCurrentUserId(authorization);
        // 生成前确认素材归属，记录中的用户ID只使用服务端认证结果。
        if (!ownsImage(userId, editImageRequest.getImageOssUrl())) {
            throw new FileUploadException(400, "图片不存在");
        }
        EditImageResponse response = pythonImageService.editImage(editImageRequest);
        saveEditRecord(userId, response, editImageRequest);
        return response;
    }

    @Override
    public MergeImageResponse mergeImage(String authorization, MergeImageRequest mergeImageRequest) {
        Long userId = getCurrentUserId(authorization);
        // 两张素材都必须属于当前用户，图片顺序与合并指令保持一致。
        if (!ownsImage(userId, mergeImageRequest.getImage1Url())
                || !ownsImage(userId, mergeImageRequest.getImage2Url())) {
            throw new FileUploadException(400, "图片不存在");
        }
        MergeImageResponse response = pythonImageService.mergeImage(mergeImageRequest);
        saveMergeRecord(userId, response, mergeImageRequest);
        return response;
    }

    private boolean ownsImage(Long userId, String ossUrl) {
        return fileMapper.selectCount(new LambdaQueryWrapper<FileEntity>()
                .eq(FileEntity::getUserId, userId).eq(FileEntity::getOssUrl, ossUrl)) > 0;
    }

    @Override
    public List<RecordEntity> myRecord(String authorization, String action) {
        Long userId = getCurrentUserId(authorization);
        LambdaQueryWrapper<RecordEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(RecordEntity::getUserId,userId);
        if(action != null && !action.isBlank()){
            queryWrapper.eq(RecordEntity::getAction,action);
        }
        queryWrapper.orderByDesc(RecordEntity::getId);
        return recordMapper.selectList(queryWrapper);
    }

    @Override
    public boolean saveEditRecord(Long userId, EditImageResponse editImageResponse, EditImageRequest editImageRequest) {
        RecordEntity record = new RecordEntity();
        record.setUserId(userId);
        record.setAction("edit");
        record.setInstruction(editImageRequest.getInstruction());
        record.setInputOssUrl1(editImageRequest.getImageOssUrl());
        record.setOutputOssUrl(editImageResponse.getSaveUrl());
        record.setResultUrl(editImageResponse.getUrl());
        return saveRecord(record);
    }

    @Override
    public boolean saveMergeRecord(Long userId, MergeImageResponse mergeImageResponse, MergeImageRequest mergeImageRequest) {
        RecordEntity record = new RecordEntity();
        record.setUserId(userId);
        record.setAction("merge");
        record.setInstruction(mergeImageRequest.getInstruction());
        record.setInputOssUrl1(mergeImageRequest.getImage1Url());
        record.setInputOssUrl2(mergeImageRequest.getImage2Url());
        record.setOutputOssUrl(mergeImageResponse.getSaveUrl());
        record.setResultUrl(mergeImageResponse.getUrl());
        return saveRecord(record);
    }

    private boolean saveRecord(RecordEntity record) {
        try {
            // 生成与OSS上传已经结束，只为记录落库开启短事务，提交成功后才返回。
            transactionTemplate.executeWithoutResult(status -> {
                if (recordMapper.insert(record) != 1) {
                    throw new IllegalStateException("创作记录插入条数不正确");
                }
            });
        } catch (RuntimeException exception) {
            log.error("创作记录保存失败，userId={}，action={}", record.getUserId(), record.getAction(), exception);
            // 仅清理本次生成的唯一OSS文件，不删除输入素材。
            String objectKey = URI.create(record.getOutputOssUrl()).getPath().substring(1);
            deleteUploadedObject(objectKey);
            throw new FileUploadException(500, "创作记录保存失败，请稍后重试");
        }
        log.info("创作记录已保存，userId={}，recordId={}，action={}",
                record.getUserId(), record.getId(), record.getAction());
        return true;
    }
}
