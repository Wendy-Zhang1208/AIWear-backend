package com.aiwear.mapper;
import org.apache.ibatis.annotations.Mapper;

import com.aiwear.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;


@Mapper
public interface UserMapper extends BaseMapper<User> {
}
