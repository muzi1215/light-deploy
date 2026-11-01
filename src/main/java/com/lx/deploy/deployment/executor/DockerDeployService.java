package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.project.DeployForm;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.ComposeInjection;
import com.lx.deploy.common.util.RegistryAuthSupport;
import com.lx.deploy.common.util.ShellQuote;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCKER 运行时发布服务：拉取镜像并把容器按配置形态拉起来。
 *
 * <p>只负责「把某个版本的镜像在某一台机器上跑起来」这一件事：
 * 镜像已在构建阶段推送到仓库，本服务不参与构建，也不接触 jar / 静态包这类文件产物。</p>
 *
 * <p>两种部署形态（由运行配置 {@code deploy_form} 选择，默认 RUN）：</p>
 * <ul>
 *   <li><b>RUN</b>：平台拼 {@code docker run}，端口映射与容器名来自运行配置，适合单容器应用；</li>
 *   <li><b>COMPOSE</b>：把版本里快照的 docker-compose.yml 下发到目标机工作目录，
 *       由 {@code docker compose up -d} 启动。<b>编排完全由项目自己的文件决定</b>——
 *       端口、数据卷、网络、服务间依赖平台一概不管，唯一介入的是把镜像版本号做成变量注入。</li>
 * </ul>
 *
 * <p><b>目标机必须先用仓库凭据登录</b>：镜像仓库是私有的（如阿里云 ACR），目标机本地
 * 默认没有凭据。构建机推送后出于安全会登出，目标机若不复用构建机（绝大多数生产部署都如此）
 * 必然落在「未登录」状态，直接 {@code docker pull} 会被仓库拒绝。因此发布路径在 pull 之前
 * 必须按项目绑定的 REGISTRY 凭据在目标机 {@code docker login}，pull 完成后登出并清理密码文件。
 * 登录逻辑与构建路径共用 {@link RegistryAuthSupport} 的解析与命令拼装，避免两处分叉。</p>
 *
 * <p>启动后必须再确认一次运行态：无论哪种形态，命令返回成功只代表「容器被创建」，
 * 「启动即退出」的应用会被误报成发布成功。</p>
 *
 * <p>回滚与本服务无关的特殊性：镜像是不可变制品，回滚就是拿另一个版本的 tag 重跑同样流程，
 * 不需要在目标机保留历史副本，因此比 JAR / STATIC 少一整套备份逻辑。
 * compose 形态下还多一步：连同 compose 文件一起按目标版本重写，服务编排也随之回到旧版本。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Service
public class DockerDeployService {

    /** B1 热修包文件名后缀：发布端据此判定「必须始终下发」，与源码构建上下文区分。 */
    private static final String HOTFIX_KIT_SUFFIX = "hotfix-kit.tar.gz";

    /** 部署支撑组件：任务日志与通用工具。 */
    private final DeploySupport support;

    /** SSH 会话工厂：建立到目标机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 镜像仓库 Mapper：按项目 registry_id 读取仓库（取其地址与绑定凭据）。 */
    private final RegistryMapper registryMapper;

    /** 凭据 Mapper：读取 REGISTRY 类型凭据实体。 */
    private final CredentialMapper credentialMapper;

    /** 凭据加解密组件：解密凭据密文得到明文。 */
    private final CredentialCrypto credentialCrypto;

    /** 服务器 Mapper：按 {@code release_record.build_server_id} 取构建机（读取版本配置目录用）。 */
    private final ServerMapper serverMapper;

    /**
     * 创建 DOCKER 发布服务。
     *
     * @param support           部署支撑组件
     * @param sshSessionFactory SSH 会话工厂
     * @param registryMapper    镜像仓库 Mapper
     * @param credentialMapper  凭据 Mapper
     * @param credentialCrypto  凭据加解密组件
     * @param serverMapper      服务器 Mapper
     */
    public DockerDeployService(DeploySupport support, SshSessionFactory sshSessionFactory,
                               RegistryMapper registryMapper, CredentialMapper credentialMapper,
                               CredentialCrypto credentialCrypto, ServerMapper serverMapper) {
        this.support = support;
        this.sshSessionFactory = sshSessionFactory;
        this.registryMapper = registryMapper;
        this.credentialMapper = credentialMapper;
        this.credentialCrypto = credentialCrypto;
        this.serverMapper = serverMapper;
    }

    /**
     * 在目标机上拉取指定版本镜像并按配置形态启动。
     *
     * @param ctx 目标级发布上下文，使用其中的 server / config / release / task
     * @throws DeployException 镜像标签缺失、形态所需内容缺失、登录失败、拉取失败、启动失败或未处于运行态时抛出
     */
    public void deploy(DeployContext ctx) {
        String image = ctx.release().getImageTag();
        if (!StringUtils.hasText(image)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 没有镜像标签，无法按 DOCKER 运行时发布");
        }
        if (DeployForm.fromCode(ctx.config().getDeployForm()) == DeployForm.COMPOSE) {
            deployCompose(ctx, image);
        } else {
            deployRun(ctx, image);
        }
    }

    /**
     * RUN 形态：单个容器，参数由平台按运行配置拼装。
     */
    private void deployRun(DeployContext ctx, String image) {
        String containerName = StringUtils.hasText(ctx.config().getContainerName())
                ? ctx.config().getContainerName() : ctx.project().getCode();
        SshSession session = sshSessionFactory.open(ctx.server());
        RegistryLogin login = null;
        try {
            // 私有仓库必须先登录：目标机默认无凭据，构建机的登录态也不会外溢到目标机。
            login = dockerLoginOnTarget(session, ctx);
            // RUN 形态原本完全不在目标机落盘；B1 的热修包必须落下来，
            // 否则平台一旦不可用，这台机器上除了镜像什么都没有，无法就地重建。
            if (shouldDeliverKit(ctx)) {
                String dir = workDir(ctx);
                support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                        "准备应急重建目录 " + dir);
                run(session, ctx, DeploySupport.STAGE_PREPARE, "mkdir -p " + ShellQuote.quote(dir), "创建目录失败");
                deliverBuildContext(ctx, session, dir);
            }
            // 配置分发：把版本目录里的配置初稿投放到 <工作目录>/config（目标机已存在则不覆盖）。
            // RUN 形态是否真用得上取决于 extra_docker_args 里有没有 -v 挂这个目录；
            // 没有配置文件时这里不创建任何目录，保持「RUN 形态默认不在目标机落盘」的原状。
            deliverConfigFiles(ctx, session, workDir(ctx));
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO, "拉取镜像 " + image);
            // 拉取镜像可能上千 MB，按长命令超时等待，不能用秒级超时。
            SshCommandResult pull = execLongLogged(session, ctx, DeploySupport.STAGE_DELIVER, "docker pull " + image);
            if (!pull.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker pull 失败：" + support.firstLine(pull.stderr()));
            }
            support.writeOutput(ctx.task(), DeploySupport.STAGE_DELIVER, pull.stdout());
            // 先移除同名旧容器：容器名是发布幂等的关键，不删除会导致 run 直接失败。
            // （等价于手工 docker stop + docker rm，rm -f 自带停止语义）
            execLogged(session, ctx, DeploySupport.STAGE_START,
                    "docker rm -f " + containerName + " >/dev/null 2>&1 || true");
            String runCommand = "docker run -d --restart=always --name " + containerName
                    + portOptions(ctx.config()) + extraArgs(ctx.config()) + " " + image;
            support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO, "启动容器 " + containerName);
            // 容器启动可能包含镜像解压与首次初始化（如 Spring Boot 解包），按长命令超时等待。
            SshCommandResult run = execLongLogged(session, ctx, DeploySupport.STAGE_START, runCommand);
            if (!run.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker run 失败：" + support.firstLine(run.stderr()));
            }
            requireRunningContainer(ctx, session, containerName);
        } finally {
            dockerLogoutOnTarget(ctx, session, login);
            session.close();
        }
    }

    /**
     * COMPOSE 形态：下发项目自带的 compose 文件，用变量注入版本号后交给 docker compose 启动。
     *
     * <p>顺序固定为「登录 → 准备目录 → 写 compose → 写版本变量 → pull → 清理旧容器(down/rm) → up -d → 校验运行态」。
     * 先 pull 再 up 是为了让镜像下载单独出日志：混在 up 里一旦失败，
     * 很难分清是拉不到镜像还是编排有问题。登录放在最前，保证 pull 之前目标机已具备仓库凭据。</p>
     */
    private void deployCompose(DeployContext ctx, String image) {
        String content = requireComposeContent(ctx);
        String dir = workDir(ctx);
        String composeFile = dir + "/" + ComposeInjection.COMPOSE_FILE;
        String envFile = dir + "/" + ComposeInjection.ENV_FILE;
        // 显式指定 project name：默认取目录名，而目录可能因运维习惯改过，
        // 名字一变 compose 就认不出上一次的容器，会在同一台机器上叠出两套。
        String base = "docker compose -p " + ctx.project().getCode()
                + " -f " + ShellQuote.quote(composeFile) + " --env-file " + ShellQuote.quote(envFile);
        log.info("compose容器启动命令：{}", base);
        SshSession session = sshSessionFactory.open(ctx.server());
        RegistryLogin login = null;
        try {
            login = dockerLoginOnTarget(session, ctx);
            support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO, "准备部署目录 " + dir);
            run(session, ctx, DeploySupport.STAGE_PREPARE, "mkdir -p " + ShellQuote.quote(dir), "创建目录失败");

            // 配置分发必须在 up -d 之前：compose 里形如 ./config:/app/config 的绑定挂载
            // 在容器创建那一刻就把宿主目录挂进去了，之后才往里放文件，容器已经按「空目录」起来了
            //（表现为「宿主机有配置、容器里读不到」，且 docker inspect 看不出任何异常）。
            int delivered = deliverConfigFiles(ctx, session, dir);
            warnIfConfigMountEmpty(ctx, session, dir, content, delivered);

            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "下发 compose 文件到 " + composeFile);
            run(session, ctx, DeploySupport.STAGE_DELIVER, ShellQuote.heredocWrite(composeFile, content), "写入 compose 文件失败");

            // 版本号通过独立变量文件注入，平台不改 compose 原文：
            // 运维上机看到的文件必须与仓库里的一致，否则「文件里写的是啥」就不再可信。
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "注入镜像版本 " + ComposeInjection.VARIABLE + "=" + image);
            run(session, ctx, DeploySupport.STAGE_DELIVER, ShellQuote.heredocWrite(envFile, ComposeInjection.envContent(image)), "写入版本变量文件失败");

            // 应急重建素材：B1 热修包必须始终下发（目标机无源码，平台挂了只能靠它自救）；
            // 标准源码上下文沿用既有语义，仅在显式配置 work_dir 时下发。
            if (shouldDeliverKit(ctx)) {
                deliverBuildContext(ctx, session, dir);
            }

            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO, "拉取镜像 " + image);
            SshCommandResult pull = execLongLogged(session, ctx, DeploySupport.STAGE_DELIVER, base + " pull");
            if (!pull.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker compose pull 失败：\n" + support.outputSummary(pull.stdout(), pull.stderr()));
            }
            support.writeOutput(ctx.task(), DeploySupport.STAGE_DELIVER, pull.stdout());

            // 替换式重部署：docker compose up -d 遇到目标机上已存在的同名容器会直接报
            // Conflict（常见于 compose 里硬编码 container_name，且旧容器来自上一次遗留栈 /
            // 手动 docker run / 其它 -p 命名空间）——这与 RUN 形态用 docker rm -f 兜底一致。
            // 先按 compose 显式声明的容器名强制移除，再 down 拆掉上一次同栈，最后 up -d 重建。
            for (String name : parseContainerNames(content)) {
                execLogged(session, ctx, DeploySupport.STAGE_START,
                        "docker rm -f " + ShellQuote.quote(name) + " >/dev/null 2>&1 || true");
            }
            SshCommandResult down = execLongLogged(session, ctx, DeploySupport.STAGE_START, base + " down");
            if (!down.success()) {
                support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO,
                        "docker compose down 未完全成功（可能上一次并未以同栈方式运行），继续尝试 up -d");
            }

            support.appendLog(ctx.task(), DeploySupport.STAGE_START, TaskLogLevel.INFO, "启动服务编排（up -d）");
            SshCommandResult up = execLongLogged(session, ctx, DeploySupport.STAGE_START, base + " up -d");
            if (!up.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "docker compose up 失败：\n" + support.outputSummary(up.stdout(), up.stderr()));
            }
            support.writeOutput(ctx.task(), DeploySupport.STAGE_START, up.stdout());
            requireRunningServices(ctx, session, base);
        } finally {
            dockerLogoutOnTarget(ctx, session, login);
            session.close();
        }
    }

    /**
     * 解析 compose 内容里显式声明的容器名（{@code container_name: xxx}）。
     *
     * <p>仅返回静态字面量，跳过含 {@code ${...}} 变量展开的项——平台无法在发布期解析
     * compose 变量文件之外的占位符，强行移除一个不存在的名字只会让 {@code docker rm -f}
     * 走 {@code || true} 兜底，无害但无意义。硬编码容器名正是本次 {@code Conflict} 的来源，
     * 平台在 up -d 前按这些名字强制移除，确保同名旧容器不会挡路。</p>
     *
     * @param content compose 文件内容
     * @return 静态容器名集合（去重，可能为空）
     */
    private List<String> parseContainerNames(String content) {
        List<String> names = new ArrayList<>();
        if (content == null) {
            return names;
        }
        Pattern pattern = Pattern.compile("^\\s*container_name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            // 跳过变量展开（如 ${PROJECT}-backend）与 YAML 锚点引用
            if (!name.contains("${") && !name.startsWith("*")) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 判断本次发布是否要下发应急重建素材（构建上下文 / B1 热修包）。
     *
     * <p>两条来源的语义不同，不能一刀切：</p>
     * <ul>
     *   <li><b>B1 热修包</b>（{@code hotfix-kit.tar.gz}）：目标机既无源码也无 maven，
     *       它是平台不可用时唯一能就地重建的素材，因此<b>无论是否配置 work_dir 都必须下发</b>；</li>
     *   <li><b>标准源码上下文</b>（{@code build-context.tar.gz}）：体积是整仓源码，
     *       沿用既有语义，只在显式配置 {@code work_dir} 时下发，避免给不需要的机器平白多传几十 MB。</li>
     * </ul>
     *
     * @param ctx 部署上下文
     * @return 需要下发时返回 true
     */
    private boolean shouldDeliverKit(DeployContext ctx) {
        String path = ctx.release().getContextArtifactPath();
        if (!StringUtils.hasText(path)) {
            return false;
        }
        if (path.endsWith(HOTFIX_KIT_SUFFIX)) {
            return true;
        }
        return StringUtils.hasText(ctx.config().getWorkDir());
    }

    /**
     * 把应急重建素材下发并解包到目标机工作目录，供运维上机 {@code docker build} 再次构建。
     *
     * <p>素材有两种：B1 的平铺热修包（jar + Dockerfile + compose）与标准源码构建上下文。
     * 走与 JAR / STATIC 一致的「构建机 → 平台 → 目标机」两跳中转：先经
     * {@link DeploySupport#downloadArtifact} 从构建机拉到平台本地临时文件，再 {@code sftpUpload}
     * 到目标机，最后解包到 {@code <工作目录>/build-context/}，解包后删 tar 省空间。</p>
     *
     * <p>这是「应急上机重建」的辅助材料，下发失败只记日志不阻断正常的镜像发布流程——
     * 即便它没到位，已发布的镜像仍可正常回滚，不应因为一个可选材料把一次成功发布判成失败。</p>
     *
     * @param ctx     部署上下文
     * @param session 已认证的目标机会话
     * @param dir     目标机工作目录绝对路径
     */
    private void deliverBuildContext(DeployContext ctx, SshSession session, String dir) {
        String remoteTar = ctx.release().getContextArtifactPath();
        if (!StringUtils.hasText(remoteTar)) {
            return;
        }
        String fileName = remoteTar.substring(remoteTar.lastIndexOf('/') + 1);
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "下发应急重建素材到 " + dir + "/build-context（含 jar / Dockerfile / compose，供上机 docker build 重建）");
        Path local = null;
        try {
            local = support.downloadArtifact(ctx.task(), ctx.release(), remoteTar, fileName, ctx.deployment());
            String remoteFile = dir + "/" + fileName;
            session.sftpUpload(local, remoteFile);
            // 解包到 build-context/ 子目录，jar / Dockerfile / compose 随之平铺落位；解包后删 tar 省空间。
            String extract = "mkdir -p " + ShellQuote.quote(dir + "/build-context")
                    + " && tar xzf " + ShellQuote.quote(remoteFile)
                    + " -C " + ShellQuote.quote(dir + "/build-context")
                    + " && rm -f " + ShellQuote.quote(remoteFile);
            run(session, ctx, DeploySupport.STAGE_DELIVER, extract, "解包应急重建素材失败");
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "应急重建素材已就绪：" + dir + "/build-context（cd 进去按 README.md 执行 docker build / docker compose up 即可，不依赖平台）");
        } catch (Exception e) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "应急重建素材下发失败（不影响镜像发布，仍可回滚）：" + e.getMessage());
        } finally {
            if (local != null) {
                support.deleteQuietly(local);
            }
        }
    }

    // ==================== 配置分发 ====================

    /**
     * 把版本目录里的配置初稿投放到目标机工作目录的 {@code config/} 子目录。
     *
     * <p><b>为什么 DOCKER 也需要这一步</b>：镜像里的配置是「构建时的那一份」，而生产现场的
     * 库地址、账号几乎必然不同；compose 的 {@code ./config} 挂载只有在宿主目录真的有文件时才
     * 起作用，否则容器静默回落到镜像内配置。此前平台从不往 {@code <work_dir>/config} 写东西，
     * 于是首次部署后运维看到一个空目录，只能自己手抄一份配置放进去——
     * 这既是多余的体力活，也是「改错文件不生效」这类问题的温床。</p>
     *
     * <p><b>默认只投初稿、不覆盖</b>：目标机已存在同名文件时跳过（部署记录
     * {@code force_overwrite_config = false}）。配置与产物分离的意义就在于配置由运维自己维护，
     * 平台不该在每次发版时用仓库里的配置盖掉现场改动。这条口径与 JAR 运行时完全一致，
     * 实现也共用 {@link DeploySupport} 的那一份。</p>
     *
     * <p><b>勾选「强制覆盖」时放行</b>：部署记录 {@code force_overwrite_config = true} 时，
     * 平台先比对内容，不一致则把目标机旧配置改名备份为 {@code <文件名>_bak_<时间戳>}
     * （单文件、不建目录，与 JAR 备份约定同源）再写入新配置
     * （留退路，符合容灾底线）。用于「配置由仓库管理、改了仓库配置想下发」的场景——
     * 否则默认的「存在即跳过」会让新配置永远传不下去。</p>
     *
     * <p><b>配置缺失不阻断发布</b>：没有 {@code build_server_id}、构建机不可达、版本目录下没有
     * {@code config/}，一律返回 0 并继续发版——服务能不能起来由运行态校验判定，
     * 不该因为「没带配置文件」这种增强项把一次正常的镜像发布判成失败。</p>
     *
     * @param ctx     部署上下文
     * @param session 已认证的目标机会话
     * @param dir     目标机工作目录绝对路径
     * @return 本次实际写入（新增或覆盖）的配置文件数量；跳过的文件不计入
     */
    private int deliverConfigFiles(DeployContext ctx, SshSession session, String dir) {
        String buildServerId = ctx.release().getBuildServerId();
        if (!StringUtils.hasText(buildServerId)) {
            return 0;
        }
        Server buildServer = serverMapper.selectById(buildServerId);
        // DOCKER 的 artifact_path 是 null（产物是镜像），配置目录只能按约定拼，
        // 因此这里用 versionConfigDir 而不是从产物路径推断。
        String configDir = support.versionConfigDir(buildServer, ctx.project().getCode(),
                ctx.release().getVersion());
        if (configDir == null) {
            return 0;
        }
        Map<String, Path> files = support.downloadConfigFiles(ctx.task(), buildServer, configDir);
        if (files.isEmpty()) {
            return 0;
        }
        try {
            // 强制覆盖开关：从部署记录读取（创建时固化、异步任务再读）。
            // true 时内容不一致会先备份目标机旧配置再覆盖；false 时维持「存在即跳过」。
            boolean overwrite = Boolean.TRUE.equals(ctx.deployment().getForceOverwriteConfig());
            return support.uploadConfigFiles(ctx.task(), session,
                    dir + "/" + DeploySupport.CONFIG_SUBDIR, files, overwrite);
        } finally {
            // 本地临时文件是「构建机 → 平台 → 目标机」两跳中转的中间产物，用完必须删。
            files.values().forEach(support::deleteQuietly);
        }
    }

    /**
     * compose 挂载了 {@code ./config} 而目标机该目录启动前为空时，打一条显式提示。
     *
     * <p>这是最容易误判的一类「静默错配」：容器起得来、健康检查也过、平台报发布成功，
     * 但实际跑的是镜像里的默认配置（比如连的是测试库）。{@code docker inspect} 里
     * 挂载项一切正常，只有进容器 {@code ls /app/config} 才能发现是空的。
     * 因此不等到出事，发布日志里先把结论写出来。</p>
     *
     * <p>任务日志只有 INFO / ERROR 两级（见 {@link TaskLogLevel}），这里是可继续的提示，
     * 用「【注意】」前缀让它在一屏日志里能被一眼扫到。</p>
     *
     * @param ctx       部署上下文
     * @param session   已认证的目标机会话
     * @param dir       目标机工作目录
     * @param content   compose 文件内容
     * @param delivered 本次投放的文件数
     */
    private void warnIfConfigMountEmpty(DeployContext ctx, SshSession session, String dir,
                                        String content, int delivered) {
        if (content == null || !content.contains("./" + DeploySupport.CONFIG_SUBDIR)) {
            return;
        }
        String configDir = dir + "/" + DeploySupport.CONFIG_SUBDIR;
        SshCommandResult ls = execLogged(session, ctx, DeploySupport.STAGE_PREPARE,
                "ls -1 " + ShellQuote.quote(configDir) + " 2>/dev/null");
        if (ls.success() && StringUtils.hasText(ls.stdout())) {
            return;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.INFO,
                "【注意】compose 挂载了 ./" + DeploySupport.CONFIG_SUBDIR
                        + "，但目标机该目录为空"
                        + (delivered == 0 ? "（本次版本没有带出配置文件）" : "")
                        + "：容器将回落到镜像 / jar 内 classpath 的内置配置启动。"
                        + "需要外部配置请把文件放到 " + configDir
                        + " 后执行 docker compose -p " + ctx.project().getCode() + " up -d 重新拉起");
    }

    /**
     * 取本次要下发的 compose 内容，缺失时给出可操作的排查方向。
     *
     * <p>发布期还要再校验一次占位符（{@link ComposeInjection#requireUsable}）：构建期已校验过，
     * 但存量版本可能是加校验之前构建的，那段窗口期里产生的脏数据只在这里才拦得住。</p>
     */
    private String requireComposeContent(DeployContext ctx) {
        if (!StringUtils.hasText(ctx.release().getComposeContent())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 没有记录 compose 文件内容，无法按 COMPOSE 形态发布。"
                            + "请确认仓库中存在 docker-compose.yml 并提交后重新构建"
                            + "（文件不在仓库根目录时，用 build_config_json.compose_file_path 指定路径）。"
                            + "若只是想临时发出去，可把运行配置的 deploy_form 改成 RUN，"
                            + "但 compose 里编排的端口、卷与服务依赖都不会生效");
        }
        return ComposeInjection.requireUsable(ctx.release().getComposeContent());
    }

    /**
     * 在目标机登录镜像仓库：解析 REGISTRY 凭据 → 写密码文件 → docker login。
     *
     * <p>与构建路径共用 {@link RegistryAuthSupport}：凭据有两种存储形态（密码在密文 +
     * 用户名在 metadata，或用户名:密码整体在密文），由 {@code resolveRegistryCredentials}
     * 统一兼容。公共仓库（未绑定 registry_id）直接返回 null，调用方跳过登录。</p>
     *
     * @return 登录态（含登出用的仓库地址与密码文件路径）；无需登录时返回 null
     * @throws DeployException 凭据缺失、解密失败或登录失败时抛出
     */
    private RegistryLogin dockerLoginOnTarget(SshSession session, DeployContext ctx) {
        Project project = ctx.project();
        if (!StringUtils.hasText(project.getRegistryId())) {
            return null;
        }
        Registry registry = registryMapper.selectById(project.getRegistryId());
        if (registry == null || !StringUtils.hasText(registry.getCredentialId())) {
            return null;
        }
        Credential credential = credentialMapper.selectById(registry.getCredentialId());
        if (credential == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "镜像仓库绑定的凭据不存在");
        }
        String plaintext = credentialCrypto.decrypt(credential.getEncryptedValue());
        String[] up = RegistryAuthSupport.resolveRegistryCredentials(plaintext, credential.getMetadataJson());
        String loginHost = registry.getRegistryUrl();
        String passwordFile = "/tmp/ld_registry_pwd_deploy_" + ctx.task().getId();
        writeSecretFile(session, passwordFile, up[1]);
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "目标机登录镜像仓库 " + loginHost + "（密码经 stdin 传入，不落日志与命令行）");
        SshCommandResult loginResult = execLogged(session, ctx, DeploySupport.STAGE_DELIVER,
                RegistryAuthSupport.dockerLoginCommand(loginHost, up[0], passwordFile));
        if (!loginResult.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "目标机镜像仓库登录失败：" + support.firstLine(loginResult.stderr()));
        }
        return new RegistryLogin(loginHost, passwordFile);
    }

    /**
     * 在目标机登出镜像仓库并清理密码临时文件（成功失败都要清，绝不能把明文密码留在目标机磁盘）。
     *
     * @param ctx     部署上下文（取任务写命令日志）
     * @param session 已认证会话
     * @param login   登录态；为 null 时直接返回
     */
    private void dockerLogoutOnTarget(DeployContext ctx, SshSession session, RegistryLogin login) {
        if (login == null) {
            return;
        }
        if (login.passwordFile() != null) {
            execLogged(session, ctx, DeploySupport.STAGE_DELIVER, "rm -f " + login.passwordFile());
        }
        if (login.host() != null) {
            execLogged(session, ctx, DeploySupport.STAGE_DELIVER, RegistryAuthSupport.dockerLogoutCommand(login.host()));
        }
    }

    /**
     * 把敏感内容经 base64 写入目标机临时文件（权限 600）；明文不进命令行、不进日志。
     *
     * <p>与构建路径 {@code BuildTaskHandler#writeSecretFile} 同一实现，避免两处对
     * 「密码怎么安全落到目标机」的处理分叉。</p>
     */
    private void writeSecretFile(SshSession session, String path, String content) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String cmd = "echo " + b64 + " | base64 -d > " + path + " && chmod 600 " + path;
        SshCommandResult result = session.exec(cmd);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "写入镜像仓库密码到目标机临时文件失败：" + support.firstLine(result.stderr()));
        }
    }

    /**
     * 目标机部署目录：优先用运行配置的 work_dir，回落「部署根目录 / 项目编码」。
     *
     * <p>部署根目录按机器配在服务器档案里，因此这里不写死任何绝对路径。</p>
     */
    private String workDir(DeployContext ctx) {
        if (StringUtils.hasText(ctx.config().getWorkDir())) {
            return ctx.config().getWorkDir().strip();
        }
        return support.deployBase(ctx.server()) + "/" + ctx.project().getCode();
    }

    /**
     * 执行远程命令并打印（脱敏后的）命令原文到任务日志，失败抛出带错误首行的异常。
     *
     * <p>把命令落进任务日志是为了「平台不可用时运维能直接从日志抄命令上机手工操作」——
     * 尤其是不透明的 {@code docker compose -p &lt;项目编码&gt; ...} 与容器名，以前只能靠猜。
     * 日志一律走 {@link #commandLogText(String)} 脱敏，密码等敏感片段不会出现在日志里。</p>
     *
     * @param session 已认证会话
     * @param ctx     部署上下文（取任务用于写日志）
     * @param stage   日志阶段（prepare / deliver / start）
     * @param command 待执行命令
     * @param message 失败时的中文说明
     */
    private void run(SshSession session, DeployContext ctx, String stage, String command, String message) {
        support.appendLog(ctx.task(), stage, TaskLogLevel.INFO, "执行命令：\n" + commandLogText(command));
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    message + "：" + support.firstLine(result.stderr()));
        }
    }

    /**
     * 记录并执行短命令（不抛异常，结果由调用方判定），并把脱敏命令写进任务日志。
     *
     * @see #commandLogText(String)
     */
    private SshCommandResult execLogged(SshSession session, DeployContext ctx, String stage, String command) {
        support.appendLog(ctx.task(), stage, TaskLogLevel.INFO, "执行命令：\n" + commandLogText(command));
        return session.exec(command);
    }

    /**
     * 记录并执行长命令（拉镜像 / 起容器等可能跑很久，不抛异常，结果由调用方判定），
     * 并把脱敏命令写进任务日志。
     *
     * @see #commandLogText(String)
     */
    private SshCommandResult execLongLogged(SshSession session, DeployContext ctx, String stage, String command) {
        support.appendLog(ctx.task(), stage, TaskLogLevel.INFO, "执行命令：\n" + commandLogText(command));
        return session.execLong(command);
    }

    /**
     * 生成写入任务日志的命令文本：先脱敏，再把 heredoc（compose / 版本变量文件）的正文折叠，
     * 避免把整份 compose 塞进日志。
     *
     * @param command 原始命令
     * @return 可安全打印的日志文本
     */
    private String commandLogText(String command) {
        if (!StringUtils.hasText(command)) {
            return "";
        }
        // heredoc 写文件：只保留「cat > '<path>' <<'LD_EOF'」头，正文折叠（见发布版本快照）。
        if (command.contains("<<'LD_EOF")) {
            int headEnd = command.indexOf('\n');
            String head = headEnd > 0 ? command.substring(0, headEnd) : command;
            long lines = command.lines().count();
            return head + " ... (heredoc 内容 " + lines + " 行，不在命令日志展开，见发布版本快照)";
        }
        return redact(command);
    }

    /**
     * 命令脱敏：抹掉可能泄露凭据的片段。
     *
     * <p>平台登录走 {@code --password-stdin &lt;密码文件&gt;}，命令本身不含密码；唯一会泄露的是
     * {@code writeSecretFile} 里 {@code echo &lt;base64&gt; | base64 -d} 的 base64（可逆出明文）。
     * 兜底再把 {@code --password &lt;值&gt;} 这类显式写法抹掉。</p>
     *
     * @param command 原始命令
     * @return 脱敏后的命令
     */
    private static String redact(String command) {
        String s = SECRET_ECHO.matcher(command).replaceAll("echo *** | base64 -d");
        return PASSWORD_VALUE.matcher(s).replaceAll("$1***");
    }

    /** 匹配 {@code writeSecretFile} 里 {@code echo <base64> | base64 -d}：base64 可逆出明文密码，必须抹掉。 */
    private static final Pattern SECRET_ECHO = Pattern.compile("echo\\s+[A-Za-z0-9+/=]{8,}\\s*\\|\\s*base64\\s+-d");

    /** 兜底抹掉显式 {@code --password <值>}（登录已统一走 stdin，正常情况下不会命中）。 */
    private static final Pattern PASSWORD_VALUE = Pattern.compile("(--password\\s+)\\S+");

    /**
     * 确认单个容器处于运行态。
     *
     * @param ctx           部署上下文（取任务写命令日志）
     * @param session       已认证会话
     * @param containerName 容器名
     */
    private void requireRunningContainer(DeployContext ctx, SshSession session, String containerName) {
        // 容器启动成功不代表进程还在：立刻确认一次运行状态，避免把「启动即退出」报成成功。
        // docker inspect 同时是「查看状态」的等价命令，落日志后运维可直接抄。
        SshCommandResult inspect = execLogged(session, ctx, DeploySupport.STAGE_START,
                "docker inspect -f '{{.State.Running}}' " + containerName + " 2>/dev/null");
        if (!inspect.success() || !"true".equalsIgnoreCase(inspect.stdout().strip())) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "容器启动后未处于运行态，请检查应用启动日志（docker logs " + containerName + "）");
        }
    }

    /**
     * 确认编排中至少有一个服务处于运行态。
     *
     * <p>只判「至少一个」而不是「全部」：compose 里允许存在一次性任务容器（退出码 0 即完成），
     * 逐个苛求会让这类编排永远发布失败。</p>
     *
     * @param ctx  部署上下文（取任务写命令日志）
     * @param session 已认证会话
     * @param base 含 -p / -f / --env-file 的 compose 前缀命令
     */
    private void requireRunningServices(DeployContext ctx, SshSession session, String base) {
        SshCommandResult result = execLogged(session, ctx, DeploySupport.STAGE_START,
                base + " ps --status running --services 2>/dev/null");
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "查询编排运行状态失败：" + support.firstLine(result.stderr()));
        }
        long running = result.stdout().lines().map(String::strip).filter(s -> !s.isEmpty()).count();
        if (running == 0) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "编排启动后没有任何服务处于运行态，请在目标机执行 docker compose ps 与 docker compose logs 排查");
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

    /** 登录态载体：登出与清理密码文件时复用。 */
    private record RegistryLogin(String host, String passwordFile) {
    }
}
