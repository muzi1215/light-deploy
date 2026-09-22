package ${package.Entity}.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * ${table.comment!"业务"}请求对象。
 *
 * <p>DTO 只用于 Controller 入参，避免接口层直接暴露数据库实体。主键、审计字段和软删除字段由服务端维护。</p>
 *
 * @author ${author}
 * @since ${date}
 */
@Data
@Schema(description = "${table.comment!"业务"}请求参数")
public class ${entity}Dto {
<#list table.fields as field>
<#if field.name != "id" && field.name != "create_time" && field.name != "update_time" && field.name != "is_deleted"
    && field.name != "created_by" && field.name != "updated_by">
    /** ${field.comment!field.propertyName} */
    @Schema(description = "${field.comment!field.propertyName}")
    private ${field.propertyType} ${field.propertyName};

</#if>
</#list>
}
