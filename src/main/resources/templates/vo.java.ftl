package ${package.Entity}.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
<#list importEntityFrameworkPackages as pkg>
import ${pkg};
</#list>
<#list importEntityJavaPackages as pkg>
import ${pkg};
</#list>

/**
 * ${table.comment!"业务"}响应对象。
 *
 * <p>VO 只用于 Controller 出参。敏感字段不会生成到 VO，避免密码、密文、Token 或私钥被返回给调用方。</p>
 *
 * @author ${author}
 * @since ${date}
 */
@Data
@Schema(description = "${table.comment!"业务"}响应数据")
public class ${entity}Vo {
    /** 数据主键。 */
    private String id;
    /** 创建时间。 */
    private LocalDateTime createTime;
    /** 更新时间。 */
    private LocalDateTime updateTime;
<#list table.fields as field>
<#assign lowerName=field.propertyName?lower_case>
<#if field.name != "id" && field.name != "create_time" && field.name != "update_time" && field.name != "is_deleted"
    && !lowerName?contains("password") && !lowerName?contains("credential") && !lowerName?contains("secret")
    && !lowerName?contains("token") && !lowerName?contains("privatekey") && !lowerName?contains("encrypted") && !lowerName?contains("ciphertext")>
    /** ${field.comment!field.propertyName} */
    @Schema(description = "${field.comment!field.propertyName}")
    private ${field.propertyType} ${field.propertyName};

</#if>
</#list>
}
