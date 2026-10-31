package com.lx.deploy.build.executor;

import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.task.model.Task;

import java.util.List;

/**
 * 构建执行的不可变上下文。
 *
 * <p>把一次构建需要的所有「运行时输入」打包成一等对象，在编排器与各构建 Service 之间传递，
 * 避免每个 build 方法都扛一长串参数。字段在构造后不应再变：
 * {@code artifacts} 用传入的同一个 List 引用，构建过程中向其中 add，不新建集合。</p>
 *
 * @param task          当前任务
 * @param session       已认证的构建机会话
 * @param project       项目（提供运行时、构建模式、构建配置与代码来源）
 * @param config        解析后的构建配置
 * @param workspacePath 本次构建的工作区绝对路径
 * @param commit        实际构建所用的 Git 提交 ID，可为 null（直接发布产物场景）
 * @param version       已解析好的发布版本号字符串（与镜像 tag / 版本记录 / 产物目录同值）
 * @param artifacts     构建产物收集列表（方法内追加，调用方持有同一引用）
 * @param buildServer   构建机档案
 * @param artifactBase  构建机产物持久目录，已校验非空
 * @param runtimeType   运行时类型
 * @param buildMode     构建模式
 */
public record BuildContext(
        Task task,
        SshSession session,
        Project project,
        BuildConfig config,
        String workspacePath,
        String commit,
        String version,
        List<BuildArtifact> artifacts,
        Server buildServer,
        String artifactBase,
        RuntimeType runtimeType,
        BuildMode buildMode) {
}
