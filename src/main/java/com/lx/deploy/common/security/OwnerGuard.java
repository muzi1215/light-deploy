package com.lx.deploy.common.security;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * 项目负责人校验。
 *
 * <p>平台当前阶段的核心发版约束：项目一旦指派了负责人，只有该负责人能对其发起构建、发布、回滚、取消。
 * 设计目标「防止项目被随便发版」。校验与具体业务服务解耦，仅接收负责人 ID / 名称 / 项目编码三个标量，
 * 避免 common 层反向依赖 project 领域实体。</p>
 *
 * <p>过渡期兜底：负责人为空（存量项目或尚未指派）时按放行处理，但打印 WARN 提示尽快指派，
 * 避免把历史项目全部锁死导致无法发版。接入统一权限中心后可收紧为「必须指派」。</p>
 */
public final class OwnerGuard {

    private static final Logger log = LoggerFactory.getLogger(OwnerGuard.class);

    private OwnerGuard() {
    }

    /**
     * 校验当前登录用户是否为项目负责人。
     *
     * @param ownerId 项目 owner_id（user 表 UUID），可能为空
     * @param ownerName 项目负责人名称，用于错误提示展示
     * @param projectCode 项目编码，用于日志定位
     * @throws DeployException owner_id 非空且当前用户非负责人时抛 {@code FORBIDDEN(4003)}；
     *                         未登录（理论上拦截器已拦）抛 {@code UNAUTHORIZED}
     */
    public static void assertOwner(String ownerId, String ownerName, String projectCode) {
        if (!StringUtils.hasText(ownerId)) {
            log.warn("项目 {} 尚未指派负责人，按过渡期策略放行构建/发布，请尽快在项目配置中指派 owner",
                    projectCode);
            return;
        }
        String currentUserId = CurrentUser.getUserId();
        if (currentUserId == null) {
            throw new DeployException(ResponseCode.UNAUTHORIZED, "请先登录");
        }
        if (!ownerId.equals(currentUserId)) {
            throw new DeployException(ResponseCode.FORBIDDEN,
                    "只有项目负责人「" + (StringUtils.hasText(ownerName) ? ownerName : ownerId)
                            + "」可以对该项目执行此操作");
        }
    }
}
