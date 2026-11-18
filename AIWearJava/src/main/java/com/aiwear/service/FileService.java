package com.aiwear.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.aiwear.dto.request.EditImageRequest;
import com.aiwear.dto.request.MergeImageRequest;
import com.aiwear.dto.request.SearchImageRequest;
import com.aiwear.dto.response.EditImageResponse;
import com.aiwear.dto.response.FileUploadResponse;
import com.aiwear.dto.response.MergeImageResponse;
import com.aiwear.dto.response.SearchImageResponse;
import com.aiwear.entity.FileEntity;
import com.aiwear.entity.RecordEntity;

public interface FileService {

    FileUploadResponse uploadImage(MultipartFile file, String authorization);
    List<FileEntity> myImages(String authorization);
    void deleteImage(String authorization, Long fileId);
    List<SearchImageResponse> searchImage(String authorization, SearchImageRequest request);
    EditImageResponse editImage(String authorization, EditImageRequest editImageRequest);
    MergeImageResponse mergeImage(String authorization, MergeImageRequest mergeImageRequest);
    List<RecordEntity> myRecord(String authorization, String action);
    boolean saveEditRecord(Long userId, EditImageResponse editImageResponse, EditImageRequest editImageRequest);
    boolean saveMergeRecord(Long userId, MergeImageResponse mergeImageResponse, MergeImageRequest mergeImageRequest);
}
