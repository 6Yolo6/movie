package com.gying.movie.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminCreateUserRequest {
    @NotBlank @Size(min = 3, max = 50)
    private String username;
    @NotBlank @Size(max = 200)
    private String email;
    @NotBlank @Size(min = 12, max = 72)
    private String password;
    @NotBlank @Pattern(regexp = "USER|PUBLISHER")
    private String role = "USER";
}
