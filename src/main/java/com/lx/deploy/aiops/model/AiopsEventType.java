package com.lx.deploy.aiops.model;

/**
 * 发布生命周期事件类型。
 *
 * <p>与 AIops 侧 {@code ops_deploy_event.event_type} 一一对应。相比设计稿补了四类：
 * {@code SILENCE_CLOSED}（静默关闭留痕）、{@code ROLLBACK_SUCCEEDED} / {@code ROLLBACK_FAILED}
 * （回滚成功与失败必须分开——失败要恢复真实告警并升级等级）、{@code CANCELLED}（人工取消）。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
public enum AiopsEventType {

    /** 静默已建、即将下发。 */
    PREPARED("DEPLOYMENT_PREPARED", "静默已建"),

    /** 首个目标开始执行。 */
    STARTED("DEPLOYMENT_STARTED", "开始发布"),

    /** 单台目标发布成功。 */
    HOST_SUCCESS("DEPLOYMENT_HOST_SUCCESS", "单机成功"),

    /** 单台目标发布失败。 */
    HOST_FAILED("DEPLOYMENT_HOST_FAILED", "单机失败"),

    /** 整体发布成功。 */
    SUCCEEDED("DEPLOYMENT_SUCCEEDED", "发布成功"),

    /** 整体发布失败。 */
    FAILED("DEPLOYMENT_FAILED", "发布失败"),

    /** 静默窗已关闭。 */
    SILENCE_CLOSED("DEPLOYMENT_SILENCE_CLOSED", "静默已关"),

    /** 回滚完成且健康检查通过。 */
    ROLLBACK_SUCCEEDED("DEPLOYMENT_ROLLBACK_SUCCEEDED", "回滚成功"),

    /** 回滚后仍不健康，必须恢复真实告警并升级。 */
    ROLLBACK_FAILED("DEPLOYMENT_ROLLBACK_FAILED", "回滚失败"),

    /** 人工取消。 */
    CANCELLED("DEPLOYMENT_CANCELLED", "人工取消");

    private final String code;

    private final String label;

    AiopsEventType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 事件类型编码，直接作为报文中的 eventType。 */
    public String getCode() {
        return code;
    }

    /**
     * 事件类型中文短名，用于页面展示。
     *
     * <p>放在枚举里而不是前端另写一份映射：事件类型是对外契约的一部分（对端按 code 落库），
     * 两处维护必然漂移。前端只负责渲染服务端给的 label。</p>
     */
    public String getLabel() {
        return label;
    }

    /**
     * 按编码反查枚举。
     *
     * @param code 事件类型编码，允许为空
     * @return 对应枚举；编码为空或未知时返回 null（历史数据可能有已废弃的编码）
     */
    public static AiopsEventType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AiopsEventType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }
}
