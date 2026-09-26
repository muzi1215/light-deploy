package com.lx.deploy.release.service;

import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 发布版本产物存在性预检。
 *
 * <p>解决「版本下拉看得到、点了才发不了」：版号在库里就一直显示，但产物是构建机磁盘上的文件，
 * 可能被保留策略回收、可能随构建机更换而失联。发布任务要跑到「下载产物」阶段才会失败，
 * 那时已经建好了部署记录与目标快照，页面看起来像一次失败的发布，实际是选错了版本。</p>
 *
 * <p>因此在<b>创建部署之前</b>先探一次：连到版本记录的构建机，确认 `artifact_path` 还在。
 * 不存在就直接拒绝，把原因说清楚，让运维去重新构建，而不是等任务跑一半。</p>
 *
 * <p>只校验落盘产物（JAR / STATIC）。Docker 版本的产物是镜像仓库里的 tag，
 * 目标机 `docker pull` 时才真正判定，平台侧不做预检——探了也不代表目标机拉得到。</p>
 *
 * @author lx
 * @since 2026-09-26
 */
@Component
public class ArtifactProbe {

    /** 服务器 Mapper，用于取版本记录的构建机。 */
    private final ServerMapper serverMapper;

    /** SSH 会话工厂。 */
    private final SshSessionFactory sshSessionFactory;

    /**
     * 注入预检所需依赖。
     *
     * @param serverMapper      服务器 Mapper
     * @param sshSessionFactory SSH 会话工厂
     */
    public ArtifactProbe(ServerMapper serverMapper, SshSessionFactory sshSessionFactory) {
        this.serverMapper = serverMapper;
        this.sshSessionFactory = sshSessionFactory;
    }

    /**
     * 确认版本的落盘产物仍然可用；不可用直接抛业务异常。
     *
     * @param release 待发布版本
     * @throws DeployException 未记录构建机、构建机不存在、产物不在或连不上构建机时抛出
     */
    public void requireExists(ReleaseRecord release) {
        // 无落盘产物的运行时（DOCKER 走镜像 tag）不预检。
        if (!StringUtils.hasText(release.getArtifactPath())) {
            return;
        }
        String version = release.getVersion();
        if (!StringUtils.hasText(release.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + version + " 未记录构建机（多为历史版本），请重新构建后再发布");
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND,
                    "版本 " + version + " 记录的构建机不存在，无法定位产物，请重新构建后再发布");
        }
        String path = release.getArtifactPath();
        try (SshSession session = sshSessionFactory.open(buildServer)) {
            // test -e 对文件与目录都成立：JAR 是文件，STATIC 转存后可能是目录。
            SshCommandResult result = session.exec("test -e " + quote(path) + " && echo yes || echo no");
            if (!result.success() || !"yes".equals(result.stdout().strip())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "版本 " + version + " 的产物在构建机 " + buildServer.getServerName() + " 上已不存在（" + path
                                + "）：可能已被保留策略回收或构建机已更换，请重新构建后再发布");
            }
        } catch (DeployException e) {
            throw e;
        } catch (Exception e) {
            // 连不上构建机时不能放行：放行等于把「未知」当成「存在」，任务照样会失败，只是更晚。
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "无法连接版本 " + version + " 的构建机 " + buildServer.getServerName() + "，无法确认产物是否可用：" + e.getMessage());
        }
    }

    /**
     * 单引号包裹路径，避免路径里的空格或特殊字符被 shell 拆开。
     *
     * @param value 原始路径
     * @return 可安全拼进命令的形式
     */
    private String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
