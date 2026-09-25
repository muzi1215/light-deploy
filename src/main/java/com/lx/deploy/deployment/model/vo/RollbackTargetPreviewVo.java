package com.lx.deploy.deployment.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 回滚预览中的单个目标明细。
 *
 * <p>只在「点回滚之后、真正提交之前」的预览接口里使用。currentVersion 与 targetVersion
 * 必须成对展示：只写「将回滚到 X」无法让使用者判断这次是升级还是回退、跨度有多大。</p>
 *
 * @author lx
 * @since 2026-09-25 20:00:00
 */
@Data
@Schema(description = "回滚预览目标明细")
public class RollbackTargetPreviewVo {

    /** 目标主键。 */
    @Schema(description = "目标主键")
    private String targetId;

    /** 目标服务器 ID。 */
    @Schema(description = "目标服务器ID")
    private String serverId;

    /** 目标服务器名称。 */
    @Schema(description = "目标服务器名称")
    private String serverName;

    /** 目标服务器地址。 */
    @Schema(description = "目标服务器地址")
    private String serverHost;

    /** 当前正在运行的版本，即本次部署发布上去的版本。 */
    @Schema(description = "当前运行版本")
    private String currentVersion;

    /** 本次回滚要退回去的版本。 */
    @Schema(description = "将回滚到的版本")
    private String targetVersion;

    /** 该目标是否可回滚；为 false 时保持不变。 */
    @Schema(description = "是否可回滚")
    private Boolean rollbackable;

    /** 不可回滚的原因，可回滚时为空。 */
    @Schema(description = "跳过回滚的原因")
    private String skipReason;
}
