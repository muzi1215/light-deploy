package com.lx.deploy.project.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 项目环境部署目标请求对象。
 *
 * <p>Controller 的查询、新增和修改请求统一使用该 DTO，不直接接收
 * ProjectEnvironmentTarget 数据库实体。主键和审计字段由服务端维护。</p>
 *
 * <p><b>字段维护边界：</b>projectId/environmentId/serverId 新增必填，
 * Service 校验三方存在性且服务器须启用；isPrimary/deployOrder/status
 * 选填，未传时由 Service 填充默认值；runtimeConfigJson 选填但必须为合法 JSON。
 * 同一项目环境同一服务器重复绑定返回 DATA_CONFLICT。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Data
@Schema(description = "项目环境部署目标请求参数")
public class ProjectEnvironmentTargetDto {

    /**
     * 项目 ID。
     *
     * <p>新增必填，Service 校验项目真实存在；查询时等值过滤（主查询入口）。</p>
     */
    @Schema(description = "项目ID，新增必填")
    private String projectId;

    /**
     * 环境 ID。
     *
     * <p>新增必填，Service 校验环境真实存在；查询时等值过滤。</p>
     */
    @Schema(description = "环境ID，新增必填")
    private String environmentId;

    /**
     * 目标服务器 ID。
     *
     * <p>新增必填，Service 校验服务器存在且启用（status=1），
     * 停用服务器返回 DATA_CONFLICT；查询时等值过滤。</p>
     */
    @Schema(description = "目标服务器ID，新增必填，须为启用状态")
    private String serverId;

    /**
     * 是否为主目标。
     *
     * <p>选填，0 普通 1 主目标，默认 0；设为 1 时 Service 在同一事务内
     * 自动取消同项目环境的旧主目标；查询时等值过滤。</p>
     */
    @Schema(description = "是否为主目标：0普通，1主目标，默认0")
    private Byte isPrimary;

    /**
     * 多目标发布顺序。
     *
     * <p>选填，最小 1，默认 1；发布时按此顺序执行。</p>
     */
    @Schema(description = "多目标发布顺序，最小1，默认1")
    private Integer deployOrder;

    /**
     * 目标差异化运行配置 JSON。
     *
     * <p>选填，必须为合法 JSON；覆盖项目默认运行配置；不参与查询条件。</p>
     */
    @Schema(description = "目标差异化运行配置JSON，必须为合法JSON")
    private String runtimeConfigJson;

    /**
     * 目标状态，数字编码。
     *
     * <p>选填，取值 0 停用 / 1 启用，未传时 Service 填充默认值 1；
     * 查询时等值过滤。传入其他数字返回参数校验失败。</p>
     */
    @Schema(description = "状态：0停用，1启用，默认1")
    private Integer status;

}
