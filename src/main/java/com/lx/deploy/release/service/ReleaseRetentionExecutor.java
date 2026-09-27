package com.lx.deploy.release.service;

import com.lx.deploy.project.model.Project;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布版本保留策略执行器：把「算清单 → 删磁盘 → 置废弃」三步做成一条可复用链路。
 *
 * <p>{@link ReleaseRetentionService} 只做数据决策（该回收哪些版本），
 * 删磁盘属于远程执行。原先这一步只写在构建任务处理器内部，导致
 * <b>产物上传链路登记的版本永远不会被回收</b>——上传不走构建任务，
 * 磁盘上的历史产物只增不减，时间一长就会攒满构建机的产物目录。</p>
 *
 * <p>本执行器把这段逻辑抽出来，构建收尾与产物上传两个入口共用同一份实现：
 * 「删哪些、能不能删、删完怎么落记录」的判断必须在全局只有一处，
 * 否则一边漏掉安全阀会比没有回收更危险。</p>
 *
 * <p><b>回收永远不是主流程成功的必要条件</b>：任何一步失败都只汇总成一条消息返回，
 * 不向调用方抛异常。把「磁盘清理不顺」报成「构建/上传失败」是典型的误报，
 * 使用者会以为版本没登记成功而去重来一遍，反而多出垃圾。</p>
 *
 * <p>调用方可以传入一个已认证的会话（构建链路本来就有构建机会话）复用，
 * 避免为一个删除动作重新握手；产自其它机器的版本按需单开会话，用完即关。</p>
 *
 * @author lx
 * @since 2026-09-27 20:20:00
 */
@Slf4j
@Service
public class ReleaseRetentionExecutor {

    /** 版本保留策略：给出待回收清单并把产物落定为「已废弃」。 */
    private final ReleaseRetentionService retentionService;

    /** SSH 会话工厂：连接历史版本所在的构建机。 */
    private final SshSessionFactory sshSessionFactory;

    /** 服务器 Mapper：按主键取构建机档案。 */
    private final ServerMapper serverMapper;

    /**
     * 创建保留策略执行器。
     *
     * @param retentionService  版本保留策略
     * @param sshSessionFactory SSH 会话工厂
     * @param serverMapper      服务器 Mapper
     */
    public ReleaseRetentionExecutor(ReleaseRetentionService retentionService,
                                    SshSessionFactory sshSessionFactory,
                                    ServerMapper serverMapper) {
        this.retentionService = retentionService;
        this.sshSessionFactory = sshSessionFactory;
        this.serverMapper = serverMapper;
    }

    /**
     * 执行一次版本回收。
     *
     * <p>顺序固定为「先删磁盘产物，再置记录废弃」：反过来的话记录已不可见、磁盘却还占着，
     * 运维只能上机手工找目录。</p>
     *
     * @param project         项目，提供编码与保留版本数
     * @param preferredServer 调用方已连接的构建机，可为空
     * @param preferredSession 与 preferredServer 对应的已认证会话，可为空；生命周期由调用方管理
     * @return 回收结果报告（含逐条说明）；无需回收时 {@code recycledCount} 为 0
     */
    public RetentionReport execute(Project project, Server preferredServer, SshSession preferredSession) {
        List<ReleaseRetentionService.RecycleItem> items;
        try {
            items = retentionService.plan(project);
        } catch (Exception e) {
            // 算不出清单也要说清楚原因：否则使用者只会看到「没回收」，分不清是策略没配还是出错。
            String message = "版本保留策略计算失败（不影响本次结果）：" + e.getMessage();
            log.error("计算版本保留回收计划失败，projectId={}", project.getId(), e);
            return new RetentionReport(0, List.of(message));
        }
        if (items.isEmpty()) {
            return new RetentionReport(0, List.of());
        }

        List<String> messages = new ArrayList<>();
        List<String> recycled = new ArrayList<>();
        // 历史版本可能产自另一台构建机，按需开会话；调用方的会话由外层管理，不在这里关闭。
        Map<String, SshSession> opened = new HashMap<>();
        try {
            for (ReleaseRetentionService.RecycleItem item : items) {
                if (!StringUtils.hasText(item.dir())) {
                    // 目录无法确定（构建机缺失 / 未配产物目录 / 版本号不安全）：只废弃记录，磁盘不猜路径删。
                    recycled.add(item.releaseId());
                    messages.add("版本 " + item.version() + " 无法确定产物目录，仅标记为已废弃");
                    continue;
                }
                SshSession session = resolveSession(item.serverId(), preferredServer, preferredSession, opened);
                if (session == null) {
                    messages.add("回收版本产物失败（保留记录）：" + item.version() + " - 无法连接产物所在构建机");
                    continue;
                }
                SshCommandResult result = session.exec("rm -rf " + shellQuote(item.dir()));
                if (result.success()) {
                    recycled.add(item.releaseId());
                    messages.add("已回收超额版本产物：" + item.version() + "（" + item.dir() + "）");
                } else {
                    messages.add("回收版本产物失败（保留记录）：" + item.version()
                            + " - " + firstLine(result.stderr()));
                }
            }
        } catch (Exception e) {
            log.error("回收超额版本产物异常，projectId={}", project.getId(), e);
            messages.add("版本回收异常（不影响本次结果）：" + e.getMessage());
        } finally {
            for (SshSession extra : opened.values()) {
                try {
                    extra.close();
                } catch (Exception ignore) {
                    // 关闭失败不影响结论，只留一条告警日志。
                    log.warn("关闭回收用 SSH 会话失败");
                }
            }
        }
        if (recycled.isEmpty()) {
            return new RetentionReport(items.size(), messages);
        }
        int updated = retentionService.markDeprecated(recycled);
        messages.add("已按保留版本数 " + project.getRetainReleaseCount() + " 回收 " + updated + " 个历史版本");
        return new RetentionReport(updated, messages);
    }

    /**
     * 取回收目标所在的 SSH 会话：调用方已连接的机器直接复用，其它机器按需新开并缓存。
     *
     * @param serverId   产物所在构建机主键
     * @param preferred  调用方已连接的构建机，允许为空
     * @param session    调用方的已认证会话，允许为空
     * @param opened     已额外打开的会话缓存
     * @return 可用会话；连接不上时返回 null
     */
    private SshSession resolveSession(String serverId, Server preferred, SshSession session,
                                      Map<String, SshSession> opened) {
        if (!StringUtils.hasText(serverId)) {
            return null;
        }
        if (preferred != null && serverId.equals(preferred.getId()) && session != null) {
            return session;
        }
        SshSession cached = opened.get(serverId);
        if (cached != null) {
            return cached;
        }
        Server server = serverMapper.selectById(serverId);
        if (server == null) {
            return null;
        }
        try {
            SshSession extra = sshSessionFactory.open(server);
            opened.put(serverId, extra);
            return extra;
        } catch (Exception e) {
            log.warn("连接产物所在构建机失败，serverId={}", serverId, e);
            return null;
        }
    }

    /**
     * 把参数转成 shell 单引号字面量，避免空格与特殊字符破坏删除命令。
     *
     * @param value 原始值
     * @return 可直接拼进 shell 命令的引号包裹值
     */
    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 取命令输出第一行（用于错误摘要）。
     *
     * @param text 原始输出
     * @return 第一行；为空时返回默认文案
     */
    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String trimmed = text.strip();
        int index = trimmed.indexOf('\n');
        return index < 0 ? trimmed : trimmed.substring(0, index);
    }

    /**
     * 一次回收的结果报告。
     *
     * @param count    实际被标记为已废弃的版本数（无回收时为 0）
     * @param messages 逐条回收说明，供调用方写入任务日志或平台日志
     */
    public record RetentionReport(int count, List<String> messages) {
    }
}
