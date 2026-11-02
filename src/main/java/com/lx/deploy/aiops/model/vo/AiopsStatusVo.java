package com.lx.deploy.aiops.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台级 AIops 联动状态（首页实时状态卡数据源）。
 *
 * <p>与「一次发布的联动全景」{@link AiopsLinkVo} 不同，这是<b>平台级</b>只读探针：
 * 不绑定任何部署，只回答「总闸开没开、对端活没活」。</p>
 *
 * <p>约定：{@code reachable} 仅在 {@code enabled=true} 时有意义；总闸关闭时恒为 false，
 * 因为平台根本不会去连 AIops。前端据此区分三种状态：未启用 / 正常 / 后端不可达。</p>
 *
 * @author lx
 * @since 2026-11-03
 */
@Data
@Schema(description = "AIops平台联动状态")
public class AiopsStatusVo {

    /** 平台 AIops 联动总闸是否开启（light-deploy.aiops.enabled）。 */
    @Schema(description = "平台AIops联动总闸是否开启")
    private boolean enabled;

    /** AIops 后端当前是否可达（/openapi/v1/health 返回 UP）。总闸关闭时恒为 false。 */
    @Schema(description = "AIops后端当前是否可达")
    private boolean reachable;

    /** 对端服务地址，便于排障。 */
    @Schema(description = "AIops对端服务地址")
    private String baseUrl;

    /** 本次探测时刻。 */
    @Schema(description = "探测时刻")
    private LocalDateTime checkedAt;
}
