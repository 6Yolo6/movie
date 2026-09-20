package com.gying.movie.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String username;
    private String password;
    private String email;
    private String emailCode;
    private String inviteCode;
}
