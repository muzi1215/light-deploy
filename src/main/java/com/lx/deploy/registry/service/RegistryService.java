package com.lx.deploy.registry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.enums.credential.CredentialType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import com.lx.deploy.registry.model.dto.RegistryDto;
import com.lx.deploy.registry.model.vo.RegistryVo;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 镜像仓库服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。</p>
 *
 * <p>本服务只服务于 DOCKER 运行时：脚本部署与仅发布产物不产出镜像，也就不需要绑定仓库
 * （AGENTS.md §23.4）。绑定关系由 project.registry_id 表达，跨模块校验只注入对方 Mapper，
 * 不注入对方 Service，避免 Service 之间形成循环依赖。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:27
 */
@Service
public class RegistryService extends ServiceImpl<RegistryMapper, Registry> {

    /** 仓库名称长度上限，与数据库列 registry.name VARCHAR(100) 保持一致。 */
    private static final int MAX_NAME_LENGTH = 100;

    /** 仓库地址长度上限，与数据库列 registry.registry_url VARCHAR(255) 保持一致。 */
    private static final int MAX_URL_LENGTH = 255;

    /** 凭据 Mapper：校验 registry.credential_id 引用的是类型为 REGISTRY 的凭据。 */
    @Autowired
    private CredentialMapper credentialMapper;

    /** 项目 Mapper：删除仓库前统计 project.registry_id 的引用数量。 */
    @Autowired
    private ProjectMapper projectMapper;

    /**
     * 根据 DTO 中的非空条件分页查询镜像仓库。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<RegistryVo> pageByCondition(PageQuery pageQuery, RegistryDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<Registry> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<RegistryVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<RegistryVo> listByCondition(RegistryDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回镜像仓库响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public RegistryVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条镜像仓库。
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、名称重复或保存失败时抛出
     */
    public RegistryVo create(RegistryDto dto) {
        // 新增前校验名称、地址、凭据引用与名称唯一性。
        validateBusinessRule(dto, null);
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        Registry entity = toEntity(dto);
        // 未指定状态时默认启用，显式填充保证新增接口返回的 VO 与库内数据一致。
        applyDefaultValue(entity);
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增镜像仓库失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改镜像仓库。
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、参数不合法、名称重复或修改失败时抛出
     */
    public RegistryVo update(String id, RegistryDto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        Registry entity = getRequiredEntityById(id);
        // 修改时名称唯一性检查排除当前记录。
        validateBusinessRule(dto, id);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改镜像仓库失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除镜像仓库。
     *
     * <p>删除前检查引用：仍被项目（project.registry_id）绑定的仓库不允许删除，
     * 否则项目构建时会推送失败。应先解除项目的仓库绑定。</p>
     *
     * @param id 待删除数据的主键
     * @throws DeployException 数据不存在、仍被项目引用或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        // 统计仍绑定该仓库的项目数量。
        Long projectCount = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getRegistryId, id));
        if (projectCount != null && projectCount > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "镜像仓库仍被 " + projectCount + " 个项目绑定，请先解除项目的仓库绑定");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除镜像仓库失败");
        }
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Registry getRequiredEntityById(String id) {
        Registry entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "镜像仓库不存在");
        }
        return entity;
    }

    /**
     * 校验镜像仓库的新增/修改业务规则。
     *
     * <p>规则镜像到前端表单校验（AGENTS.md §20.2）：名称必填且不超过 100 字符、地址必填且
     * 不超过 255 字符、状态必须是 0 或 1、credentialId 填写时必须是指向 REGISTRY 类型凭据的
     * 有效引用、名称不能重复。</p>
     *
     * @param dto 请求参数
     * @param excludeId 修改场景下需要排除的当前记录主键；新增时传 null
     * @throws DeployException 任一规则不满足时抛出
     */
    private void validateBusinessRule(RegistryDto dto, String excludeId) {
        // 名称必填且长度受数据库列限制。
        if (!StringUtils.hasText(dto.getName())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "镜像仓库名称不能为空");
        }
        if (dto.getName().length() > MAX_NAME_LENGTH) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "镜像仓库名称长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        // 地址必填：没有地址的仓库无法用于镜像推送。
        if (!StringUtils.hasText(dto.getRegistryUrl())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "镜像仓库地址不能为空");
        }
        if (dto.getRegistryUrl().length() > MAX_URL_LENGTH) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "镜像仓库地址长度不能超过 " + MAX_URL_LENGTH + " 个字符");
        }
        // 状态可选，传入时必须是合法数字编码（0 停用 / 1 启用）。
        if (dto.getStatus() != null && !EnableStatus.isValid(dto.getStatus())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "状态必须为 0（停用）或 1（启用）");
        }
        // 凭据引用校验：公共仓库可以不填，填写时必须是类型为 REGISTRY 的凭据。
        validateCredentialReference(dto.getCredentialId());
        // 名称唯一性检查，修改场景排除当前记录自身。
        LambdaQueryWrapper<Registry> nameWrapper = new LambdaQueryWrapper<Registry>()
                .eq(Registry::getName, dto.getName());
        if (excludeId != null) {
            nameWrapper.ne(Registry::getId, excludeId);
        }
        if (count(nameWrapper) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "镜像仓库名称已存在：" + dto.getName());
        }
    }

    /**
     * 校验仓库凭据引用。
     *
     * <p>两件必须同时成立的事：凭据存在（否则推送时解密会失败），凭据类型是 REGISTRY
     * （Git 令牌不能用来登录镜像仓库）。</p>
     *
     * @param credentialId 待校验的凭据主键，允许为 null（公共仓库）
     * @throws DeployException 凭据不存在或类型不符时抛出
     */
    private void validateCredentialReference(String credentialId) {
        if (!StringUtils.hasText(credentialId)) {
            return;
        }
        Credential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "绑定的凭据不存在，请重新选择");
        }
        if (CredentialType.fromCode(credential.getType()) != CredentialType.REGISTRY) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "镜像仓库只能绑定类型为 REGISTRY 的凭据，当前凭据类型为 " + credential.getType());
        }
    }

    /**
     * 填充新增时的默认值。
     *
     * @param entity 待保存的实体；方法内原地修改
     */
    private void applyDefaultValue(Registry entity) {
        // 未指定状态时默认启用：仓库登记后立即可被项目绑定。
        if (entity.getStatus() == null) {
            entity.setStatus(EnableStatus.ENABLED);
        }
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private Registry toEntity(RegistryDto dto) {
        Registry entity = new Registry();
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
    private RegistryVo toVo(Registry entity) {
        RegistryVo vo = new RegistryVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Registry> buildQueryWrapper(RegistryDto condition) {
        LambdaQueryWrapper<Registry> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 仓库名称：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getName()),
                Registry::getName, condition.getName());
        // 仓库地址：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getRegistryUrl()),
                Registry::getRegistryUrl, condition.getRegistryUrl());
        // 仓库凭据：非空字符串使用等值条件查询，便于排查「某个凭据被哪些仓库引用」。
        wrapper.eq(StringUtils.hasText(condition.getCredentialId()),
                Registry::getCredentialId, condition.getCredentialId());
        // 状态：0停用，1启用：字段非空时使用等值条件查询。
        wrapper.eq(condition.getStatus() != null,
                Registry::getStatus, condition.getStatus());
        // 有意不把 namespace / description 作为查询条件：两者都是自由文本，
        // 参与等值查询没有业务价值，需要筛选时再按实际场景补条件。
        wrapper.orderByDesc(Registry::getCreateTime);
        return wrapper;
    }
}
