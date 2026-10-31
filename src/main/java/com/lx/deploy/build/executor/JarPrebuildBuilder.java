package com.lx.deploy.build.executor;

import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.task.model.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * JAR 预构建（B1）模式的 DOCKER 构建服务：先由平台用 Maven 打出 jar，再用极简 Dockerfile 包成镜像推送。
 *
 * <p>与 {@link DockerBuilder}（PROJECT_DOCKERFILE / LAYERED_DOCKER 由项目 Dockerfile 在 docker build 内完成编译）
 * 的区别：本模式把「编译出 jar」这一步从镜像构建里剥离，用 Maven 构建镜像在构建机跑（挂载持久化 ~/.m2 缓存），
 * 先在宿主工作区产出 jar，再用项目提供的极简 Dockerfile（FROM jre + COPY jar + COPY yml）打最终镜像。</p>
 *
 * <p>收益：① 增量构建复用依赖缓存，不必每次全量下载；② jar 成为可见的一等产物，可单独审计/分发；
 * ③ 最终镜像与多阶段 Dockerfile 体积持平（均只含 jre），但「编译」与「打包镜像」职责清晰分离。
 * 代价：多一次 Maven 构建镜像的拉取/启动开销。</p>
 *
 * <p>与 DockerBuilder 一样，所有共用零件都委托给 {@link BuildSupport}。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Component
public class JarPrebuildBuilder {

    /** 构建支撑组件：jar 定位、docker 命令、日志与进度等共用零件。 */
    private final BuildSupport support;

    /**
     * 创建 JAR 预构建服务。
     *
     * @param support 构建支撑组件
     */
    public JarPrebuildBuilder(BuildSupport support) {
        this.support = support;
    }

    /**
     * 执行 B1（JAR 预构建）模式的 DOCKER 构建：先由平台用 Maven 打出 jar，再用极简 Dockerfile 包成镜像推送。
     *
     * <p>极简 Dockerfile 一律由项目仓库提供（build_config_json.dockerfile_path 指向），平台不生成模板——
     * 与「Dockerfile 由项目自带」的硬约束一致。</p>
     *
     * @param ctx 构建上下文（含 task / session / project / config / runtimeType / workspacePath / commit / version / artifacts）
     * @return 项目自带的 docker-compose.yml 原文；未使用返回 null
     * @throws DeployException 构建/推送失败或必要配置缺失时抛出
     */
    public String build(BuildContext ctx) {
        Task task = ctx.task();
        SshSession session = ctx.session();
        Project project = ctx.project();
        BuildConfig config = ctx.config();
        RuntimeType runtimeType = ctx.runtimeType();
        String workspacePath = ctx.workspacePath();
        String commit = ctx.commit();
        String releaseVersion = ctx.version();
        List<BuildArtifact> artifacts = ctx.artifacts();
        if (!support.reportProgress(task, BuildSupport.STAGE_BUILD, BuildSupport.PROGRESS_BUILDING)) {
            return null;
        }
        String imageRepo = support.resolveImageRepository(project, config);
        String tag = releaseVersion;
        String fullImage = imageRepo + ":" + tag;
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "构建镜像 " + fullImage + "（模式 JAR_PREBUILD：先出 jar 再极简镜像）");

        // 1) Maven 构建出 jar：用 Maven 构建镜像在构建机跑，避免要求构建机裸装 maven/JDK（与现有 Dockerfile 的 builder 阶段同源）。
        //    挂载持久化 ~/.m2 缓存目录，增量构建不必每次全量下载依赖；挂载整个工作区为 /workspace 并设为工作目录。
        //    timeout 包裹：超时时杀掉 docker run，--rm 保证容器被一并清理，不留僵尸。
        String moduleDir = support.resolveModuleDir(config);
        String mavenSettings = StringUtils.hasText(config.getMavenSettingsPath())
                ? " -s /workspace/" + config.getMavenSettingsPath().strip() : "";
        String mavenModule = StringUtils.hasText(moduleDir) ? " -f " + moduleDir + "/pom.xml" : "";
        int timeoutSeconds = support.resolveBuildTimeout(config, session);
        String mavenCmd = "timeout -k 5 " + timeoutSeconds + "s docker run --rm"
                + " -v " + support.shellQuote(workspacePath) + ":/workspace -w /workspace"
                + " -v " + support.shellQuote(BuildSupport.M2_CACHE_HOST_DIR) + ":/root/.m2"
                + " " + BuildSupport.MAVEN_BUILDER_IMAGE
                + " mvn -B" + mavenSettings + mavenModule + " package -DskipTests";
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "Maven 构建 jar（输出实时回传）：" + mavenCmd);
        SshCommandResult mavenResult;
        try {
            mavenResult = session.exec(mavenCmd, timeoutSeconds + 60, support.streamingLogConsumer(task, BuildSupport.STAGE_BUILD));
        } catch (com.lx.deploy.ssh.SshCommandTimeoutException e) {
            support.writeTimeoutOutput(task, BuildSupport.STAGE_BUILD, e.getPartialResult());
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "Maven 构建 jar 超时（" + e.getTimeoutSeconds()
                            + " 秒，可在 build_config_json.command_timeout_seconds 放宽上限）");
        }
        if (!mavenResult.success()) {
            String hint = support.tailLines(StringUtils.hasText(mavenResult.stderr()) ? mavenResult.stderr() : mavenResult.stdout(), BuildSupport.MAX_ERROR_HINT_LINES);
            support.writeError(task, BuildSupport.STAGE_BUILD, StringUtils.hasText(mavenResult.stderr()) ? mavenResult.stderr() : mavenResult.stdout(), BuildSupport.MAX_BUILD_OUTPUT_LINES);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "Maven 构建 jar 失败：" + hint);
        }
        support.fillTailIfTruncated(task, BuildSupport.STAGE_BUILD, mavenResult);

        // 2) 定位 jar 产物：相对仓库根优先，找不到再按模块目录回落。
        if (!StringUtils.hasText(config.getArtifactPath())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "JAR_PREBUILD 模式必须在 build_config_json.artifact_path 给出 jar 相对路径");
        }
        String jarFile = support.locateJarArtifact(session, workspacePath, config);
        if (!StringUtils.hasText(jarFile)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "未找到 jar 产物，请检查 artifact_path：" + config.getArtifactPath());
        }
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "已产出 jar：" + jarFile);

        // 3) 极简镜像：用项目提供的 Dockerfile（FROM jre + COPY jar + COPY yml），构建上下文为工作区根。
        if (!StringUtils.hasText(config.getDockerfilePath())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "JAR_PREBUILD 模式需要 build_config_json.dockerfile_path（指向项目提供的极简 Dockerfile）");
        }
        support.dockerBuild(task, session, workspacePath, "-f " + config.getDockerfilePath(), fullImage);
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "镜像构建完成");

        // 4) 启动级验证 + 推送（与 buildDocker 同闸门）。
        support.dockerRunVerify(task, session, fullImage, config);
        support.dockerPush(task, session, project, fullImage);
        String digest = support.dockerDigest(session, fullImage);
        artifacts.add(support.imageArtifact(ArtifactType.APP_IMAGE, fullImage, tag, digest));
        support.appendLog(task, BuildSupport.STAGE_PUSH, TaskLogLevel.INFO, "镜像已推送：" + fullImage
                + (StringUtils.hasText(digest) ? "，摘要 " + digest : ""));
        return support.readComposeFile(task, session, config, workspacePath);
    }
}
