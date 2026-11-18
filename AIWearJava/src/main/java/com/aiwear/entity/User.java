package com.aiwear.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName(value = "users")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    @TableField("password_hash")
    private String passwordhash;
    private String email;
}
