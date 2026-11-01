package com.lx.deploy.aiops.model;

/**
 * {@code aiops_event.status} 取值。
 *
 * <p>与 AIops 侧表注释保持一致：0 待发送、1 成功、2 失败、3 已放弃。
 * 「已放弃」不是错误状态，而是重试耗尽或事件过期后的终态——继续重试只会
 * 在发布结束后推一条过期时序过去，反而污染对方分析。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
public final class AiopsEventStatus {

    /** 待发送：已落库、尚未成功送达。 */
    public static final int PENDING = 0;

    /** 成功：对端返回业务成功。 */
    public static final int SUCCESS = 1;

    /** 失败：本次投递失败，等待重试。 */
    public static final int FAILED = 2;

    /** 已放弃：重试耗尽或事件过期，不再投递。 */
    public static final int ABANDONED = 3;

    private AiopsEventStatus() {
    }
}
