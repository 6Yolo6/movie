package com.gying.movie.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class AuthRequest {
    @NotBlank @Size(min = 3, max = 50)
    private String username;
    @NotBlank @Size(max = 100)
    private String password;
    @Size(max = 254)
    private String email;
    @Size(max = 16)
    private String emailCode;
    @Size(max = 128)
    private String inviteCode;
}
