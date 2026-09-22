package com.lx.deploy.user.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 过渡期本地用户。
 * 接入统一权限中心后，本实体仅保留兼容和迁移用途。
 */
@Data
@TableName("user")
@Schema(description = "临时用户")
public class User extends BaseEntity {

    @TableField("username")
    @Schema(description = "登录用户名")
    private String username;

    @TableField("password_hash")
    @Schema(description = "不可逆密码哈希", accessMode = Schema.AccessMode.WRITE_ONLY)
    @ToString.Exclude
    private String passwordHash;

    @TableField("nickname")
    @Schema(description = "用户昵称")
    private String nickname;

    @TableField("email")
    @Schema(description = "邮箱")
    private String email;

    @TableField("mobile")
    @Schema(description = "手机号")
    private String mobile;

    @TableField("avatar_url")
    @Schema(description = "头像地址")
    private String avatarUrl;

    @TableField("external_user_id")
    @Schema(description = "统一权限中心用户ID")
    private String externalUserId;

    /**
     * 用户状态，数字编码。
     *
     * <p>0 停用 / 1 启用 / 2 锁定；用户表为过渡期实现，接入统一权限中心后逐步停用。</p>
     */
    @TableField("status")
    @Schema(description = "状态：0停用，1启用，2锁定")
    private Integer status;

    @TableField("last_login_time")
    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginTime;
}
