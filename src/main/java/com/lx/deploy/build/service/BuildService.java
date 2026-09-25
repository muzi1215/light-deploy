package com.lx.deploy.build.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.build.mapper.BuildArtifactMapper;
import com.lx.deploy.build.mapper.BuildMapper;
import com.lx.deploy.build.model.Build;
import com.lx.deploy.build.model.BuildArtifact;
import com.lx.deploy.build.model.dto.BuildDto;
import com.lx.deploy.build.model.vo.BuildArtifactVo;
import com.lx.deploy.build.model.vo.BuildVo;
import com.lx.deploy.common.constants.BuildStatus;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import com.lx.deploy.task.service.TaskService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 构建服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。职责分为两部分：</p>
 * <ul>
 *   <li><b>触发</b>：{@link #createAndTrigger(BuildDto)} 校验项目/运行时/构建模式/构建机，创建 build 记录
 *       并同步生成一条 BUILD 类型异步任务（bizId=build.id），由任务执行器驱动实际构建；</li>
 *   <li><b>状态维护</b>：构建执行器在关键节点回调 {@link #startBuild}、{@link #captureCommit}、
 *       {@link #completeSuccess}、{@link #completeFailure}，把 build 状态与产物、发布版本落到库。</li>
 * </ul>
 *
 * <p>跨模块只注入对方 Mapper（ProjectMapper / ServerMapper / TaskMapper）与 TaskService，
 * 不注入对方 Service，避免 Service 之间形成循环依赖（全项目既定约定）。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Service
public class BuildService extends ServiceImpl<BuildMapper, Build> {

    /** 失败原因写入数据库前的长度上限，避免超长文本塞满 error_message。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    /** 发布版本号时间戳格式。 */
    private static final java.time.format.DateTimeFormatter VERSION_TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 发布版本号中序号段的标记前缀，最终形如 {@code auth-7888-v3-887b603c}。 */
    private static final String VERSION_SEQ_PREFIX = "v";

    /** 版本号中提交短码的截取长度。 */
    private static final int VERSION_COMMIT_LENGTH = 8;

    /** 默认构建机服务器 ID：请求未指定、项目档案也未绑定时采用。 */
    private final String defaultBuildServerId;

    /** 项目 Mapper：校验项目存在/启用、读取运行时与构建配置。 */
    private final ProjectMapper projectMapper;

    /** 服务器 Mapper：校验构建机存在/启用、读取构建机名称。 */
    private final ServerMapper serverMapper;

    /** 构建产物 Mapper：保存与查询本次构建的产物。 */
    private final BuildArtifactMapper buildArtifactMapper;

    /** 发布版本 Mapper：构建成功时创建可发布版本。 */
    private final ReleaseRecordMapper releaseRecordMapper;

    /** 任务 Mapper：构建记录转 VO 时关联出驱动构建的异步任务标识。 */
    private final TaskMapper taskMapper;

    /** 任务服务：创建驱动构建的异步任务，强制从排队状态开始。 */
    private final TaskService taskService;

    /**
     * 注入构建模块与跨模块数据访问依赖。
     *
     * @param defaultBuildServerId 系统默认构建机 ID（{@code light-deploy.build.server-id}），允许为空
     * @param projectMapper        项目 Mapper
     * @param serverMapper         服务器 Mapper
     * @param buildArtifactMapper  构建产物 Mapper
     * @param releaseRecordMapper  发布版本 Mapper
     * @param taskMapper           任务 Mapper
     * @param taskService          任务服务
     */
    public BuildService(@Value("${light-deploy.build.server-id:}") String defaultBuildServerId,
                        ProjectMapper projectMapper,
                        ServerMapper serverMapper,
                        BuildArtifactMapper buildArtifactMapper,
                        ReleaseRecordMapper releaseRecordMapper,
                        TaskMapper taskMapper,
                        TaskService taskService) {
        this.defaultBuildServerId = defaultBuildServerId;
        this.projectMapper = projectMapper;
        this.serverMapper = serverMapper;
        this.buildArtifactMapper = buildArtifactMapper;
        this.releaseRecordMapper = releaseRecordMapper;
        this.taskMapper = taskMapper;
        this.taskService = taskService;
    }

    /**
     * 根据 DTO 中的非空条件分页查询构建记录。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<BuildVo> pageByCondition(PageQuery pageQuery, BuildDto condition) {
        Page<Build> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<BuildVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配构建记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<BuildVo> listByCondition(BuildDto condition) {
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回构建响应对象（含关联任务标识）。
     *
     * @param id 数据主键
     * @return 包含项目名、构建机名、状态文案与任务标识的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public BuildVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 创建一条构建并同步触发异步任务。
     *
     * <p>流程：校验项目存在且启用 → 校验运行时与构建模式组合 → DOCKER 必须绑定仓库 →
     * 解析并校验构建机 → 防重（同项目无进行中构建）→ 落 build 记录（排队中）→ 生成 BUILD 任务。
     * 返回对象携带刚创建任务的 ID 与编号，前端据此轮询日志。</p>
     *
     * <p>构建记录与 BUILD 任务必须在同一事务内落库：任务建不起来时构建记录也不能留下，
     * 否则会出现一条永远无法查看执行日志、也无法被调度的孤儿构建（与部署创建同一条规则）。</p>
     *
     * @param dto 构建请求，包含项目 ID 与可选的构建机覆盖
     * @return 创建并触发的构建响应对象
     * @throws DeployException 项目不存在/已停用、组合非法、未配置构建机或存在进行中构建时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public BuildVo createAndTrigger(BuildDto dto) {
        // 目标项目必须存在且启用，构建才有意义。
        Project project = projectMapper.selectById(dto.getProjectId());
        if (project == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标项目不存在");
        }
        if (project.getStatus() == null || project.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标项目已停用，不允许发起构建");
        }
        // 运行时与构建模式组合必须合法：DOCKER 只能配镜像类构建模式，JAR/STATIC 只能配脚本类。
        RuntimeType runtimeType = RuntimeType.fromCode(project.getRuntimeType());
        BuildMode buildMode = BuildMode.fromCode(project.getBuildMode());
        if (runtimeType == null || buildMode == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目的运行时或构建模式非法，请先修正项目配置");
        }
        if (!runtimeType.supports(buildMode)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目运行时 " + runtimeType.getCode() + " 不支持构建模式 " + buildMode.getCode()
                            + "，该运行时的可选构建模式为：" + runtimeType.supportedBuildModeCodes());
        }
        // 镜像类构建必须绑定镜像仓库，否则构建出的镜像无处推送。
        if (runtimeType == RuntimeType.DOCKER && !StringUtils.hasText(project.getRegistryId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "DOCKER 运行时必须绑定镜像仓库（project.registry_id）才能推送构建产物");
        }
        // 解析构建机，三级优先：请求显式指定 > 项目档案绑定 > 系统默认配置。
        // 项目档案这一级让「在哪台机器上构建」成为项目自身属性，新建构建时不必每次再选；
        // 连通性测试也取同一来源，保证「测的机器」与「构建的机器」不会各说各话。
        String buildServerId = StringUtils.hasText(dto.getBuildServerId()) ? dto.getBuildServerId()
                : (StringUtils.hasText(project.getBuildServerId()) ? project.getBuildServerId() : defaultBuildServerId);
        if (!StringUtils.hasText(buildServerId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "未配置构建机：请在项目档案里绑定构建机、在请求中指定 buildServerId，"
                            + "或在系统配置 light-deploy.build.server-id 中设置默认构建机");
        }
        Server buildServer = serverMapper.selectById(buildServerId);
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "指定的构建机不存在");
        }
        if (buildServer.getStatus() == null || buildServer.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "指定的构建机已停用，请更换构建机");
        }
        // 防重：同一项目已有排队中或构建中的记录时，不允许重复触发，避免抢占构建机与产物混乱。
        long inProgress = lambdaQuery()
                .eq(Build::getProjectId, project.getId())
                .in(Build::getStatus, BuildStatus.QUEUED, BuildStatus.BUILDING)
                .count();
        if (inProgress > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "该项目已有进行中的构建，请等待其完成后再发起");
        }

        // 落 build 记录，初始为排队中，构建模式从项目复制由执行器消费。
        Build build = new Build();
        build.setProjectId(project.getId());
        build.setBuildServerId(buildServerId);
        build.setBuildMode(project.getBuildMode());
        build.setStatus(BuildStatus.QUEUED);
        // 人工指定的版本序号：在创建阶段就校验占用情况。若拖到构建成功、镜像已推送、
        // 产物已转存才在版本落库阶段撞唯一键，这条构建会以「成功」结束却拿不到版本记录，
        // 产物永远发不出去，排查成本远高于在入口拒绝。
        if (dto.getVersionSeq() != null) {
            requireVersionSeqAvailable(project.getId(), dto.getVersionSeq());
            build.setVersionSeq(dto.getVersionSeq());
        }
        if (!save(build)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "创建构建记录失败");
        }

        // 生成驱动构建的异步任务，bizId 指向 build 主键；任务强制从排队状态开始。
        TaskDto taskDto = new TaskDto();
        taskDto.setTaskType(TaskType.BUILD.getCode());
        taskDto.setBizId(build.getId());
        TaskVo taskVo = taskService.create(taskDto);

        BuildVo vo = toVo(build);
        vo.setTaskId(taskVo.getId());
        vo.setTaskNo(taskVo.getTaskNo());
        return vo;
    }

    /**
     * 根据路径主键和请求 DTO 修改构建。
     *
     * <p>构建记录多数字段由执行链路维护，可修改范围被严格限制：仅允许在「排队中」状态下重新指定
     * 构建机（buildServerId），用于把构建调度到另一台机器。状态、提交、时间等字段不可通过此接口变更。</p>
     *
     * @param id  待修改的构建主键
     * @param dto 包含可选 buildServerId 的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、非排队中状态或构建机不合法时抛出
     */
    public BuildVo update(String id, BuildDto dto) {
        Build build = getRequiredEntityById(id);
        // 只有排队中的构建允许改构建机：已开始或已结束的构建重定目标无意义且危险。
        if (build.getStatus() != null && build.getStatus() != BuildStatus.QUEUED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "仅排队中的构建可重新指定构建机");
        }
        if (StringUtils.hasText(dto.getBuildServerId())) {
            Server server = serverMapper.selectById(dto.getBuildServerId());
            if (server == null) {
                throw new DeployException(ResponseCode.DATA_NOT_FOUND, "指定的构建机不存在");
            }
            if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
                throw new DeployException(ResponseCode.DATA_CONFLICT, "指定的构建机已停用");
            }
            build.setBuildServerId(dto.getBuildServerId());
            updateById(build);
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除构建记录。
     *
     * <p>只有终态（成功/失败）的构建允许删除，避免删除正在执行或排队中的构建导致任务悬空。</p>
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在、构建未结束时抛出
     */
    public void delete(String id) {
        Build build = getRequiredEntityById(id);
        if (!BuildStatus.isFinal(build.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "仅终态（成功/失败）的构建可删除，请等待构建结束");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除构建记录失败");
        }
    }

    /**
     * 查询某次构建的全部产物。
     *
     * @param buildId 构建主键
     * @return 产物响应列表，按创建时间升序
     */
    public List<BuildArtifactVo> listArtifacts(String buildId) {
        List<BuildArtifact> artifacts = buildArtifactMapper.selectList(new LambdaQueryWrapper<BuildArtifact>()
                .eq(BuildArtifact::getBuildId, buildId)
                .orderByAsc(BuildArtifact::getCreateTime));
        return artifacts.stream().map(this::toArtifactVo).toList();
    }

    // ==================== 构建执行链路回调（由 BuildTaskHandler 调用） ====================

    /**
     * 标记构建进入构建中。
     *
     * <p>执行器开始真正执行 SSH 构建前调用，记录开始时间并切换到构建中状态。</p>
     *
     * @param buildId 构建主键
     */
    public void startBuild(String buildId) {
        Build build = getRequiredEntityById(buildId);
        build.setStatus(BuildStatus.BUILDING);
        build.setStartedAt(LocalDateTime.now());
        updateById(build);
    }

    /**
     * 回填实际构建所用的 Git 提交 ID。
     *
     * <p>构建机克隆后执行 {@code git rev-parse HEAD} 得到，用于发布版本溯源。重复调用以最后一次为准。</p>
     *
     * @param buildId 构建主键
     * @param commit  实际提交 ID
     */
    public void captureCommit(String buildId, String commit) {
        Build build = getRequiredEntityById(buildId);
        if (StringUtils.hasText(commit)) {
            build.setGitCommit(commit);
            updateById(build);
        }
    }

    /**
     * 标记构建成功并登记产物与发布版本。
     *
     * <p>执行器在构建与推送全部成功后调用：把 build 置为成功、写入结束时间与提交 ID，
     * 保存本次产出的全部产物，并创建一条「可发布」的 release_record（见 docs/构建模块.md）。</p>
     *
     * @param buildId   构建主键
     * @param gitCommit 实际提交 ID（可为空，直接发布产物场景）
     * @param artifacts 本次构建产出的全部产物
     * @param release   待创建的发布版本（由执行器组装，含版本号、镜像/产物信息）
     */
    public void completeSuccess(String buildId, String gitCommit,
                                List<BuildArtifact> artifacts, ReleaseRecord release) {
        Build build = getRequiredEntityById(buildId);
        build.setStatus(BuildStatus.SUCCESS);
        build.setFinishedAt(LocalDateTime.now());
        if (StringUtils.hasText(gitCommit)) {
            build.setGitCommit(gitCommit);
        }
        updateById(build);
        // 产物逐条保存，主键与时间由 MyBatis-Plus 维护。
        if (artifacts != null) {
            for (BuildArtifact artifact : artifacts) {
                artifact.setBuildId(buildId);
                buildArtifactMapper.insert(artifact);
            }
        }
        // 发布版本落库，状态固定为可发布。
        if (release != null) {
            release.setProjectId(build.getProjectId());
            // 冗余构建机：发布链路与 build 无外键，JAR/STATIC 分发只能凭本字段定位产物在哪台机器上。
            release.setBuildServerId(build.getBuildServerId());
            if (release.getStatus() == null) {
                release.setStatus(ReleaseStatus.RELEASABLE);
            }
            try {
                releaseRecordMapper.insert(release);
            } catch (DuplicateKeyException e) {
                // 撞唯一键 uk_release_project_version(project_id, version, is_deleted)：
                // 本项目已存在同名版本。走到这里说明序号在「定稿」与「落库」之间被占用
                // （人工指定序号是主要来源）。转成可读的业务异常，避免把原始 SQL 异常抛给页面。
                throw new DeployException(ResponseCode.DATA_CONFLICT,
                        "发布版本 " + release.getVersion() + " 已存在，请更换版本序号后重新构建");
            }
        }
    }

    /**
     * 标记构建失败。
     *
     * <p>执行器在构建任意阶段出错或凭据/仓库不可用时调用：置为失败、写入结束时间与失败原因。
     * 失败原因会做长度截断，且调用方必须保证不含凭据、Token 等敏感信息。</p>
     *
     * @param buildId      构建主键
     * @param errorMessage 失败原因（面向页面展示，禁止含敏感信息）
     */
    public void completeFailure(String buildId, String errorMessage) {
        Build build = getRequiredEntityById(buildId);
        build.setStatus(BuildStatus.FAILED);
        build.setFinishedAt(LocalDateTime.now());
        build.setErrorMessage(truncate(errorMessage));
        updateById(build);
    }

    /**
     * 为一次构建定稿发布版本号。
     *
     * <p>格式 {@code {项目编码}-v{序号}-{提交短码}}，例如 {@code auth-7888-v3-887b603c}。
     * 这个字符串会被<b>三处共用</b>：Docker 镜像 tag、构建机产物持久目录名、以及
     * {@code release_record.version}，所以必须在构建开始前一次性定稿并全程传递，
     * 中途重新生成会导致版本记录与镜像、目录三者对不上。</p>
     *
     * <p>为什么必须有序号：改造前版本号只有「项目编码 + 提交短码」，同一个 commit 重复构建时
     * 三处全部同名——镜像被第二次推送覆盖、产物目录被覆盖、版本记录撞唯一键。
     * 递增序号让每次构建的产物彼此独立，同时让列表能按版本先后排序。</p>
     *
     * @param projectId   项目主键，取号与占用校验的维度
     * @param projectCode 项目编码，用于版本号可读性
     * @param gitCommit   提交 ID；为空（如仅重发已有产物的场景）时退化为时间戳
     * @param manualSeq   人工指定的序号；为 null 或非正数表示自动取号（项目内最大序号 +1）
     * @return 定稿后的序号与版本号
     */
    public ReleaseVersion resolveReleaseVersion(String projectId, String projectCode, String gitCommit, Integer manualSeq) {
        int seq = (manualSeq != null && manualSeq > 0)
                ? manualSeq
                : releaseRecordMapper.selectMaxVersionSeq(projectId) + 1;
        return new ReleaseVersion(seq, projectCode + "-" + VERSION_SEQ_PREFIX + seq + "-" + versionCommitPart(gitCommit));
    }

    /**
     * 取版本号中的提交部分。
     *
     * @param gitCommit 提交 ID，可为空
     * @return 提交短码（前 8 位）；无提交时返回 {@code yyyyMMddHHmmss} 时间戳
     */
    private String versionCommitPart(String gitCommit) {
        if (StringUtils.hasText(gitCommit)) {
            return gitCommit.length() > VERSION_COMMIT_LENGTH
                    ? gitCommit.substring(0, VERSION_COMMIT_LENGTH) : gitCommit;
        }
        return LocalDateTime.now().format(VERSION_TIME_FORMATTER);
    }

    // ==================== 私有辅助 ====================

    /**
     * 校验人工指定的版本序号在该项目内可用。
     *
     * <p>占用判断只看未删除的版本，口径与唯一键 {@code uk_release_project_version} 一致。
     * 这与 {@link ReleaseRecordMapper#selectMaxVersionSeq(String)} 刻意不过滤软删除的做法不同：
     * 自动取号追求序号单调递增、绝不重用，人工指定则只要求不与现有版本相撞。</p>
     *
     * @param projectId 项目主键
     * @param seq       待校验的版本序号
     * @throws DeployException 序号非正数或已被该项目占用时抛出
     */
    private void requireVersionSeqAvailable(String projectId, int seq) {
        if (seq <= 0) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "版本序号必须大于 0");
        }
        Long used = releaseRecordMapper.selectCount(new LambdaQueryWrapper<ReleaseRecord>()
                .eq(ReleaseRecord::getProjectId, projectId)
                .eq(ReleaseRecord::getVersionSeq, seq));
        if (used != null && used > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "版本序号 " + VERSION_SEQ_PREFIX + seq + " 已被该项目占用，请更换序号或留空由平台自动取号");
        }
    }

    /**
     * 查询数据库实体（构建）。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Build getRequiredEntityById(String id) {
        Build entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "构建记录不存在");
        }
        return entity;
    }

    /**
     * 将数据库实体转换为接口响应 VO。
     *
     * <p>补充项目名、构建机名、状态文案与关联任务标识，便于页面直接展示并打开执行日志。
     * 关联任务按 bizId 取最新一条：列表与详情都走本方法，任务标识必须在这里补齐，
     * 否则列表行的 taskId 为空，前端只能提示「未关联异步任务」而点不开日志。</p>
     *
     * @param entity 数据库实体
     * @return 可安全返回给 Controller 的响应对象
     */
    private BuildVo toVo(Build entity) {
        BuildVo vo = new BuildVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setStatusText(BuildStatus.describe(entity.getStatus()));
        // 关联名称按需查询，缺省不报错（可能项目/构建机已删除）。
        if (StringUtils.hasText(entity.getProjectId())) {
            Project project = projectMapper.selectById(entity.getProjectId());
            if (project != null) {
                vo.setProjectName(project.getName());
            }
        }
        if (StringUtils.hasText(entity.getBuildServerId())) {
            Server server = serverMapper.selectById(entity.getBuildServerId());
            if (server != null) {
                vo.setBuildServerName(server.getServerName());
            }
        }
        // 关联出驱动本次构建的异步任务，便于前端直接拉取日志。
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getTaskType, TaskType.BUILD.getCode())
                .eq(Task::getBizId, entity.getId())
                .orderByDesc(Task::getCreateTime)
                .last("LIMIT 1"));
        if (task != null) {
            vo.setTaskId(task.getId());
            vo.setTaskNo(task.getTaskNo());
        }
        return vo;
    }

    /**
     * 将构建产物实体转换为响应 VO。
     *
     * @param entity 产物实体
     * @return 含类型文案的响应对象
     */
    private BuildArtifactVo toArtifactVo(BuildArtifact entity) {
        BuildArtifactVo vo = new BuildArtifactVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setArtifactTypeText(com.lx.deploy.common.enums.build.ArtifactType
                .fromCode(entity.getArtifactType()) == null ? entity.getArtifactType()
                : com.lx.deploy.common.enums.build.ArtifactType.fromCode(entity.getArtifactType()).getMessage());
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Build> buildQueryWrapper(BuildDto condition) {
        LambdaQueryWrapper<Build> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper.orderByDesc(Build::getCreateTime);
        }
        wrapper.eq(StringUtils.hasText(condition.getProjectId()),
                Build::getProjectId, condition.getProjectId());
        wrapper.eq(StringUtils.hasText(condition.getBuildServerId()),
                Build::getBuildServerId, condition.getBuildServerId());
        wrapper.orderByDesc(Build::getCreateTime);
        return wrapper;
    }

    /**
     * 截断失败原因到数据库列允许的长度上限。
     *
     * @param message 原始失败原因
     * @return 截断后的失败原因；入参为空时返回默认文案
     */
    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return "构建失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..." : message;
    }

    /**
     * 一次构建定稿后的发布版本信息。
     *
     * <p>序号与版本号必须成对产生：序号是版本号字符串的组成部分，也是落库时写入
     * {@code release_record.version_seq} 的值。只有序号能参与排序与取号，只有版本号
     * 能对上镜像 tag 与构建机产物目录，两者缺一都无法完整表达「这是第几版、产物在哪」。</p>
     *
     * @param seq     版本序号，项目内递增
     * @param version 发布版本号，形如 {@code auth-7888-v3-887b603c}
     */
    public record ReleaseVersion(int seq, String version) {
    }
}
