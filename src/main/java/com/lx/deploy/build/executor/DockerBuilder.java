package com.lx.deploy.build.executor;

import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.task.model.Task;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * DOCKER 运行时构建服务：PROJECT_DOCKERFILE / LAYERED_DOCKER 两种模式下的镜像构建、推送与摘要登记。
 *
 * <p>只负责「把某个版本的镜像在构建机本地构建并推送到仓库」这一件事：镜像的 Dockerfile 一律由项目仓库提供，
 * 本服务不生成任何模板。推送成功后取镜像摘要登记产物，并读回项目自带的 docker-compose.yml 原文。</p>
 *
 * <p>所有与「构建什么无关」的零件（工作区/克隆/docker 命令/日志/进度/镜像仓库登录等）都委托给
 * {@link BuildSupport}，本服务只持有「DOCKER 镜像构建」这一专属编排逻辑。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Component
public class DockerBuilder {

    /** 构建支撑组件：docker 命令、镜像仓库登录、日志与进度等共用零件。 */
    private final BuildSupport support;

    /**
     * 创建 DOCKER 构建服务。
     *
     * @param support 构建支撑组件
     */
    public DockerBuilder(BuildSupport support) {
        this.support = support;
    }

    /**
     * 执行 DOCKER 运行时构建并推送镜像。
     *
     * <p>按构建模式分支：PROJECT_DOCKERFILE 用项目自带 Dockerfile；LAYERED_DOCKER 先推基础镜像再推应用镜像。
     * Dockerfile 一律来自项目仓库，平台不生成模板。推送成功后取镜像摘要登记产物。</p>
     *
     * @param ctx 构建上下文（含 task / session / project / config / runtimeType / buildMode / workspacePath / commit / version / artifacts）
     * @return 项目自带的 docker-compose.yml 原文；项目未使用该文件时返回 null
     * @throws DeployException 构建/推送失败，或 compose 文件不可用（显式配置却读不到、缺版本占位符）时抛出
     */
    public String build(BuildContext ctx) {
        Task task = ctx.task();
        SshSession session = ctx.session();
        Project project = ctx.project();
        BuildConfig config = ctx.config();
        RuntimeType runtimeType = ctx.runtimeType();
        BuildMode buildMode = ctx.buildMode();
        String workspacePath = ctx.workspacePath();
        String commit = ctx.commit();
        String releaseVersion = ctx.version();
        List<BuildArtifact> artifacts = ctx.artifacts();
        if (!support.reportProgress(task, BuildSupport.STAGE_BUILD, BuildSupport.PROGRESS_BUILDING)) {
            return null;
        }
        String imageRepo = support.resolveImageRepository(project, config);
        // 版本号由调用方一次性定稿：镜像 tag 必须与版本记录、产物目录用同一个值。
        String tag = releaseVersion;
        String fullImage = imageRepo + ":" + tag;
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO,
                "构建镜像 " + fullImage + "（模式 " + buildMode.getCode() + "）");

        String dockerfileOption;
        if (buildMode == BuildMode.PROJECT_DOCKERFILE) {
            if (!StringUtils.hasText(config.getDockerfilePath())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "PROJECT_DOCKERFILE 模式需要 build_config_json.dockerfile_path");
            }
            dockerfileOption = "-f " + config.getDockerfilePath();
        } else if (buildMode == BuildMode.LAYERED_DOCKER) {
            if (!StringUtils.hasText(config.getBaseDockerfilePath()) || !StringUtils.hasText(config.getDockerfilePath())) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR,
                        "LAYERED_DOCKER 模式需要 build_config_json.base_dockerfile_path 与 dockerfile_path");
            }
            // 先构建并推送基础镜像，应用镜像基于它构建。
            String baseImage = imageRepo + ":base-" + tag;
            support.dockerBuild(task, session, workspacePath, "-f " + config.getBaseDockerfilePath(), baseImage);
            support.dockerPush(task, session, project, baseImage);
            artifacts.add(support.imageArtifact(ArtifactType.BASE_IMAGE, baseImage, tag, support.dockerDigest(session, baseImage)));
            // 应用镜像引用基础镜像。
            dockerfileOption = "-f " + config.getDockerfilePath() + " --build-arg BASE_IMAGE=" + baseImage;
        } else {
            // Dockerfile 全部由项目仓库提供，平台不生成任何模板；
            // 能走到这里说明组合校验被绕过，宁可明确失败也不要猜一份模板去构建。
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建模式 " + buildMode.getCode() + " 不支持构建镜像，"
                            + "仅 PROJECT_DOCKERFILE / LAYERED_DOCKER 会执行 docker build");
        }

        support.dockerBuild(task, session, workspacePath, dockerfileOption, fullImage);
        support.appendLog(task, BuildSupport.STAGE_BUILD, TaskLogLevel.INFO, "镜像构建完成");

        // 方案 C 闸门：镜像构建完成后、推送仓库前，先在构建机本地跑起来确认不是"构建成功但一启动就崩"。
        support.dockerRunVerify(task, session, fullImage, config);

        support.dockerPush(task, session, project, fullImage);
        String digest = support.dockerDigest(session, fullImage);
        artifacts.add(support.imageArtifact(ArtifactType.APP_IMAGE, fullImage, tag, digest));
        support.appendLog(task, BuildSupport.STAGE_PUSH, TaskLogLevel.INFO, "镜像已推送：" + fullImage
                + (StringUtils.hasText(digest) ? "，摘要 " + digest : ""));
        return support.readComposeFile(task, session, config, workspacePath);
    }
}
