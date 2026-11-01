package com.lx.deploy.user.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 后台登录请求。
 *
 * <p>仅包含账号与密码；验证码等增强校验留待接入统一权限中心时补充。</p>
 */
@Data
@Schema(description = "后台登录请求")
public class LoginRequest {

    /** 登录用户名。 */
    @NotBlank(message = "用户名不能为空")
    @Schema(description = "登录用户名")
    private String username;

    /** 登录密码（明文，仅用于本次校验，不落库、不回显）。 */
    @NotBlank(message = "密码不能为空")
    @Schema(description = "登录密码")
    private String password;
}
