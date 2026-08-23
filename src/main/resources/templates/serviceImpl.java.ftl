package ${package.ServiceImpl};

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import ${package.Mapper}.${table.mapperName};
import ${package.Entity}.${entity};
import ${package.Entity}.dto.${entity}Dto;
import ${package.Entity}.vo.${entity}Vo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * ${table.comment!"业务"}服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。Controller 只传递 DTO 和 VO，
 * 实体仅在 Service 与 Mapper 数据访问边界内使用。</p>
 *
 * @author ${author}
 * @since ${date}
 */
@Service
public class ${table.serviceImplName} extends ServiceImpl<${table.mapperName}, ${entity}> {

    /**
     * 根据 DTO 中的非空条件分页查询${table.comment!"数据"}。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<${entity}Vo> pageByCondition(PageQuery pageQuery, ${entity}Dto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<${entity}> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<${entity}Vo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<${entity}Vo> listByCondition(${entity}Dto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回${table.comment!"数据"}响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public ${entity}Vo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条${table.comment!"数据"}。
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 数据保存失败时抛出
     */
    public ${entity}Vo create(${entity}Dto dto) {
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        ${entity} entity = toEntity(dto);
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增${table.comment!"数据"}失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改${table.comment!"数据"}。
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在或修改失败时抛出
     */
    public ${entity}Vo update(String id, ${entity}Dto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        ${entity} entity = getRequiredEntityById(id);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改${table.comment!"数据"}失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除${table.comment!"数据"}。
     *
     * @param id 待删除数据的主键
     * @throws DeployException 数据不存在或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除${table.comment!"数据"}失败");
        }
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private ${entity} getRequiredEntityById(String id) {
        ${entity} entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "${table.comment!"数据"}不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private ${entity} toEntity(${entity}Dto dto) {
        ${entity} entity = new ${entity}();
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
    private ${entity}Vo toVo(${entity} entity) {
        ${entity}Vo vo = new ${entity}Vo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<${entity}> buildQueryWrapper(${entity}Dto condition) {
        LambdaQueryWrapper<${entity}> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
<#list table.fields as field>
<#assign lowerName=field.propertyName?lower_case>
<#if field.name != "id" && field.name != "create_time" && field.name != "update_time" && field.name != "is_deleted"
    && field.name != "created_by" && field.name != "updated_by"
    && !lowerName?contains("password") && !lowerName?contains("credential") && !lowerName?contains("secret")
    && !lowerName?contains("token") && !lowerName?contains("privatekey") && !lowerName?contains("encrypted") && !lowerName?contains("ciphertext")>
<#if field.propertyType == "String">
        // ${field.comment!field.propertyName}：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.get${field.capitalName}()),
                ${entity}::get${field.capitalName}, condition.get${field.capitalName}());
<#else>
        // ${field.comment!field.propertyName}：字段非空时使用等值条件查询。
        wrapper.eq(condition.get${field.capitalName}() != null,
                ${entity}::get${field.capitalName}, condition.get${field.capitalName}());
</#if>
</#if>
</#list>
        wrapper.orderByDesc(${entity}::getCreateTime);
        return wrapper;
    }
}
