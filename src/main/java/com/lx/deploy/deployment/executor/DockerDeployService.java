package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * DOCKER 运行时发布服务：拉取镜像并以新容器启动。
 *
 * <p>只负责「把某个版本的镜像在某一台机器上跑起来」这一件事：
 * 镜像已在构建阶段推送到仓库，本服务不参与构建，也不接触产物文件。
 * 目标级的状态回调、健康检查与批次编排由 {@code DeployTaskHandler} 负责。</p>
 *
 * <p>发布动作固定为三步：{@code docker pull} → 删除同名旧容器 → {@code docker run -d}。
 * 删除同名容器不是可选项：容器名是发布幂等的关键，不删会让 run 直接失败。
 * 启动后必须再确认一次运行态——{@code docker run} 成功只代表容器被创建，
 * 「启动即退出」的应用会被误报成发布成功。</p>
 *
 * <p>回滚与本服务无关的特殊性：镜像是不可变制品，回滚就是拉另一个 tag 重跑一遍同样流程，
 * 不需要在目标机上保留任何历史副本，因此本服务比 JAR / STATIC 少一整套备份逻辑。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Service
public class DockerDeployService {

    /** 部署支撑组件：任务日志与通用工具。 */
    private final DeploySupport support;

    /** SSH 会话工厂：建立到目标机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /**
     * 创建 DOCKER 发布服务。
     *
     * @param support           部署支撑组件
     * @param sshSessionFactory SSH 会话工厂
     */
    public DockerDeployService(DeploySupport support, SshSessionFactory sshSessionFactory) {
        this.support = support;
        this.sshSessionFactory = sshSessionFactory;
    }

    /**
     * 在目标机上拉取指定版本镜像并启动容器。
     *
     * @param ctx 目标级发布上下文，使用其中的 server / config / release / task
     * @throws DeployException 镜像标签缺失、拉取失败、启动失败或启动后未处于运行态时抛出
     */
    public void deploy(DeployContext ctx) {
        String image = ctx.release().getImageTag();
        if (!StringUtils.hasText(image)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 没有镜像标签，无法按 DOCKER 运行时发布");
        }
        String containerName = StringUtils.hasText(ctx.config().getContainerName())
                ? ctx.config().getContainerName() : ctx.project().getCode();
        try (SshSession session = sshSessionFactory.open(ctx.server())) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO, "拉取镜像 " + image);
            // 拉取镜像可能上千 MB，按长命令超时等待，不能用秒级超时。
            SshCommandResult pull = session.execLong("docker pull " + image);
            if (!pull.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker pull 失败：" + support.firstLine(pull.stderr()));
            }
            support.writeOutput(ctx.task(), DeploySupport.STAGE_DELIVER, pull.stdout());
            // 先移除同名旧容器：容器名是发布幂等的关键，不删除会导致 run 直接失败。
            session.exec("docker rm -f " + containerName + " >/dev/null 2>&1 || true");
            String runCommand = "docker run -d --restart=always --name " + containerName
                    + portOptions(ctx.config()) + extraArgs(ctx.config()) + " " + image;
            support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO, "启动容器 " + containerName);
            // 容器启动可能包含镜像解压与首次初始化（如 Spring Boot 解包），按长命令超时等待。
            SshCommandResult run = session.execLong(runCommand);
            if (!run.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker run 失败：" + support.firstLine(run.stderr()));
            }
            // 容器启动成功不代表进程还在：立刻确认一次运行状态，避免把「启动即退出」报成成功。
            SshCommandResult inspect = session.exec(
                    "docker inspect -f '{{.State.Running}}' " + containerName + " 2>/dev/null");
            if (!inspect.success() || !"true".equalsIgnoreCase(inspect.stdout().strip())) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "容器启动后未处于运行态，请检查应用启动日志（docker logs " + containerName + "）");
            }
        }
    }

    /**
     * 拼装 docker run 的端口映射参数。
     *
     * @param config 运行配置
     * @return 形如 {@code " -p 8080:8080 -p 9090:9090"} 的参数片段；未配置时返回空串
     */
    private String portOptions(DeployConfig config) {
        if (!StringUtils.hasText(config.getPortMappings())) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String mapping : config.getPortMappings().split(",")) {
            String trimmed = mapping.trim();
            if (!trimmed.isEmpty()) {
                builder.append(" -p ").append(trimmed);
            }
        }
        return builder.toString();
    }

    /**
     * 拼装附加 docker run 参数。
     *
     * @param config 运行配置
     * @return 附加参数片段；未配置时返回空串
     */
    private String extraArgs(DeployConfig config) {
        return StringUtils.hasText(config.getExtraDockerArgs()) ? " " + config.getExtraDockerArgs().trim() : "";
    }
}
