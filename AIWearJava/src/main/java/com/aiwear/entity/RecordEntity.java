package com.aiwear.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("records")
public class RecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String action;
    private String instruction;
    private String inputOssUrl1;
    private String inputOssUrl2;
    private String outputOssUrl;
    private String resultUrl;
}
