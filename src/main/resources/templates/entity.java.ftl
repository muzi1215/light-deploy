package ${package.Entity};

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
<#list importEntityFrameworkPackages as pkg>
import ${pkg};
</#list>
<#list importEntityJavaPackages as pkg>
import ${pkg};
</#list>

/**
 * ${table.comment!"业务实体"}。
 *
 * <p>该实体继承统一基础实体，主键、创建时间、更新时间和软删除字段由父类维护。
 * 业务字段说明来源于数据库字段注释，修改字段时应同步更新数据库 DDL。</p>
 *
 * @author ${author}
 * @since ${date}
 */
@Data
@TableName("${table.name}")
@Schema(description = "${table.comment!"业务实体"}")
public class ${entity} extends BaseEntity {
<#list table.fields as field>
<#if field.name != "id" && field.name != "create_time" && field.name != "update_time" && field.name != "is_deleted">
    /**
     * ${field.comment!field.propertyName}
     */
    @TableField("${field.annotationColumnName}")
    @Schema(description = "${field.comment!field.propertyName}")
    private ${field.propertyType} ${field.propertyName};

</#if>
</#list>
}
