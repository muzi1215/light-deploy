package com.lx.deploy.project.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 项目已绑定环境的下拉选项。
 *
 * <p>专门服务于「新建部署」这类需要选环境的场景：只返回该项目<b>已绑定启用目标</b>的环境，
 * 并带上可发布的目标台数，让选项直接回答「选了它能不能发、发几台」。
 * 区别于 {@link EnvironmentVo}（环境档案全量字段），本 VO 不含任何可修改配置字段。</p>
 *
 * <p>台数只统计启用（status=1）的绑定：停用的绑定不参与发布执行，
 * 与发布模块展开目标的过滤条件（{@code DeploymentService.createAndTrigger}）保持一致，
 * 否则会出现「下拉写着 2 台、提交后说没有启用目标」的矛盾。</p>
 *
 * @author lx
 * @since 2026-09-27 00:30:00
 */
@Data
@Schema(description = "项目已绑定环境的下拉选项")
public class EnvironmentOptionVo {

    /**
     * 环境主键，即 environment 表的 id。
     *
     * <p>服务端维护，作为下拉的 value 与新建部署的 environmentId。</p>
     */
    @Schema(description = "环境ID")
    private String id;

    /**
     * 环境编码，如 prod / test。
     */
    @Schema(description = "环境编码")
    private String code;

    /**
     * 环境名称，如「生产环境」。
     */
    @Schema(description = "环境名称")
    private String name;

    /**
     * 该项目在该环境下启用的部署目标台数。
     *
     * <p>取值大于等于 1（为 0 的环境不会出现在列表里），页面用于渲染「生产环境（2 台）」。</p>
     */
    @Schema(description = "启用目标台数，至少为1")
    private Integer targetCount;

    /**
     * 环境默认部署策略，取值见 {@code DeployStrategy}。
     *
     * <p>随选项一并返回，供新建部署弹窗展示「环境默认策略」作为临时覆盖的对照，省一次详情查询。</p>
     */
    @Schema(description = "环境默认部署策略：SINGLE_TARGET/ALL_AT_ONCE/ROLLING")
    private String deployStrategy;

    /**
     * 环境默认滚动批次大小，仅 ROLLING 策略有意义。
     */
    @Schema(description = "环境默认滚动批次大小")
    private Integer batchSize;
}
