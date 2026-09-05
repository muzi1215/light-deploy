package com.lx.deploy.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目环境部署目标响应对象。
 *
 * <p>VO 只用于 Controller 出参，由 ProjectEnvironmentTargetService 从数据库实体转换而来。
 * 敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * <p>前端消费方式：projectId/environmentId/serverId 只存 ID，
 * 页面通过各自列表接口做本地名称映射后展示；
 * isPrimary 渲染为主目标标记，列表按 deployOrder 升序展示发布顺序。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "项目环境部署目标响应数据")
public class ProjectEnvironmentTargetVo {

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
     * 项目 ID。
     *
     * <p>指向 project 表的外键引用，前端做名称映射展示。</p>
     */
    @Schema(description = "项目ID")
    private String projectId;

    /**
     * 环境 ID。
     *
     * <p>指向 environment 表的外键引用，前端做名称映射展示。</p>
     */
    @Schema(description = "环境ID")
    private String environmentId;

    /**
     * 目标服务器 ID。
     *
     * <p>指向 server 表的外键引用，前端做名称映射展示。</p>
     */
    @Schema(description = "目标服务器ID")
    private String serverId;

    /**
     * 是否为主目标。
     *
     * <p>0 普通 1 主目标；同项目环境内最多一个主目标，前端渲染为标记。</p>
     */
    @Schema(description = "是否为主目标：0普通，1主目标")
    private Byte isPrimary;

    /**
     * 多目标发布顺序。
     *
     * <p>大于等于 1，发布执行顺序，列表默认按该字段升序。</p>
     */
    @Schema(description = "多目标发布顺序")
    private Integer deployOrder;

    /**
     * 目标差异化运行配置 JSON。
     *
     * <p>字符串形式的 JSON 配置，覆盖项目默认运行配置。</p>
     */
    @Schema(description = "目标差异化运行配置JSON")
    private String runtimeConfigJson;

    /**
     * 目标状态。
     *
     * <p>取值 ENABLED / DISABLED，与后端 EnableStatus 枚举一致。</p>
     */
    @Schema(description = "状态：ENABLED/DISABLED")
    private String status;

}
