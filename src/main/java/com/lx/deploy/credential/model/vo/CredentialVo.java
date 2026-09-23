package com.lx.deploy.credential.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 加密凭据响应对象。
 *
 * <p>VO 只用于 Controller 出参。本类**不声明**密文字段（credential.encrypted_value），
 * 从类型结构上保证任何查询接口都不可能把密文返回给调用方；前端编辑凭据时留空表示不修改。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@Data
@Schema(description = "加密凭据响应数据")
public class CredentialVo {

    /** 数据主键，32 位 UUID 字符串；项目、镜像仓库通过该值引用凭据。 */
    private String id;

    /** 创建时间，服务端维护，按创建时间倒序展示。 */
    private LocalDateTime createTime;

    /** 更新时间，服务端维护，每次修改（含更换凭据明文）都会前进。 */
    private LocalDateTime updateTime;

    /** 凭据名称。 */
    @Schema(description = "凭据名称")
    private String name;

    /** 凭据类型编码，取值见后端 {@code CredentialType} 枚举；页面按该值映射中文与标签颜色。 */
    @Schema(description = "凭据类型：SSH_PASSWORD/SSH_KEY/GIT_TOKEN/REGISTRY")
    private String type;

    /** 非敏感凭据元数据（JSON 文本），未填写时为 null。 */
    @Schema(description = "非敏感凭据元数据")
    private String metadataJson;
}
