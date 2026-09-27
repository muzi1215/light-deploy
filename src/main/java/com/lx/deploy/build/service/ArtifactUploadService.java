package com.lx.deploy.build.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.constants.BuildStatus;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.build.ArtifactType;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.project.SourceType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.build.mapper.BuildMapper;
import com.lx.deploy.build.model.Build;
import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.build.model.vo.BuildVo;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.service.ReleaseRetentionExecutor;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 产物上传服务：把本地构建好的包登记为一个可直接发布的版本。
 *
 * <p>对应「前端本地 {@code npm run build} 打成 zip 上传」「jar 本机打好直接上传」这类场景：
 * 项目来源为 {@code UPLOAD}、构建模式为 {@code ARTIFACT_ONLY}，平台不做任何构建，
 * 只负责把产物存到构建机的产物持久目录并登记一个版本，之后走与正常构建产物完全相同的发布链路。</p>
 *
 * <p><b>为什么产物必须落到构建机而不是平台本地</b>：发布执行器下载产物时强制要求
 * {@code release_record.build_server_id}（见 {@code DeploySupport#downloadArtifact}），
 * 走的是「构建机 → 平台 → 目标机」两跳。把上传的文件只存在平台本地，
 * 发布阶段会直接报「未记录构建机」——构建机在这里是产物暂存与分发中心，不是构建中心。</p>
 *
 * <p><b>为什么用编程式事务</b>：本服务的动作天然分成两段——SFTP 上传是分钟级的远程 IO，
 * 落库是三张表（build / build_artifact / release_record）的写入。
 * AGENTS.md §4.6 要求「事务方法内禁止发起远程调用与耗时 IO」，
 * 因此事务边界必须只包住落库这一段；而同一个 bean 内自调用 {@code @Transactional} 方法不经过代理、
 * 事务不会生效，这里改用 {@link TransactionTemplate} 显式划定边界，比拆一个只为过代理的类更直白。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Service
public class ArtifactUploadService {

    /** 受支持的上传产物扩展名：zip / tar.gz / tgz 为静态产物，jar 为 JAR 运行时产物。 */
    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** 构建机产物持久目录下的版本标识文件名，与构建链路同名同格式。 */
    private static final String VERSION_MARKER = "VERSION";

    /** 版本号里的时间戳格式（无提交场景退化），与 BuildService 保持一致。 */
    private static final java.time.format.DateTimeFormatter VERSION_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 默认构建机服务器 ID：项目档案未绑定时采用。 */
    private final String defaultBuildServerId;

    /** 项目 Mapper：校验项目、读取运行时与构建模式。 */
    private final ProjectMapper projectMapper;

    /** 服务器 Mapper：读取构建机档案与产物持久目录。 */
    private final ServerMapper serverMapper;

    /** 构建 Mapper：插入本次上传对应的 build 记录。 */
    private final BuildMapper buildMapper;

    /** 发布版本 Mapper：校验版本号是否被占用。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 构建服务：复用版本号定稿与「成功 + 产物 + 版本」的落库能力。 */
    private final BuildService buildService;

    /** SSH 会话工厂：把产物上传到构建机。 */
    private final SshSessionFactory sshSessionFactory;

    /** 版本保留策略执行器：上传登记后按项目保留数回收超额历史产物。 */
    private final ReleaseRetentionExecutor retentionExecutor;

    /** 编程式事务模板：只包住落库，不包住 SFTP 传输。 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建产物上传服务。
     *
     * @param defaultBuildServerId 系统默认构建机 ID（{@code light-deploy.build.server-id}）
     * @param projectMapper        项目 Mapper
     * @param serverMapper         服务器 Mapper
     * @param buildMapper          构建 Mapper
     * @param releaseRecordMapper  发布版本 Mapper
     * @param buildService         构建服务
     * @param sshSessionFactory    SSH 会话工厂
     * @param retentionExecutor    版本保留策略执行器
     * @param transactionManager   事务管理器，用于自行构造只包住落库的事务模板
     */
    public ArtifactUploadService(@Value("${light-deploy.build.server-id:}") String defaultBuildServerId,
                                 ProjectMapper projectMapper,
                                 ServerMapper serverMapper,
                                 BuildMapper buildMapper,
                                 ReleaseRecordMapper releaseRecordMapper,
                                 BuildService buildService,
                                 SshSessionFactory sshSessionFactory,
                                 ReleaseRetentionExecutor retentionExecutor,
                                 PlatformTransactionManager transactionManager) {
        this.defaultBuildServerId = defaultBuildServerId;
        this.projectMapper = projectMapper;
        this.serverMapper = serverMapper;
        this.buildMapper = buildMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.buildService = buildService;
        this.sshSessionFactory = sshSessionFactory;
        this.retentionExecutor = retentionExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 上传一份产物并登记为可发布版本。
     *
     * <p>整条链路同步执行：上传只是「传文件 + 写三张表」，没有构建那样的分钟级等待，
     * 走异步任务反而要让前端多轮询一次。文件较大时由 multipart 上限与网关超时兜底。</p>
     *
     * @param projectId  目标项目主键
     * @param versionSeq 人工指定的版本序号，为空表示自动取号（项目内最大序号 +1）
     * @param file       上传的产物文件
     * @return 本次登记对应的构建记录（含版本号信息可通过构建详情页查看）
     * @throws DeployException 项目/配置不合法、文件为空或格式不受支持、构建机不可用、上传失败时抛出
     */
    public BuildVo upload(String projectId, Integer versionSeq, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "上传文件为空");
        }
        Project project = requireUploadableProject(projectId);
        String fileName = requireSafeFileName(file.getOriginalFilename());
        Server buildServer = resolveBuildServer(project);
        String artifactBase = buildServer.getBuildArtifactDir();
        if (!StringUtils.hasText(artifactBase)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建机 " + buildServer.getServerName() + " 未配置构建产物持久目录，请在服务器页面补充后重新上传");
        }

        // 上传场景没有 Git 提交，版本号退化为 {编码}-v{序号}-yyyyMMddHHmmss（BuildService 既有口径）。
        BuildService.ReleaseVersion releaseVersion = buildService.resolveReleaseVersion(
                project.getId(), project.getCode(), null, versionSeq);
        requireVersionAvailable(project.getId(), releaseVersion.version());

        String persistDir = artifactBase.strip() + "/" + project.getCode() + "/" + releaseVersion.version();
        String persistedPath = persistDir + "/" + fileName;

        Path localFile = null;
        try {
            localFile = writeToTempFile(file, fileName);
            String sha256 = sha256Of(localFile);
            long size = Files.size(localFile);
            uploadToBuildServer(buildServer, localFile, persistDir, persistedPath);
            writeVersionMarker(buildServer, persistDir, releaseVersion.version(), fileName, project.getRuntimeType());
            // 落库放在事务里，且与上面的 SFTP 严格分离：事务内不允许出现分钟级远程 IO。
            BuildVo vo = transactionTemplate.execute(status -> persist(project, buildServer, releaseVersion,
                    fileName, persistedPath, sha256, size));
            recycleIfNeeded(project, buildServer);
            return vo;
        } catch (IOException e) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "接收上传文件失败：" + e.getMessage());
        } finally {
            deleteQuietly(localFile);
        }
    }

    // ==================== 校验 ====================

    /**
     * 校验项目允许走产物上传。
     *
     * <p>三个条件缺一不可：来源是 UPLOAD（说明代码不从仓库来）、构建模式是 ARTIFACT_ONLY
     * （说明平台不做构建）、运行时是 JAR 或 STATIC（DOCKER 的产物是镜像，必须由镜像仓库承载，
     * 上传一个文件无法变成可发布的镜像）。</p>
     *
     * @param projectId 项目主键
     * @return 项目档案
     * @throws DeployException 项目不存在、已停用或不满足上传条件时抛出
     */
    private Project requireUploadableProject(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "缺少项目 ID");
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标项目不存在");
        }
        if (project.getStatus() == null || project.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标项目已停用，不允许上传产物");
        }
        String sourceType = StringUtils.hasText(project.getSourceType()) ? project.getSourceType() : SourceType.GIT.getCode();
        if (!SourceType.UPLOAD.getCode().equals(sourceType)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目来源为 " + sourceType + "，不支持上传产物；请在项目配置中把来源改为 UPLOAD（本地上传）");
        }
        BuildMode buildMode = BuildMode.fromCode(project.getBuildMode());
        if (buildMode != BuildMode.ARTIFACT_ONLY) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目构建模式为 " + project.getBuildMode() + "，不支持上传产物；"
                            + "上传产物要求构建模式为 ARTIFACT_ONLY（仅发布产物）");
        }
        RuntimeType runtimeType = RuntimeType.fromCode(project.getRuntimeType());
        if (runtimeType != RuntimeType.JAR && runtimeType != RuntimeType.STATIC) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "仅 JAR / STATIC 运行时支持上传产物，当前运行时为 " + project.getRuntimeType()
                            + "；DOCKER 产物是镜像，应通过构建推送到镜像仓库");
        }
        return project;
    }

    /**
     * 校验上传文件名安全且格式受支持。
     *
     * <p>两层校验都要有：文件名过滤掉路径分隔符与空白，防止 {@code ../} 把产物写到构建机的任意位置；
     * 扩展名白名单保证发布阶段知道怎么解压（zip 用 unzip、tar.gz 用 tar），
     * 放一个 {.rar} 上去会在目标机解压时才发现没人认识它。</p>
     *
     * @param originalFilename 原始文件名，允许为空（部分客户端不传）
     * @return 规范化后的文件名
     * @throws DeployException 文件名缺失、含非法字符或格式不受支持时抛出
     */
    private String requireSafeFileName(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "上传文件缺少文件名");
        }
        // 只取末段：有些客户端会把带路径的名字原样传上来。
        String name = originalFilename.contains("/")
                ? originalFilename.substring(originalFilename.lastIndexOf('/') + 1) : originalFilename;
        name = name.contains("\\") ? name.substring(name.lastIndexOf('\\') + 1) : name;
        name = name.strip();
        if (!SAFE_FILE_NAME.matcher(name).matches()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "文件名只允许字母、数字与 . _ - ：" + name);
        }
        String lower = name.toLowerCase();
        boolean supported = lower.endsWith(".zip") || lower.endsWith(".tar.gz")
                || lower.endsWith(".tgz") || lower.endsWith(".jar");
        if (!supported) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "不受支持的产物格式：" + name + "，请上传 .zip / .tar.gz / .tgz（静态产物）或 .jar（JAR 运行时）");
        }
        return name;
    }

    /**
     * 解析产物要存放的构建机。
     *
     * <p>优先级与构建链路一致：项目档案绑定 > 系统默认。上传不提供请求级覆盖——
     * 产物存哪台机器属于项目属性，让每次上传都能换一台只会让发布阶段找不到东西。</p>
     *
     * @param project 项目档案
     * @return 构建机档案
     * @throws DeployException 未配置构建机、构建机不存在或已停用时抛出
     */
    private Server resolveBuildServer(Project project) {
        String buildServerId = StringUtils.hasText(project.getBuildServerId())
                ? project.getBuildServerId() : defaultBuildServerId;
        if (!StringUtils.hasText(buildServerId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "未配置构建机：请在项目档案里绑定构建机，或在系统配置 light-deploy.build.server-id 中设置默认构建机");
        }
        Server buildServer = serverMapper.selectById(buildServerId);
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目绑定的构建机不存在");
        }
        if (buildServer.getStatus() == null || buildServer.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "项目绑定的构建机已停用，请更换构建机");
        }
        return buildServer;
    }

    /**
     * 校验版本号未被占用。
     *
     * <p>人工指定序号时这一步必须提前做：等到落库撞唯一键，产物已经传到构建机了，
     * 用户看到的是「换个序号再来一次」，而远端多了一份永远不会被引用的文件。</p>
     *
     * @param projectId 项目主键
     * @param version   待使用的版本号
     * @throws DeployException 版本号已存在时抛出
     */
    private void requireVersionAvailable(String projectId, String version) {
        Long count = releaseRecordMapper.selectCount(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, projectId)
                .eq(ReleaseRecord::getVersion, version));
        if (count != null && count > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "发布版本 " + version + " 已存在，请更换版本序号后重新上传");
        }
    }

    // ==================== 文件处理 ====================

    /**
     * 把上传内容落到平台本地临时文件。
     *
     * <p>先落盘再上传有两个原因：SFTP 需要可随机读的本地文件；SHA256 也要读完整个流才能算出来，
     * 落盘后一次计算即可，不必让 MultipartFile 的流被消费两遍。</p>
     *
     * @param file     上传文件
     * @param fileName 规范化后的文件名
     * @return 平台本地临时文件路径
     * @throws IOException 写入临时文件失败时抛出
     */
    private Path writeToTempFile(MultipartFile file, String fileName) throws IOException {
        Path temp = Files.createTempFile("ld-upload-", "-" + fileName);
        try (InputStream in = file.getInputStream()) {
            Files.write(temp, in.readAllBytes());
        }
        return temp;
    }

    /**
     * 计算文件的 SHA256。
     *
     * <p>与构建链路在构建机上算摘要的口径一致，发布阶段据此校验产物经过两跳传输后是否损坏。
     * 计算失败不阻断上传——摘要是校验用的辅助信息，缺失只会让发布阶段跳过校验。</p>
     *
     * @param file 本地文件
     * @return 小写十六进制摘要；计算失败时返回 null
     */
    private String sha256Of(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("计算上传产物摘要失败，file={}，原因：{}", file, e.getMessage());
            return null;
        }
    }

    /**
     * 把产物上传到构建机的产物持久目录。
     *
     * <p>目录结构与构建链路完全一致（{@code {持久目录}/{项目编码}/{版本号}/{文件名}}），
     * 这样运维在构建机上看到上传产物与构建产物时，不需要两套找法。</p>
     *
     * @param buildServer   构建机
     * @param localFile     平台本地临时文件
     * @param persistDir    构建机上的产物目录
     * @param persistedPath 构建机上的产物绝对路径
     * @throws DeployException SSH 连接失败、目录创建失败或上传失败时抛出
     */
    private void uploadToBuildServer(Server buildServer, Path localFile, String persistDir, String persistedPath) {
        try (SshSession session = sshSessionFactory.open(buildServer)) {
            SshCommandResult mkdir = session.exec("mkdir -p " + persistDir);
            if (!mkdir.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "构建机产物目录创建失败：" + firstLine(mkdir.stderr()));
            }
            session.sftpUpload(localFile, persistedPath);
            log.info("上传产物已转存至构建机 {}", persistedPath);
        }
    }

    /**
     * 在构建机产物目录写入版本标识。
     *
     * <p>与构建链路同格式（key=value 多行）：运维在构建机上看到一个文件时，
     * 靠它可以一步确认「这是哪个版本、什么运行时的产物」，不必回平台反查。
     * 写入失败只记日志——标识是辅助信息，不该因为它否定一次已经完成的上传。</p>
     *
     * @param buildServer 构建机
     * @param persistDir  产物目录
     * @param version     版本号
     * @param fileName    产物文件名
     * @param runtimeType 运行时编码
     */
    private void writeVersionMarker(Server buildServer, String persistDir, String version,
                                    String fileName, String runtimeType) {
        String content = "version=" + version + "\n"
                + "commit=unknown\n"
                + "runtime=" + runtimeType + "\n"
                + "artifact=" + fileName + "\n"
                + "built_at=" + LocalDateTime.now().format(VERSION_TIME_FORMATTER) + "\n";
        try (SshSession session = sshSessionFactory.open(buildServer)) {
            SshCommandResult result = session.exec(
                    "printf '%s' " + shellQuote(content) + " > " + persistDir + "/" + VERSION_MARKER);
            if (!result.success()) {
                log.warn("写入构建机版本标识失败，dir={}，原因：{}", persistDir, firstLine(result.stderr()));
            }
        } catch (Exception e) {
            log.warn("写入构建机版本标识失败，dir={}，原因：{}", persistDir, e.getMessage());
        }
    }

    // ==================== 落库 ====================

    /**
     * 把一次成功的上传落成 build + 产物 + 发布版本三张表的记录。
     *
     * <p>必须在事务里：三条记录是一个整体，任何一条失败都要整体回滚，
     * 否则会留下「有版本记录但没产物」或「有产物但发不出去」的半截数据。</p>
     *
     * <p>build 记录直接置为成功且开始/结束时间相同：这次「构建」没有执行任何构建动作，
     * 但发布链路与页面都按 build 展示来源，缺这条记录会让版本凭空出现、无从溯源。</p>
     *
     * @param project        项目
     * @param buildServer    构建机
     * @param releaseVersion 定稿的版本号
     * @param fileName       产物文件名
     * @param persistedPath  构建机上的产物绝对路径
     * @param sha256         产物摘要，可为 null
     * @param size           产物字节数
     * @return 本次上传对应的构建 VO
     */
    private BuildVo persist(Project project, Server buildServer, BuildService.ReleaseVersion releaseVersion,
                            String fileName, String persistedPath, String sha256, long size) {
        Build build = new Build();
        build.setProjectId(project.getId());
        build.setBuildServerId(buildServer.getId());
        build.setBuildMode(BuildMode.ARTIFACT_ONLY.getCode());
        build.setVersionSeq(releaseVersion.seq());
        build.setStatus(BuildStatus.SUCCESS);
        build.setStartedAt(LocalDateTime.now());
        build.setFinishedAt(LocalDateTime.now());
        buildMapper.insert(build);

        List<BuildArtifact> artifacts = new ArrayList<>();
        BuildArtifact artifact = new BuildArtifact();
        artifact.setArtifactType(RuntimeType.fromCode(project.getRuntimeType()) == RuntimeType.JAR
                ? ArtifactType.JAR.getCode() : ArtifactType.STATIC.getCode());
        artifact.setArtifactName(fileName);
        artifact.setArtifactTag(releaseVersion.version());
        artifact.setArtifactDigest(sha256);
        artifact.setMetadataJson("{\"path\":\"" + persistedPath + "\",\"size\":" + size + "}");
        artifacts.add(artifact);

        ReleaseRecord release = new ReleaseRecord();
        release.setVersion(releaseVersion.version());
        release.setVersionSeq(releaseVersion.seq());
        release.setArtifactPath(persistedPath);
        release.setSha256(sha256);
        release.setRuntimeType(project.getRuntimeType());
        release.setStatus(ReleaseStatus.RELEASABLE);
        // completeSuccess 会补 projectId 与 buildServerId，并统一处理版本号撞键。
        buildService.completeSuccess(build.getId(), null, artifacts, release);
        return buildService.getRequiredById(build.getId());
    }

    // ==================== 版本保留回收 ====================

    /**
     * 按项目保留版本数回收超额历史产物。
     *
     * <p>上传链路此前完全不参与回收，构建的回收实现又写在构建任务处理器内部，
     * 结果是上传登记的版本在磁盘上只增不减。现在两者共用 {@link ReleaseRetentionExecutor}。</p>
     *
     * <p><b>为什么异步</b>：本次 HTTP 请求已经承担了一次 SFTP 上传，回收可能还要连另一台构建机；
     * SSH 握手或超时的时间是分钟级，把它塞进同步响应会让上传页面长时间转圈。
     * 回收本来就不是上传成功的必要条件——漏做一次不影响结果，下一次构建或上传还会再触发，
     * 因此丢给后台线程、失败只记日志，是权衡后的合理取舍。</p>
     *
     * @param project     项目，提供编码与保留版本数
     * @param buildServer 本次上传所用的构建机
     */
    private void recycleIfNeeded(Project project, Server buildServer) {
        CompletableFuture.runAsync(() -> {
            try {
                ReleaseRetentionExecutor.RetentionReport report = retentionExecutor.execute(project, buildServer, null);
                if (report.count() > 0 || !report.messages().isEmpty()) {
                    log.info("上传后版本回收完成，projectId={}，废弃 {} 个版本", project.getId(), report.count());
                    report.messages().forEach(message -> log.info("版本回收：{}", message));
                }
            } catch (Exception e) {
                // 回收失败不影响上传结论：最坏结果只是磁盘上多留几个历史版本。
                log.warn("上传后版本回收失败（不影响上传结果），projectId={}，原因：{}",
                        project.getId(), e.getMessage());
            }
        });
    }

    // ==================== 辅助 ====================

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
     * 把参数转成 shell 单引号字面量，避免空格与引号破坏命令行。
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
     * 删除平台本地临时文件，失败只记录不抛出。
     *
     * @param file 待删除文件，允许为 null
     */
    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除上传临时文件失败，file={}", file, e);
        }
    }
}
