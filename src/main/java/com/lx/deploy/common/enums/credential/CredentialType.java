package com.lx.deploy.common.enums.credential;

import lombok.Getter;

import java.util.Arrays;

/**
 * 加密凭据类型枚举。
 *
 * <p>数据库以字符串保存稳定编码，决定 ciphertext（加密内容）里到底装的是什么：
 * Git 访问令牌、Git 私钥或镜像仓库登录密码。凭据本身不区分环境，同一份凭据可以被多个
 * 项目或仓库引用。</p>
 *
 * <p>服务器 SSH 凭据**不**走本表：server 表直接保存自己的密文字段（AGENTS.md §6、§7），
 * 本表只服务于 Git 拉取和镜像仓库推送这两类外部系统凭据。</p>
 *
 * <p>对应数据库字段 credential.type。</p>
 *
 * @author lx
 * @since 2026-09-23 02:20:00
 */
@Getter
public enum CredentialType {

    /** Git 访问令牌：HTTPS 形式仓库地址（https://gitee.com/...）的用户名 + 令牌。 */
    GIT_TOKEN("GIT_TOKEN", "Git访问令牌"),

    /** Git 私钥：SSH 形式仓库地址（git@gitee.com:...）使用的部署私钥。 */
    SSH_KEY("SSH_KEY", "Git私钥"),

    /** 镜像仓库凭据：登录私有 registry 的用户名 + 密码。 */
    REGISTRY("REGISTRY", "镜像仓库凭据"),

    /**
     * SSH 密码：保留类型，当前**没有消费方**。
     *
     * <p>服务器 SSH 密码保存在 server 表自己的密文字段里（AGENTS.md §7），
     * 保留该编码是为了兼容既有 DDL 注释，新增凭据时不要选择它。</p>
     */
    SSH_PASSWORD("SSH_PASSWORD", "SSH密码（保留，暂无消费方）");

    private final String code;
    private final String message;

    /**
     * 初始化凭据类型。
     *
     * @param code 数据库存储的类型编码
     * @param message 面向页面和日志的中文说明
     */
    CredentialType(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据数据库编码查找凭据类型。
     *
     * @param code 数据库或请求传入的类型编码，允许为 null
     * @return 对应凭据类型；编码为空或不受支持时返回 null
     */
    public static CredentialType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断该类型是否可以用于 Git 仓库拉取。
     *
     * <p>项目模块校验 project.git_credential_id 时调用：HTTPS 仓库用访问令牌，
     * SSH 仓库用私钥，其余类型（镜像仓库凭据）不允许绑定到项目。</p>
     *
     * @return true 表示可用于 Git 拉取
     */
    public boolean isGitType() {
        return this == GIT_TOKEN || this == SSH_KEY;
    }
}
