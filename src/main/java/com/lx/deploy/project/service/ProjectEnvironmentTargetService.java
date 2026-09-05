package com.lx.deploy.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.enums.EnableStatus;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.project.model.dto.ProjectEnvironmentTargetDto;
import com.lx.deploy.project.model.vo.ProjectEnvironmentTargetVo;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 项目环境部署目标服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。除了基础 CRUD，本服务还集中处理项目、环境、
 * 服务器三方存在性校验，同一项目同一环境同一服务器唯一性，以及主目标互斥规则。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Service
public class ProjectEnvironmentTargetService
        extends ServiceImpl<ProjectEnvironmentTargetMapper, ProjectEnvironmentTarget> {

    /** 用于校验 JSON 配置字段格式的解析器，线程安全可静态复用。 */
    private static final ObjectMapper JSON_PARSER = new ObjectMapper();

    /** 项目 Mapper，用于校验项目存在性。 */
    private final ProjectMapper projectMapper;

    /** 环境 Mapper，用于校验环境存在性。 */
    private final EnvironmentMapper environmentMapper;

    /** 服务器 Mapper，用于校验目标服务器存在性和启用状态。 */
    private final ServerMapper serverMapper;

    /**
     * 注入跨模块 Mapper。
     *
     * @param projectMapper 项目 Mapper
     * @param environmentMapper 环境 Mapper
     * @param serverMapper 服务器 Mapper
     */
    public ProjectEnvironmentTargetService(ProjectMapper projectMapper,
                                           EnvironmentMapper environmentMapper,
                                           ServerMapper serverMapper) {
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.serverMapper = serverMapper;
    }

    /**
     * 根据 DTO 中的非空条件分页查询项目环境部署目标。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<ProjectEnvironmentTargetVo> pageByCondition(PageQuery pageQuery,
                                                                   ProjectEnvironmentTargetDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<ProjectEnvironmentTarget> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<ProjectEnvironmentTargetVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<ProjectEnvironmentTargetVo> listByCondition(ProjectEnvironmentTargetDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回项目环境部署目标响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public ProjectEnvironmentTargetVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条项目环境部署目标。
     *
     * <p>新增前校验项目、环境和服务器存在，目标服务器必须处于启用状态，
     * 同一项目同一环境不允许重复绑定同一服务器。设置为新的主目标时，
     * 同一项目环境下的旧主目标会被自动取消，保证主目标唯一。</p>
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、关联数据不存在、绑定重复或保存失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectEnvironmentTargetVo create(ProjectEnvironmentTargetDto dto) {
        // 新增前执行完整的业务规则校验，不存在排除自身的问题。
        validateBusinessRule(dto, null);
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        ProjectEnvironmentTarget entity = toEntity(dto);
        applyDefaultValue(entity);
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增项目环境部署目标失败");
        }
        // 当前目标为主目标时，取消同项目同环境下其他目标的主目标标记。
        demoteOtherPrimary(entity);
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改项目环境部署目标。
     *
     * <p>修改时同样执行业务规则校验，服务器绑定唯一性检查会排除当前记录自身。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、参数不合法、绑定重复或修改失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectEnvironmentTargetVo update(String id, ProjectEnvironmentTargetDto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        ProjectEnvironmentTarget entity = getRequiredEntityById(id);
        // 修改时绑定唯一性检查排除当前记录。
        validateBusinessRule(dto, id);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改项目环境部署目标失败");
        }
        // 修改为主目标时，取消同项目同环境下其他目标的主目标标记。
        demoteOtherPrimary(entity);
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除项目环境部署目标。
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除项目环境部署目标失败");
        }
    }

    /**
     * 校验项目环境部署目标业务规则。
     *
     * <p>校验内容包括：项目、环境、服务器三方 ID 必填且真实存在，目标服务器必须启用，
     * 同一项目同一环境同一服务器只能绑定一次，主目标标记和发布顺序取值合法，
     * 状态必须为受支持的枚举值，差异化运行配置必须是合法 JSON。</p>
     *
     * @param dto 请求参数
     * @param excludeId 修改场景下排除的当前记录主键；新增时为 null
     * @throws DeployException 任一规则不满足时抛出，异常消息说明具体原因
     */
    private void validateBusinessRule(ProjectEnvironmentTargetDto dto, String excludeId) {
        // 项目 ID 必填且必须真实存在。
        if (!StringUtils.hasText(dto.getProjectId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "项目ID不能为空");
        }
        if (projectMapper.selectById(dto.getProjectId()) == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目不存在");
        }
        // 环境 ID 必填且必须真实存在。
        if (!StringUtils.hasText(dto.getEnvironmentId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "环境ID不能为空");
        }
        if (environmentMapper.selectById(dto.getEnvironmentId()) == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "环境不存在");
        }
        // 服务器 ID 必填且必须真实存在并处于启用状态。
        if (!StringUtils.hasText(dto.getServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "目标服务器ID不能为空");
        }
        Server server = serverMapper.selectById(dto.getServerId());
        if (server == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标服务器不存在");
        }
        if (server.getStatus() == null || server.getStatus() != 1) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标服务器已停用，不允许绑定");
        }
        // 主目标标记传入时只能是 0 或 1。
        if (dto.getIsPrimary() != null && dto.getIsPrimary() != 0 && dto.getIsPrimary() != 1) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "主目标标记只能为 0 或 1");
        }
        // 多目标发布顺序传入时必须大于等于 1。
        if (dto.getDeployOrder() != null && dto.getDeployOrder() < 1) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "多目标发布顺序必须大于等于 1");
        }
        // 状态可选，传入时必须是受支持的枚举值。
        if (dto.getStatus() != null && EnableStatus.fromCode(dto.getStatus()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "状态必须为 ENABLED 或 DISABLED");
        }
        // 差异化运行配置传入时必须是合法 JSON。
        if (StringUtils.hasText(dto.getRuntimeConfigJson())) {
            try {
                JSON_PARSER.readTree(dto.getRuntimeConfigJson());
            } catch (Exception e) {
                throw new DeployException(ResponseCode.VALIDATION_ERROR, "目标差异化运行配置不是合法的 JSON 格式");
            }
        }
        // 同一项目同一环境同一服务器唯一，修改场景排除当前记录自身。
        LambdaQueryWrapper<ProjectEnvironmentTarget> bindWrapper =
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getProjectId, dto.getProjectId())
                        .eq(ProjectEnvironmentTarget::getEnvironmentId, dto.getEnvironmentId())
                        .eq(ProjectEnvironmentTarget::getServerId, dto.getServerId());
        if (excludeId != null) {
            bindWrapper.ne(ProjectEnvironmentTarget::getId, excludeId);
        }
        if (count(bindWrapper) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "该服务器已绑定到当前项目环境");
        }
    }

    /**
     * 为新增实体填充默认值。
     *
     * @param entity 待填充默认值的实体
     */
    private void applyDefaultValue(ProjectEnvironmentTarget entity) {
        // 未指定主目标标记时默认为普通目标。
        if (entity.getIsPrimary() == null) {
            entity.setIsPrimary((byte) 0);
        }
        // 未指定发布顺序时默认为 1。
        if (entity.getDeployOrder() == null) {
            entity.setDeployOrder(1);
        }
        // 未指定状态时默认启用。
        if (!StringUtils.hasText(entity.getStatus())) {
            entity.setStatus(EnableStatus.ENABLED.getCode());
        }
    }

    /**
     * 取消同项目同环境下其他目标的主目标标记。
     *
     * <p>只有当前目标为主目标时才执行，保证任一项目环境组合下最多只有一个主目标。
     * 该操作与保存或修改放在同一事务中，避免出现多个主目标或没有主目标的中间状态。</p>
     *
     * @param entity 当前保存或修改的目标实体
     */
    private void demoteOtherPrimary(ProjectEnvironmentTarget entity) {
        // 非主目标无需处理。
        if (entity.getIsPrimary() == null || entity.getIsPrimary() != 1) {
            return;
        }
        // 将同项目同环境下其他目标的主目标标记清零，排除当前记录自身。
        update(new LambdaUpdateWrapper<ProjectEnvironmentTarget>()
                .eq(ProjectEnvironmentTarget::getProjectId, entity.getProjectId())
                .eq(ProjectEnvironmentTarget::getEnvironmentId, entity.getEnvironmentId())
                .eq(ProjectEnvironmentTarget::getIsPrimary, 1)
                .ne(ProjectEnvironmentTarget::getId, entity.getId())
                .set(ProjectEnvironmentTarget::getIsPrimary, 0));
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private ProjectEnvironmentTarget getRequiredEntityById(String id) {
        ProjectEnvironmentTarget entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "项目环境部署目标不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private ProjectEnvironmentTarget toEntity(ProjectEnvironmentTargetDto dto) {
        ProjectEnvironmentTarget entity = new ProjectEnvironmentTarget();
        BeanUtils.copyProperties(dto, entity);
        return entity;
    }

    /**
     * 将数据库实体转换为接口响应 VO。
     *
     * <p>VO 类型本身不声明敏感字段，因此属性复制不会把密码、密文、Token 或私钥返回给前端。</p>
     *
     * @param entity 数据库实体
     * @return 可安全返回给 Controller 的响应对象
     */
    private ProjectEnvironmentTargetVo toVo(ProjectEnvironmentTarget entity) {
        ProjectEnvironmentTargetVo vo = new ProjectEnvironmentTargetVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * <p>差异化运行配置内容较长且不适合作为过滤条件，因此不参与查询。
     * 结果默认按发布顺序升序排列，同一项目环境下的目标按发布顺序展示。</p>
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<ProjectEnvironmentTarget> buildQueryWrapper(ProjectEnvironmentTargetDto condition) {
        LambdaQueryWrapper<ProjectEnvironmentTarget> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 项目ID：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getProjectId()),
                ProjectEnvironmentTarget::getProjectId, condition.getProjectId());
        // 环境ID：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getEnvironmentId()),
                ProjectEnvironmentTarget::getEnvironmentId, condition.getEnvironmentId());
        // 目标服务器ID：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getServerId()),
                ProjectEnvironmentTarget::getServerId, condition.getServerId());
        // 是否为主目标：字段非空时使用等值条件查询。
        wrapper.eq(condition.getIsPrimary() != null,
                ProjectEnvironmentTarget::getIsPrimary, condition.getIsPrimary());
        // 状态：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getStatus()),
                ProjectEnvironmentTarget::getStatus, condition.getStatus());
        // 先按创建时间倒序分组，再按发布顺序升序排列。
        wrapper.orderByDesc(ProjectEnvironmentTarget::getCreateTime);
        wrapper.orderByAsc(ProjectEnvironmentTarget::getDeployOrder);
        return wrapper;
    }
}
