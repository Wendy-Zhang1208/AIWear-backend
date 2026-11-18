package com.aiwear.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("files")
public class FileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String fileName;
    private Long fileSize;
    private String ossUrl;
}
