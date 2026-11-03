package ${package.Controller};

import ${package.Entity}.dto.${entity}Dto;
import ${package.Entity}.vo.${entity}Vo;
import ${package.ServiceImpl}.${table.serviceImplName};
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

<#-- 业务名统一取表注释，缺省为「业务」。集中赋值一次，避免在每个注解里重复写默认值表达式。 -->
<#assign biz = table.comment!"业务">
/**
 * ${biz}接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author ${author}
 * @since ${date}
 */
@RestController
@RequestMapping("/${table.entityPath}")
@Tag(name = "${biz}管理", description = "${biz}的新增、修改、删除与分页查询")
public class ${table.controllerName} {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ${table.serviceImplName} service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @Operation(summary = "${biz}分页", description = "按 DTO 非空字段筛选，按创建时间倒序")
    @GetMapping("/page")
    public ApiResponse<PageResult<${entity}Vo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute ${entity}Dto condition,
                                                      HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 根据 DTO 中的非空业务字段查询全部匹配记录。
     *
     * <p>该接口适合数据量较小的选择列表；大数据量页面应使用分页接口。</p>
     *
     * @param condition DTO 查询条件，字段全部为空时查询所有未删除记录
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的 VO 列表
     */
    @Operation(summary = "${biz}列表", description = "不分页，适合下拉与轻量列表；数据量大时应使用分页接口")
    @GetMapping("/list")
    public ApiResponse<List<${entity}Vo>> list(@ModelAttribute ${entity}Dto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条${biz}。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @Operation(summary = "${biz}详情", description = "返回单条记录，不含敏感字段")
    @GetMapping("/detail/{id}")
    public ApiResponse<${entity}Vo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条${biz}。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @Operation(summary = "新增${biz}", description = "主键与审计字段由服务端维护")
    @PostMapping("/create")
    public ApiResponse<${entity}Vo> create(@Valid @RequestBody ${entity}Dto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改${biz}。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @Operation(summary = "修改${biz}", description = "按路径主键更新；主键不能通过请求体修改")
    @PutMapping("/update/{id}")
    public ApiResponse<${entity}Vo> update(@PathVariable String id,
                                           @Valid @RequestBody ${entity}Dto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除${biz}。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @Operation(summary = "删除${biz}", description = "逻辑删除，不物理删除记录")
    @DeleteMapping("/delete/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(id);
        return ApiResponse.success(requestId(request));
    }

    /**
     * 从请求属性读取本次请求的追踪 ID。
     *
     * @param request 当前 HTTP 请求
     * @return 与响应头和 MDC 日志一致的 requestId
     */
    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return requestId == null ? null : requestId.toString();
    }
}
