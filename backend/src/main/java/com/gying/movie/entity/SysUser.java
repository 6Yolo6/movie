package com.gying.movie.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String nickname;
    private String password;
    private String email;
    private LocalDateTime emailUpdatedAt;
    private String role;
    private Integer score;
    private Boolean enabled;
    private Long invitedByUserId;
    private LocalDateTime createdAt;

    /** 站内展示名：优先昵称，未设置时回退登录用户名。 */
    public String displayName() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }
}
