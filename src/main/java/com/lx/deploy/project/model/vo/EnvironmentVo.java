package com.lx.deploy.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部署环境响应对象。
 *
 * <p>VO 只用于 Controller 出参，由 EnvironmentService 从数据库实体转换而来。
 * 敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * <p>前端消费方式：deployStrategy/aiopsEnabled 渲染为标签/开关，
 * defaultServerId 需前端通过服务器列表接口做 ID→名称映射展示。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "部署环境响应数据")
public class EnvironmentVo {

    /**
     * 数据主键。
     *
     * <p>由服务端通过 IdGenerator 生成 32 位 UUID，调用方只读、不能通过请求体修改；
     * 前端用于行标识以及详情、编辑、删除接口的路径参数。</p>
     */
    @Schema(description = "主键ID")
    private String id;

    /**
     * 创建时间。
     *
     * <p>由 MyBatis-Plus 在插入时自动填充，服务端维护，调用方只读；列表默认按该字段倒序。</p>
     */
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>由 MyBatis-Plus 在插入和更新时自动填充，服务端维护，调用方只读。</p>
     */
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    /**
     * 环境编码。
     *
     * <p>业务唯一标识（如 dev/test/prod），前端列表展示与搜索使用。</p>
     */
    @Schema(description = "环境编码")
    private String code;

    /**
     * 环境名称。
     *
     * <p>面向页面的显示名称。</p>
     */
    @Schema(description = "环境名称")
    private String name;

    /**
     * 部署策略。
     *
     * <p>取值 SINGLE_TARGET / ALL_AT_ONCE / ROLLING，与后端 DeployStrategy 枚举一致；
     * 前端渲染为策略标签。</p>
     */
    @Schema(description = "部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING")
    private String deployStrategy;

    /**
     * 滚动发布批次大小。
     *
     * <p>大于等于 1，仅 ROLLING 策略下有实际意义。</p>
     */
    @Schema(description = "滚动发布批次大小")
    private Integer batchSize;

    /**
     * 默认目标服务器 ID。
     *
     * <p>指向 server 表的外键引用；只存 ID 不存名称，
     * 前端需调用服务器列表接口做本地名称映射。</p>
     */
    @Schema(description = "默认目标服务器ID")
    private String defaultServerId;

    /**
     * 是否启用 AIops 联动。
     *
     * <p>0 关闭 1 开启，前端渲染为开关/标签。</p>
     */
    @Schema(description = "是否启用AIops联动：0关闭，1开启")
    private Byte aiopsEnabled;

    /**
     * 环境描述。
     *
     * <p>面向页面的用途说明。</p>
     */
    @Schema(description = "环境描述")
    private String description;

}
