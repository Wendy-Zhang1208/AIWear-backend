package com.aiwear.dto.response;

import lombok.Data;

@Data
public class LoginResponse {
    private Long userId;
    private String username;
    private String email;
    private String token;
}
