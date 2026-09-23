package com.lx.deploy.credential.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.enums.credential.CredentialType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.credential.mapper.CredentialMapper;
import com.lx.deploy.credential.model.Credential;
import com.lx.deploy.credential.model.dto.CredentialDto;
import com.lx.deploy.credential.model.vo.CredentialVo;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.registry.mapper.RegistryMapper;
import com.lx.deploy.registry.model.Registry;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 加密凭据服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。</p>
 *
 * <p><b>本类是凭据明文的第二个出现点</b>（第一个是 server 表的 SSH 凭据）：新增/修改时对
 * 调用方提交的明文调用 {@code CredentialCrypto.encrypt()}，之后明文立刻丢弃，不写日志、
 * 不进响应、不缓存到字段。解密由消费方（Git 拉取、镜像仓库推送）在执行前自行调用
 * {@code CredentialCrypto.decrypt()}，本服务不提供「取明文」的接口（AGENTS.md §7）。</p>
 *
 * <p>删除前会检查凭据是否仍被 project.git_credential_id 或 registry.credential_id 引用：
 * 跨模块只注入对方 Mapper，不注入对方 Service，避免 Service 之间形成循环依赖
 * （与 ProjectService 校验 server 的方式一致）。</p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@Service
public class CredentialService extends ServiceImpl<CredentialMapper, Credential> {

    /** JSON 解析器，只用于校验 metadata_json 的格式，不做业务语义解析。 */
    private static final ObjectMapper JSON_PARSER = new ObjectMapper();

    /** 凭据名称长度上限，与数据库列 credential.name VARCHAR(100) 保持一致。 */
    private static final int MAX_NAME_LENGTH = 100;

    /** 凭据加解密组件，明文只在加密方法调用前后短暂存在。 */
    @Autowired
    private CredentialCrypto credentialCrypto;

    /** 项目 Mapper：删除凭据前统计 project.git_credential_id 的引用数量。 */
    @Autowired
    private ProjectMapper projectMapper;

    /** 镜像仓库 Mapper：删除凭据前统计 registry.credential_id 的引用数量。 */
    @Autowired
    private RegistryMapper registryMapper;

    /**
     * 根据 DTO 中的非空条件分页查询加密凭据。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<CredentialVo> pageByCondition(PageQuery pageQuery, CredentialDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<Credential> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<CredentialVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<CredentialVo> listByCondition(CredentialDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回加密凭据响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public CredentialVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条加密凭据。
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、名称重复或保存失败时抛出
     */
    public CredentialVo create(CredentialDto dto) {
        // 空白 JSON 先归一为 null，避免写入 MySQL JSON 列时报格式错误。
        normalizeMetadataJson(dto);
        // 新增前校验名称、类型、明文凭据和名称唯一性。
        validateBusinessRule(dto, null);
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        Credential entity = toEntity(dto);
        // 明文凭据在这里加密落库，DTO 上的明文字段随后不再被使用。
        entity.setEncryptedValue(credentialCrypto.encrypt(dto.getCredential()));
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增加密凭据失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改加密凭据。
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、参数不合法、名称重复或修改失败时抛出
     */
    public CredentialVo update(String id, CredentialDto dto) {
        // 空白 JSON 先归一为 null，语义为「清空该字段」。
        normalizeMetadataJson(dto);
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        Credential entity = getRequiredEntityById(id);
        // 修改时名称唯一性检查排除当前记录；明文凭据选填，因此这里不强制必填。
        validateBusinessRule(dto, id);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        // 只有传入了新明文才重新加密覆盖旧密文；未传则保留数据库里的原密文。
        if (StringUtils.hasText(dto.getCredential())) {
            entity.setEncryptedValue(credentialCrypto.encrypt(dto.getCredential()));
        }
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改加密凭据失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除加密凭据。
     *
     * <p>删除前检查引用：仍被项目或镜像仓库引用的凭据不允许删除，否则会留下指向不存在凭据的
     * 悬空引用（Git 拉取、镜像推送时会直接失败）。应先解除引用再删除。</p>
     *
     * @param id 待删除数据的主键
     * @throws DeployException 数据不存在、仍被项目或镜像仓库引用或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        // 统计仍引用该凭据的项目数量（project.git_credential_id）。
        Long projectCount = projectMapper.selectCount(
                new LambdaQueryWrapper<Project>().eq(Project::getGitCredentialId, id));
        if (projectCount != null && projectCount > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "凭据仍被 " + projectCount + " 个项目引用，请先解除项目的 Git 凭据绑定");
        }
        // 统计仍引用该凭据的镜像仓库数量（registry.credential_id）。
        Long registryCount = registryMapper.selectCount(
                new LambdaQueryWrapper<Registry>().eq(Registry::getCredentialId, id));
        if (registryCount != null && registryCount > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "凭据仍被 " + registryCount + " 个镜像仓库引用，请先解除仓库的凭据绑定");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除加密凭据失败");
        }
    }

    /**
     * 校验凭据的新增/修改业务规则。
     *
     * <p>规则镜像到前端表单校验（AGENTS.md §20.2）：名称必填且不超过 100 字符、类型必须是
     * 受支持的枚举值、明文凭据在新增时必填、metadata_json 必须是合法 JSON、名称不能重复。</p>
     *
     * @param dto 请求参数
     * @param excludeId 修改场景下需要排除的当前记录主键；新增时传 null
     * @throws DeployException 任一规则不满足时抛出，参数问题用 VALIDATION_ERROR，重名用 DATA_CONFLICT
     */
    private void validateBusinessRule(CredentialDto dto, String excludeId) {
        // 名称必填且长度受数据库列限制。
        if (!StringUtils.hasText(dto.getName())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "凭据名称不能为空");
        }
        if (dto.getName().length() > MAX_NAME_LENGTH) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "凭据名称长度不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        // 类型必填且必须是受支持的枚举值。
        if (CredentialType.fromCode(dto.getType()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "凭据类型必须为 GIT_TOKEN、SSH_KEY 或 REGISTRY");
        }
        // 明文凭据新增必填：没有凭据内容的凭据记录没有意义。
        if (excludeId == null && !StringUtils.hasText(dto.getCredential())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "凭据内容不能为空");
        }
        // 元数据传入时必须是合法 JSON，尽早拦截格式错误。
        validateJsonFormat("凭据元数据", dto.getMetadataJson());
        // 名称唯一性检查，修改场景排除当前记录自身。
        LambdaQueryWrapper<Credential> nameWrapper = new LambdaQueryWrapper<Credential>()
                .eq(Credential::getName, dto.getName());
        if (excludeId != null) {
            nameWrapper.ne(Credential::getId, excludeId);
        }
        if (count(nameWrapper) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "凭据名称已存在：" + dto.getName());
        }
    }

    /**
     * 校验 JSON 字段格式。
     *
     * @param fieldName 用于异常提示的字段中文名
     * @param json 待校验的 JSON 文本，为空时跳过校验
     * @throws DeployException JSON 非法时抛出
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
     * 归一化请求 DTO 中的元数据字段。
     *
     * <p>数据库列是 MySQL `JSON` 类型，空字符串不是合法 JSON，直接写入会被数据库拒绝；
     * 前端文本域未填写时提交空串，因此统一归一为 null，语义为「未配置元数据」。</p>
     *
     * @param dto 请求参数；方法内原地修改
     */
    private void normalizeMetadataJson(CredentialDto dto) {
        if (dto.getMetadataJson() != null && dto.getMetadataJson().isBlank()) {
            dto.setMetadataJson(null);
        }
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Credential getRequiredEntityById(String id) {
        Credential entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "加密凭据不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private Credential toEntity(CredentialDto dto) {
        Credential entity = new Credential();
        // DTO 没有密文字段，因此属性复制不会把调用方提交的内容直接写进 encrypted_value，
        // 密文只能由本服务的 encrypt 逻辑产生。
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
    private CredentialVo toVo(Credential entity) {
        CredentialVo vo = new CredentialVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Credential> buildQueryWrapper(CredentialDto condition) {
        LambdaQueryWrapper<Credential> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 凭据名称：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getName()),
                Credential::getName, condition.getName());
        // 凭据类型：SSH_PASSWORD/SSH_KEY/GIT_TOKEN/REGISTRY：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getType()),
                Credential::getType, condition.getType());
        // 有意不把 metadataJson 作为查询条件：它是自由文本元数据，参与查询没有业务价值，
        // 却容易让调用方通过模糊条件反推凭据内容，保持「敏感字段不参与条件查询」的口径。
        // 密文字段 encryptedValue 同样永远不参与查询。
        wrapper.orderByDesc(Credential::getCreateTime);
        return wrapper;
    }
}
