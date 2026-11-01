package com.lx.deploy.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.common.util.TimeRangeSupport;
import com.lx.deploy.common.enums.project.BuildMode;
import com.lx.deploy.common.enums.project.RuntimeType;
import com.lx.deploy.common.enums.project.SourceType;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.enums.credential.CredentialType;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.project.model.dto.ProjectDto;
import com.lx.deploy.project.model.vo.ProjectVo;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import com.lx.deploy.task.service.TaskService;
import com.lx.deploy.common.security.CurrentUser;
import com.lx.deploy.common.security.LoginUser;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 部署项目服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。除了基础 CRUD，本服务还集中处理项目编码唯一性、
 * 枚举取值合法性、运行时与构建模式的组合约束、Git 来源必填地址、凭据/镜像仓库/构建机引用校验、
 * JSON 配置格式等业务校验，并提供项目连通性测试的任务提交入口。</p>
 *
 * <p>跨模块校验统一注入对方 Mapper（credential / registry），不注入对方 Service，
 * 避免 Service 之间形成循环依赖。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Service
public class ProjectService extends ServiceImpl<ProjectMapper, Project> {

    /** 项目编码只允许字母、数字、下划线和中划线，长度 2 到 64 位。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{2,64}$");

    /** 用于校验 JSON 配置字段格式的解析器，线程安全可静态复用。 */
    private static final ObjectMapper JSON_PARSER = new ObjectMapper();

    /** 项目环境部署目标 Mapper，用于删除项目前检查引用关系，避免 Service 之间循环依赖。 */
    private final ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper;

    /** 凭据 Mapper，用于校验 project.git_credential_id 指向的凭据存在且可用于 Git 拉取。 */
    private final CredentialMapper credentialMapper;

    /** 镜像仓库 Mapper，用于校验 project.registry_id 指向的仓库存在且处于启用状态。 */
    private final RegistryMapper registryMapper;

    /** 服务器 Mapper，用于校验 project.build_server_id 指向的构建机存在且启用，并回填构建机名称。 */
    private final ServerMapper serverMapper;

    /**
     * 任务服务。
     *
     * <p>连通性测试本质是一次异步任务，项目模块只负责提交任务，不直接执行远程操作，
     * 因此依赖任务模块的服务而不是 SSH 执行层（AGENTS.md §12：Controller 不写执行逻辑）。</p>
     */
    private final TaskService taskService;

    /**
     * 注入本模块与跨模块校验所需的 Mapper。
     *
     * @param projectEnvironmentTargetMapper 项目环境部署目标 Mapper
     * @param credentialMapper 凭据 Mapper
     * @param registryMapper 镜像仓库 Mapper
     * @param serverMapper 服务器 Mapper
     * @param taskService 任务服务
     */
    public ProjectService(ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                          CredentialMapper credentialMapper,
                          RegistryMapper registryMapper,
                          ServerMapper serverMapper,
                          TaskService taskService) {
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.credentialMapper = credentialMapper;
        this.registryMapper = registryMapper;
        this.serverMapper = serverMapper;
        this.taskService = taskService;
    }

    /**
     * 提交一次项目连通性测试任务。
     *
     * <p>测试在项目的构建机上执行，校验代码仓库可否拉取、镜像仓库可否登录，避免这些配置问题
     * 拖到真正构建时才以「构建失败」的形式暴露。接口只创建任务并立即返回，
     * 真正的远程校验由 {@code ProjectConnectTestTaskHandler} 异步完成。</p>
     *
     * <p>提交前必须拦住两类提交了也白跑的情况：项目未绑定构建机（没有执行目标）、
     * 同一项目已有未完成的测试（连点会重复占用构建机）。</p>
     *
     * @param id 目标项目主键
     * @return 新建的异步任务（状态为 0 排队中）
     * @throws DeployException 项目不存在、已停用、未绑定构建机或已有未完成任务时抛出
     */
    public TaskVo submitConnectivityTest(String id) {
        Project entity = getRequiredEntityById(id);
        // 停用项目不参与构建与发布，测试属于构建前预检，同样拒绝执行。
        if (entity.getStatus() == null || entity.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "项目已停用，不允许执行连通性测试");
        }
        if (!StringUtils.hasText(entity.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目未绑定构建机，无法执行连通性测试；请先在编辑页选择构建机");
        }
        // 同一项目已有排队中或执行中的测试时拒绝重复提交，避免连点重复占用构建机。
        if (taskService.hasUnfinishedTask(TaskType.PROJECT_TEST.getCode(), id)) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "该项目已有未完成的连通性测试任务，请等待其结束");
        }
        // 任务类型与关联业务主键由服务端决定，不接受调用方自定义，避免任务指向错误目标。
        TaskDto dto = new TaskDto();
        dto.setTaskType(TaskType.PROJECT_TEST.getCode());
        dto.setBizId(id);
        return taskService.create(dto);
    }

    /**
     * 根据 DTO 中的非空条件分页查询部署项目。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<ProjectVo> pageByCondition(PageQuery pageQuery, ProjectDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<Project> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<ProjectVo> records = toVoList(page.getRecords());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<ProjectVo> listByCondition(ProjectDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return toVoList(list(buildQueryWrapper(condition)));
    }

    /**
     * 根据主键查询并返回部署项目响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public ProjectVo getRequiredById(String id) {
        return toVoList(List.of(getRequiredEntityById(id))).get(0);
    }

    /**
     * 根据请求 DTO 新增一条部署项目。
     *
     * <p>新增前校验编码格式、编码唯一性、运行时和构建模式等枚举取值，
     * 并为代码来源、构建模式和状态填充默认值。Git 来源项目必须提供仓库地址。</p>
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、编码重复或保存失败时抛出
     */
    public ProjectVo create(ProjectDto dto) {
        // 前端文本域未填写时会提交空串，先归一为 null，避免写入 MySQL JSON 列时报格式错误。
        normalizeJsonConfig(dto);
        // 新增前执行完整的业务规则校验，不存在排除自身的问题。
        validateBusinessRule(dto, null);
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        Project entity = toEntity(dto);
        applyDefaultValue(entity);
        // 过渡期：记录创建人，并默认将项目负责人指派为创建者（owner_id 为空时）。
        // 后续接入统一权限中心后，createdBy/ownerId 改为外部用户 ID。
        LoginUser creator = CurrentUser.get();
        if (creator != null) {
            entity.setCreatedBy(creator.username());
            entity.setUpdatedBy(creator.username());
            if (!StringUtils.hasText(entity.getOwnerId())) {
                entity.setOwnerId(creator.id());
                entity.setOwnerName(creator.username());
            }
        }
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增部署项目失败");
        }
        return toVoList(List.of(entity)).get(0);
    }

    /**
     * 根据路径主键和请求 DTO 修改部署项目。
     *
     * <p>修改时同样执行业务规则校验，编码唯一性检查会排除当前记录自身，
     * 避免项目修改其他字段时被误判为编码重复。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、参数不合法、编码重复或修改失败时抛出
     */
    public ProjectVo update(String id, ProjectDto dto) {
        // 修改同样需要归一化：空白值表示「清空该配置」，统一落库为 NULL。
        normalizeJsonConfig(dto);
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        Project entity = getRequiredEntityById(id);
        // 修改时编码唯一性检查排除当前记录。
        validateBusinessRule(dto, id);
        // 项目负责人为可选改派字段：先保留原值，再整体拷贝 DTO，最后仅在请求显式携带 ownerId 时覆盖，
        // 避免过渡期旧前端未传 ownerId 时把已有负责人清空。
        String originalOwnerId = entity.getOwnerId();
        String originalOwnerName = entity.getOwnerName();
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!StringUtils.hasText(dto.getOwnerId())) {
            entity.setOwnerId(originalOwnerId);
            entity.setOwnerName(originalOwnerName);
        }
        LoginUser operator = CurrentUser.get();
        if (operator != null) {
            entity.setUpdatedBy(operator.username());
        }
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改部署项目失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除部署项目。
     *
     * <p>删除前检查项目是否仍被项目环境部署目标引用；存在引用时拒绝删除，
     * 避免部署目标指向不存在的项目。应先删除或迁移全部部署目标后再删除项目。</p>
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在、仍被部署目标引用或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        // 统计仍引用该项目的部署目标数量，存在引用时抛出数据冲突异常。
        Long targetCount = projectEnvironmentTargetMapper.selectCount(
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getProjectId, id));
        if (targetCount != null && targetCount > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "项目仍被部署目标引用，请先删除或迁移部署目标");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除部署项目失败");
        }
    }

    /**
     * 校验项目业务规则。
     *
     * <p>校验内容包括：编码和名称必填、编码格式、编码唯一性、运行时/来源/构建模式/状态枚举取值、
     * Git 来源必须提供仓库地址、保留版本数量下限以及三个 JSON 配置字段的格式。</p>
     *
     * @param dto 请求参数
     * @param excludeId 修改场景下排除的当前记录主键；新增时为 null
     * @throws DeployException 任一规则不满足时抛出，异常消息说明具体原因
     */
    private void validateBusinessRule(ProjectDto dto, String excludeId) {
        // 项目编码必填且只允许字母、数字、下划线和中划线。
        if (!StringUtils.hasText(dto.getCode())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目编码不能为空");
        }
        if (!CODE_PATTERN.matcher(dto.getCode()).matches()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目编码只允许字母、数字、下划线和中划线，长度 2 到 64 位");
        }
        // 项目名称必填。
        if (!StringUtils.hasText(dto.getName())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目名称不能为空");
        }
        // 运行时类型必填且必须是受支持的枚举值。
        if (RuntimeType.fromCode(dto.getRuntimeType()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "运行时类型必须为 DOCKER、JAR 或 STATIC");
        }
        // 代码来源可选，传入时必须是受支持的枚举值。
        if (dto.getSourceType() != null && SourceType.fromCode(dto.getSourceType()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "代码来源必须为 GIT 或 UPLOAD");
        }
        // 构建模式可选，传入时必须是受支持的枚举值。
        if (dto.getBuildMode() != null && BuildMode.fromCode(dto.getBuildMode()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "构建模式必须为 PROJECT_DOCKERFILE、LAYERED_DOCKER、SCRIPT_DEPLOY 或 ARTIFACT_ONLY");
        }
        // 运行时与构建模式必须匹配：Docker 镜像类构建模式只能配 DOCKER 运行时，
        // 脚本 / 仅发布产物只能配 JAR 或 STATIC 运行时（AGENTS.md §23.4）。
        // 规则定义在 RuntimeType 枚举里，避免组合判断散落在各处。
        RuntimeType runtimeType = RuntimeType.fromCode(dto.getRuntimeType());
        BuildMode buildMode = BuildMode.fromCode(dto.getBuildMode());
        if (runtimeType != null && buildMode != null && !runtimeType.supports(buildMode)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "运行时 " + runtimeType.getCode() + " 不支持构建模式 " + buildMode.getCode()
                            + "，该运行时的可选构建模式为：" + runtimeType.supportedBuildModeCodes());
        }
        // 镜像仓库只允许 DOCKER 运行时绑定：脚本部署与仅发布产物不产出镜像，没有推送目标。
        if (StringUtils.hasText(dto.getRegistryId()) && runtimeType != RuntimeType.DOCKER) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "只有 DOCKER 运行时的项目可以绑定镜像仓库，当前运行时为 " + dto.getRuntimeType());
        }
        // 状态可选，传入时必须是合法数字编码（0 停用 / 1 启用）。
        if (dto.getStatus() != null && !EnableStatus.isValid(dto.getStatus())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "状态必须为 0（停用）或 1（启用）");
        }
        // Git 来源必须提供仓库地址，上传来源不强制。
        if (SourceType.GIT.getCode().equals(dto.getSourceType()) && !StringUtils.hasText(dto.getGitUrl())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "Git 来源项目必须填写 Git 仓库地址");
        }
        // Git 凭据引用校验：公开仓库可以不填，填写时必须是可用于 Git 拉取的凭据。
        validateGitCredentialReference(dto.getGitCredentialId());
        // 镜像仓库引用校验：仓库必须存在且处于启用状态。
        validateRegistryReference(dto.getRegistryId());
        // 构建机引用校验：服务器必须存在且处于启用状态。
        validateBuildServerReference(dto.getBuildServerId());
        // 保留发布版本数量传入时必须大于等于 1。
        if (dto.getRetainReleaseCount() != null && dto.getRetainReleaseCount() < 1) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "保留发布版本数量必须大于等于 1");
        }
        // 三个 JSON 配置字段传入时必须是合法 JSON，尽早拦截格式错误。
        validateJsonFormat("构建配置", dto.getBuildConfigJson());
        validateJsonFormat("运行配置", dto.getRuntimeConfigJson());
        validateJsonFormat("健康检查配置", dto.getHealthConfigJson());
        // 编码唯一性检查，修改场景排除当前记录自身。
        LambdaQueryWrapper<Project> codeWrapper = new LambdaQueryWrapper<Project>()
                .eq(Project::getCode, dto.getCode());
        if (excludeId != null) {
            codeWrapper.ne(Project::getId, excludeId);
        }
        if (count(codeWrapper) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "项目编码已存在：" + dto.getCode());
        }
    }

    /**
     * 为新增实体填充默认值。
     *
     * <p>数据库层虽然定义了默认值，但显式填充保证新增后返回的 VO 与库中数据一致。</p>
     *
     * @param entity 待填充默认值的实体
     */
    private void applyDefaultValue(Project entity) {
        // 未指定代码来源时默认从 Git 仓库拉取。
        if (!StringUtils.hasText(entity.getSourceType())) {
            entity.setSourceType(SourceType.GIT.getCode());
        }
        // 未指定构建模式时默认使用项目仓库自带的 Dockerfile。
        // Docker 镜像类默认改成 PROJECT_DOCKERFILE：平台不再提供 Dockerfile 模板，
        // 把「必须项目提供 Dockerfile」作为默认值，比默认一个已下线的模式更不容易走弯路。
        if (!StringUtils.hasText(entity.getBuildMode())) {
            entity.setBuildMode(BuildMode.PROJECT_DOCKERFILE.getCode());
        }
        // 未指定状态时默认启用（1）。
        if (entity.getStatus() == null) {
            entity.setStatus(EnableStatus.ENABLED);
        }
        // 未指定保留版本数量时默认保留 5 个历史版本。
        if (entity.getRetainReleaseCount() == null) {
            entity.setRetainReleaseCount(5);
        }
    }

    /**
     * 校验 Git 凭据引用。
     *
     * <p>两件必须同时成立的事：凭据存在（否则拉取代码时解密会失败）、凭据类型可用于 Git
     * （GIT_TOKEN 用于 HTTPS 仓库，SSH_KEY 用于 SSH 仓库）。镜像仓库凭据不允许绑定到项目。</p>
     *
     * @param credentialId 待校验的凭据主键，允许为 null（公开仓库）
     * @throws DeployException 凭据不存在或类型不符时抛出
     */
    private void validateGitCredentialReference(String credentialId) {
        if (!StringUtils.hasText(credentialId)) {
            return;
        }
        Credential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "绑定的 Git 凭据不存在，请重新选择");
        }
        CredentialType credentialType = CredentialType.fromCode(credential.getType());
        if (credentialType == null || !credentialType.isGitType()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "项目只能绑定类型为 GIT_TOKEN 或 SSH_KEY 的凭据，当前凭据类型为 " + credential.getType());
        }
    }

    /**
     * 校验镜像仓库引用。
     *
     * <p>仓库必须存在且处于启用状态：停用的仓库意味着运维已经不打算再推送镜像，
     * 此时允许新项目绑定会推迟到构建阶段才失败。</p>
     *
     * @param registryId 待校验的镜像仓库主键，允许为 null（不绑定仓库）
     * @throws DeployException 仓库不存在或已停用时抛出
     */
    private void validateRegistryReference(String registryId) {
        if (!StringUtils.hasText(registryId)) {
            return;
        }
        Registry registry = registryMapper.selectById(registryId);
        if (registry == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "绑定的镜像仓库不存在，请重新选择");
        }
        if (registry.getStatus() == null || registry.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "绑定的镜像仓库已停用，请重新选择或先启用该仓库");
        }
    }

    /**
     * 校验构建机引用。
     *
     * <p>构建机必须存在且处于启用状态：停用意味着运维已经不打算再用这台机器构建，
     * 此时允许绑定会把问题推迟到构建或连通性测试时才暴露。</p>
     *
     * @param buildServerId 待校验的服务器主键，允许为 null（回落到系统默认构建机）
     * @throws DeployException 服务器不存在或已停用时抛出
     */
    private void validateBuildServerReference(String buildServerId) {
        if (!StringUtils.hasText(buildServerId)) {
            return;
        }
        Server server = serverMapper.selectById(buildServerId);
        if (server == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "绑定的构建机不存在，请重新选择");
        }
        if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "绑定的构建机已停用，请重新选择或先启用该服务器");
        }
    }

    /**
     * 校验字符串是合法 JSON。
     *
     * @param fieldName 配置字段中文名，用于异常消息
     * @param json 待校验的 JSON 字符串；为空时跳过校验
     * @throws DeployException 内容不是合法 JSON 时抛出
     */
    private void validateJsonFormat(String fieldName, String json) {
        if (!StringUtils.hasText(json)) {
            return;
        }
        try {
            JSON_PARSER.readTree(json);
        } catch (Exception e) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, fieldName + "不是合法的 JSON 格式");
        }
    }

    /**
     * 归一化请求 DTO 中的三段 JSON 配置字段。
     *
     * <p>数据库对应的三列是 MySQL `JSON` 类型，空字符串不是合法 JSON，直接写入会被数据库拒绝；
     * 前端表单的文本域在未填写时提交的是空串，因此统一在业务校验前把空白值归一为 `null`，
     * 语义为「该项目未配置这一段」。</p>
     *
     * @param dto 请求参数；方法内原地修改，调用方无需接收返回值
     */
    private void normalizeJsonConfig(ProjectDto dto) {
        // 构建配置：仅含空白字符时视为未配置。
        dto.setBuildConfigJson(normalizeJsonText(dto.getBuildConfigJson()));
        // 运行配置：仅含空白字符时视为未配置。
        dto.setRuntimeConfigJson(normalizeJsonText(dto.getRuntimeConfigJson()));
        // 健康检查配置：仅含空白字符时视为未配置。
        dto.setHealthConfigJson(normalizeJsonText(dto.getHealthConfigJson()));
    }

    /**
     * 将 JSON 配置文本归一化：有效内容去掉首尾空白，空白内容转为 `null`。
     *
     * @param json 请求中的原始 JSON 文本，允许为 null
     * @return 去掉首尾空白后的 JSON 文本；入参为 null 或纯空白时返回 null
     */
    private String normalizeJsonText(String json) {
        return StringUtils.hasText(json) ? json.trim() : null;
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Project getRequiredEntityById(String id) {
        Project entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署项目不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private Project toEntity(ProjectDto dto) {
        Project entity = new Project();
        BeanUtils.copyProperties(dto, entity);
        return entity;
    }

    /**
     * 将数据库实体批量转换为接口响应 VO，并回填展示用字段。
     *
     * <p>VO 类型本身不声明敏感字段，因此属性复制不会把密码、密文、Token 或私钥返回给前端。</p>
     *
     * <p>构建机名称必须在这里**批量**回填而不是逐条查询：列表页一页 20 行时逐条查 server
     * 就是 20 次额外查询（N+1）。收集全部 buildServerId 后一次 selectByIds 即可。</p>
     *
     * @param entities 数据库实体列表，允许为空
     * @return 可安全返回给 Controller 的响应对象列表
     */
    private List<ProjectVo> toVoList(List<Project> entities) {
        List<ProjectVo> vos = entities.stream().map(entity -> {
            ProjectVo vo = new ProjectVo();
            BeanUtils.copyProperties(entity, vo);
            return vo;
        }).toList();
        fillBuildServerName(vos);
        return vos;
    }

    /**
     * 按 buildServerId 批量回填构建机名称。
     *
     * <p>名称只是展示字段，查不到（服务器已删除、数据被手工改脏）时留空即可，
     * 不抛异常、不阻塞列表返回。</p>
     *
     * @param vos 已转换的 VO 列表；方法内原地回填
     */
    private void fillBuildServerName(List<ProjectVo> vos) {
        Set<String> serverIds = vos.stream()
                .map(ProjectVo::getBuildServerId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (serverIds.isEmpty()) {
            return;
        }
        Map<String, String> nameById = serverMapper.selectByIds(serverIds).stream()
                .collect(Collectors.toMap(Server::getId, Server::getServerName, (left, right) -> left));
        vos.forEach(vo -> vo.setBuildServerName(nameById.get(vo.getBuildServerId())));
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * <p>JSON 配置字段内容较长且不适合作为过滤条件，因此不参与查询。</p>
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Project> buildQueryWrapper(ProjectDto condition) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 项目编码：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getCode()),
                Project::getCode, condition.getCode());
        // 项目名称：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getName()),
                Project::getName, condition.getName());
        // 运行时类型：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getRuntimeType()),
                Project::getRuntimeType, condition.getRuntimeType());
        // 代码来源：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getSourceType()),
                Project::getSourceType, condition.getSourceType());
        // Git 分支：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getGitBranch()),
                Project::getGitBranch, condition.getGitBranch());
        // 构建模式：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getBuildMode()),
                Project::getBuildMode, condition.getBuildMode());
        // 构建机：非空字符串使用等值条件查询，用于筛出绑定到某台机器（或未绑定）的项目。
        wrapper.eq(StringUtils.hasText(condition.getBuildServerId()),
                Project::getBuildServerId, condition.getBuildServerId());
        // 状态：传入数字编码时使用等值条件查询。
        wrapper.eq(condition.getStatus() != null,
                Project::getStatus, condition.getStatus());
        // 默认按创建时间倒序返回，优先展示最近新增的数据。
        // 创建时间区间：闭区间，开始晚于截止由 TimeRangeSupport 直接判参数错误。
        TimeRangeSupport.apply(wrapper, Project::getCreateTime, condition);
        wrapper.orderByDesc(Project::getCreateTime);
        return wrapper;
    }
}
