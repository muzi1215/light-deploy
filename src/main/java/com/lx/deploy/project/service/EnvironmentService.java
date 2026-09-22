package com.lx.deploy.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.enums.project.DeployStrategy;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectEnvironmentTargetMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.ProjectEnvironmentTarget;
import com.lx.deploy.project.model.dto.EnvironmentDto;
import com.lx.deploy.project.model.vo.EnvironmentVo;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 部署环境服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。除了基础 CRUD，本服务还集中处理环境编码格式与
 * 唯一性、部署策略枚举取值、批次大小下限和默认服务器存在性校验。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Service
public class EnvironmentService extends ServiceImpl<EnvironmentMapper, Environment> {

    /** 环境编码约定为小写英文标识（如 dev/test/prod），长度 2 到 32 位。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    /** 项目环境部署目标 Mapper，用于删除环境前检查引用关系，避免 Service 之间循环依赖。 */
    private final ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper;

    /** 服务器 Mapper，用于校验默认目标服务器存在。 */
    private final ServerMapper serverMapper;

    /**
     * 注入跨模块 Mapper。
     *
     * @param projectEnvironmentTargetMapper 项目环境部署目标 Mapper
     * @param serverMapper 服务器 Mapper
     */
    public EnvironmentService(ProjectEnvironmentTargetMapper projectEnvironmentTargetMapper,
                              ServerMapper serverMapper) {
        this.projectEnvironmentTargetMapper = projectEnvironmentTargetMapper;
        this.serverMapper = serverMapper;
    }

    /**
     * 根据 DTO 中的非空条件分页查询部署环境。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<EnvironmentVo> pageByCondition(PageQuery pageQuery, EnvironmentDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<Environment> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<EnvironmentVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<EnvironmentVo> listByCondition(EnvironmentDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回部署环境响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public EnvironmentVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条部署环境。
     *
     * <p>新增前校验编码格式与唯一性、部署策略取值、批次大小和默认服务器存在性，
     * 并为部署策略和 AIops 联动填充默认值。</p>
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、编码重复或保存失败时抛出
     */
    public EnvironmentVo create(EnvironmentDto dto) {
        // 新增前执行完整的业务规则校验，不存在排除自身的问题。
        validateBusinessRule(dto, null);
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        Environment entity = toEntity(dto);
        applyDefaultValue(entity);
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增部署环境失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改部署环境。
     *
     * <p>修改时同样执行业务规则校验，编码唯一性检查会排除当前记录自身。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、参数不合法、编码重复或修改失败时抛出
     */
    public EnvironmentVo update(String id, EnvironmentDto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        Environment entity = getRequiredEntityById(id);
        // 修改时编码唯一性检查排除当前记录。
        validateBusinessRule(dto, id);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改部署环境失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除部署环境。
     *
     * <p>删除前检查环境是否仍被项目环境部署目标引用；存在引用时拒绝删除。
     * 应先删除或迁移该环境下的全部部署目标后再删除环境。</p>
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在、仍被部署目标引用或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        // 统计仍引用该环境的部署目标数量，存在引用时抛出数据冲突异常。
        Long targetCount = projectEnvironmentTargetMapper.selectCount(
                new LambdaQueryWrapper<ProjectEnvironmentTarget>()
                        .eq(ProjectEnvironmentTarget::getEnvironmentId, id));
        if (targetCount != null && targetCount > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "环境仍被部署目标引用，请先删除或迁移部署目标");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除部署环境失败");
        }
    }

    /**
     * 校验部署环境业务规则。
     *
     * <p>校验内容包括：编码和名称必填、编码格式与唯一性、部署策略枚举取值、
     * 滚动批次大小下限、AIops 联动开关取值以及默认目标服务器存在性。</p>
     *
     * @param dto 请求参数
     * @param excludeId 修改场景下排除的当前记录主键；新增时为 null
     * @throws DeployException 任一规则不满足时抛出，异常消息说明具体原因
     */
    private void validateBusinessRule(EnvironmentDto dto, String excludeId) {
        // 环境编码必填且约定为小写英文标识（如 dev/test/prod）。
        if (!StringUtils.hasText(dto.getCode())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "环境编码不能为空");
        }
        if (!CODE_PATTERN.matcher(dto.getCode()).matches()) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "环境编码必须以小写字母开头，只包含小写字母、数字和中划线，长度 2 到 32 位");
        }
        // 环境名称必填。
        if (!StringUtils.hasText(dto.getName())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "环境名称不能为空");
        }
        // 部署策略可选，传入时必须是受支持的枚举值。
        if (dto.getDeployStrategy() != null && DeployStrategy.fromCode(dto.getDeployStrategy()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "部署策略必须为 SINGLE_TARGET、ALL_AT_ONCE 或 ROLLING");
        }
        // 滚动发布批次大小传入时必须大于等于 1。
        if (dto.getBatchSize() != null && dto.getBatchSize() < 1) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "滚动发布批次大小必须大于等于 1");
        }
        // AIops 联动开关传入时只能是 0 或 1。
        if (dto.getAiopsEnabled() != null
                && dto.getAiopsEnabled() != 0 && dto.getAiopsEnabled() != 1) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "AIops 联动开关只能为 0 或 1");
        }
        // 默认目标服务器可选，传入时必须真实存在，避免环境指向无效服务器。
        if (StringUtils.hasText(dto.getDefaultServerId())
                && serverMapper.selectById(dto.getDefaultServerId()) == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "默认目标服务器不存在");
        }
        // 编码唯一性检查，修改场景排除当前记录自身。
        LambdaQueryWrapper<Environment> codeWrapper = new LambdaQueryWrapper<Environment>()
                .eq(Environment::getCode, dto.getCode());
        if (excludeId != null) {
            codeWrapper.ne(Environment::getId, excludeId);
        }
        if (count(codeWrapper) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "环境编码已存在：" + dto.getCode());
        }
    }

    /**
     * 为新增实体填充默认值。
     *
     * <p>数据库层虽然定义了默认值，但显式填充保证新增后返回的 VO 与库中数据一致。</p>
     *
     * @param entity 待填充默认值的实体
     */
    private void applyDefaultValue(Environment entity) {
        // 未指定部署策略时默认单目标部署。
        if (!StringUtils.hasText(entity.getDeployStrategy())) {
            entity.setDeployStrategy(DeployStrategy.SINGLE_TARGET.getCode());
        }
        // 未指定批次大小时默认为 1。
        if (entity.getBatchSize() == null) {
            entity.setBatchSize(1);
        }
        // 未指定 AIops 联动时默认关闭。
        if (entity.getAiopsEnabled() == null) {
            entity.setAiopsEnabled((byte) 0);
        }
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Environment getRequiredEntityById(String id) {
        Environment entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署环境不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private Environment toEntity(EnvironmentDto dto) {
        Environment entity = new Environment();
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
    private EnvironmentVo toVo(Environment entity) {
        EnvironmentVo vo = new EnvironmentVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Environment> buildQueryWrapper(EnvironmentDto condition) {
        LambdaQueryWrapper<Environment> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 环境编码：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getCode()),
                Environment::getCode, condition.getCode());
        // 环境名称：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getName()),
                Environment::getName, condition.getName());
        // 部署策略：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getDeployStrategy()),
                Environment::getDeployStrategy, condition.getDeployStrategy());
        // 默认目标服务器ID：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getDefaultServerId()),
                Environment::getDefaultServerId, condition.getDefaultServerId());
        // 是否启用AIops联动：字段非空时使用等值条件查询。
        wrapper.eq(condition.getAiopsEnabled() != null,
                Environment::getAiopsEnabled, condition.getAiopsEnabled());
        // 默认按创建时间倒序返回，优先展示最近新增的数据。
        wrapper.orderByDesc(Environment::getCreateTime);
        return wrapper;
    }
}
