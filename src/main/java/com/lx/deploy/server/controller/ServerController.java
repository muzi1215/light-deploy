package com.lx.deploy.server.controller;

import com.lx.deploy.server.model.dto.ServerDto;
import com.lx.deploy.server.model.vo.ServerVo;
import com.lx.deploy.server.service.ServerService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部署服务器接口。
 *
 * <p>Controller 只负责接收请求、校验参数、调用 Service 和包装统一响应。
 * 动态查询、数据存在性检查和逻辑删除等规则由 Service 集中处理。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@RestController
@RequestMapping("/server")
public class ServerController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ServerService service;

    /**
     * 根据非空业务字段分页查询部署服务器。
     *
     * @param pageQuery 页码和每页数量
     * @param condition 动态查询条件，未传递的字段不会参与查询
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 分页元数据和当前页数据列表
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<ServerVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                   @ModelAttribute ServerDto condition,
                                                   HttpServletRequest request) {
        // Service 负责构建动态条件并执行分页查询。
        PageResult<ServerVo> result = service.pageByCondition(pageQuery, condition);
        return ApiResponse.success(result, requestId(request));
    }

    /**
     * 根据非空业务字段查询所有匹配记录。
     *
     * <p>该接口不分页，适合数据量较小的下拉选择和基础列表。数据量较大的页面应使用分页接口。</p>
     *
     * @param condition 动态查询条件，全部为空时查询所有未删除记录
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 满足条件的数据列表
     */
    @GetMapping("/list")
    public ApiResponse<List<ServerVo>> list(@ModelAttribute ServerDto condition,
                                             HttpServletRequest request) {
        // Service 复用与分页接口相同的动态条件构造规则。
        List<ServerVo> records = service.listByCondition(condition);
        return ApiResponse.success(records, requestId(request));
    }

    /**
     * 根据主键查询单条部署服务器。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 对应的业务实体
     */
    @GetMapping("/{id}")
    public ApiResponse<ServerVo> detail(@PathVariable String id, HttpServletRequest request) {
        // Service 在记录不存在时抛出统一 DATA_NOT_FOUND 业务异常。
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 新增一条部署服务器。
     *
     * @param dto 待新增的服务器请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 保存完成且不包含 SSH 密文的响应对象
     */
    @PostMapping
    public ApiResponse<ServerVo> create(@Valid @RequestBody ServerDto dto,
                                         HttpServletRequest request) {
        // Service 会清空调用方传入的主键，并由 MyBatis-Plus 生成 UUID。
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键修改部署服务器。
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的服务器请求参数
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 修改完成且不包含 SSH 密文的响应对象
     */
    @PutMapping("/{id}")
    public ApiResponse<ServerVo> update(@PathVariable String id,
                                         @Valid @RequestBody ServerDto dto,
                                         HttpServletRequest request) {
        // Service 始终使用路径中的 ID，避免请求体主键覆盖其他数据。
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 根据主键逻辑删除部署服务器。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取请求追踪ID
     * @return 不包含业务数据的统一成功响应
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        // Service 使用 MyBatis-Plus removeById，将 is_deleted 更新为1而不是物理删除记录。
        service.delete(id);
        return ApiResponse.success(requestId(request));
    }

    /**
     * 从请求属性读取 RequestTraceFilter 生成的追踪ID。
     *
     * @param request 当前 HTTP 请求
     * @return 请求追踪ID；请求属性不存在时返回 null
     */
    private String requestId(HttpServletRequest request) {
        // 追踪ID会同时出现在响应和日志中，用于关联一次完整请求。
        Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return requestId == null ? null : requestId.toString();
    }
}
