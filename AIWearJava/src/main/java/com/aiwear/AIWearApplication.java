package com.aiwear;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@Slf4j
// 启动类
public class AIWearApplication {

    public static void main(String[] args) {
        log.info("AIWearApplication.main() 启动成功");
        SpringApplication.run(AIWearApplication.class, args);
    }
}