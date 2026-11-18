package com.aiwear.controller;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aiwear.common.Result;
import com.aiwear.dto.request.EditImageRequest;
import com.aiwear.dto.request.MergeImageRequest;
import com.aiwear.dto.request.SearchImageRequest;
import com.aiwear.dto.response.EditImageResponse;
import com.aiwear.dto.response.FileUploadResponse;
import com.aiwear.dto.response.MergeImageResponse;
import com.aiwear.dto.response.SearchImageResponse;
import com.aiwear.entity.FileEntity;
import com.aiwear.entity.RecordEntity;
import com.aiwear.exception.FileUploadException;
import com.aiwear.log.ApiLog;
import com.aiwear.service.FileService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;


@ApiLog
@RestController
@RequestMapping("/api/file")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload/image")
    public Result<FileUploadResponse> uploadImage(
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        try {
            // 从 request 读取 Token，避免 ApiLog 将 Authorization 参数写入日志。
            return Result.success(fileService.uploadImage(file, request.getHeader("Authorization")), "图片上传成功");
        } catch (FileUploadException exception) {
            // 先转为 Result，让现有 ApiLog 保留业务提示，而不是统一改成通用 500。
            return new Result<>(exception.getCode(), exception.getMessage(), null);
        }
    }

    //我的图片列表
    @PostMapping("/my/images")
    public Result<List<FileEntity>> myImages(HttpServletRequest request) {
        return Result.success(fileService.myImages(request.getHeader("Authorization")), "获取我的图片列表成功");
    }

    @DeleteMapping("/delete/image")
    public Result<Void> deleteImage(@RequestParam("fileId") Long fileId, HttpServletRequest request) {
        fileService.deleteImage(request.getHeader("Authorization"), fileId);
        return Result.success(null, "图片删除成功");
    }

    // 兼容前端正在使用的 /search，以及原有预留的 /search/image。
    @PostMapping({"/search", "/search/image"})
    public Result<List<SearchImageResponse>> searchImage(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        SearchImageRequest searchRequest = new SearchImageRequest();
        searchRequest.setQuery(query);
        searchRequest.setFile(file);
        try {
            return Result.success(fileService.searchImage(request.getHeader("Authorization"), searchRequest), "图片搜索成功");
        } catch (FileUploadException exception) {
            // 与上传接口一致，避免现有 ApiLog 把业务错误替换成通用 500。
            return new Result<>(exception.getCode(), exception.getMessage(), null);
        }
    }

    @PostMapping("/edit_image")
    public Result<EditImageResponse> editImage(
            @RequestBody @Validated EditImageRequest editImageRequest,
            HttpServletRequest request) {
        return Result.success(fileService.editImage(request.getHeader("Authorization"), editImageRequest), "编辑图片成功");
    }

    @PostMapping("/merge_images")
    public Result<MergeImageResponse> mergeImage(
            @RequestBody @Validated MergeImageRequest mergeImageRequest,
            HttpServletRequest request){
        return Result.success(fileService.mergeImage(request.getHeader("Authorization"), mergeImageRequest), "合并图片成功");
    }

    @GetMapping("/my_record")
    public Result<List<RecordEntity>> myRecord(
            @RequestParam(value = "action", required = false) String action,
            HttpServletRequest request) {
        return Result.success(fileService.myRecord(request.getHeader("Authorization"), action), "获取我的图片编辑/合并记录成功");
    }
    
}
