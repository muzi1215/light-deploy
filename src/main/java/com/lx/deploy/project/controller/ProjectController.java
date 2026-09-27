package com.lx.deploy.project.controller;

import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.project.model.dto.ProjectDto;
import com.lx.deploy.project.model.vo.ProjectVo;
import com.lx.deploy.project.service.ProjectService;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import com.lx.deploy.task.model.vo.TaskVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部署项目接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@RestController
@RequestMapping("/project")
public class ProjectController {

    /** 当前模块业务服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ProjectService service;

    /**
     * 根据 DTO 非空字段分页查询数据。
     *
     * @param pageQuery 页码和每页数量
     * @param condition DTO 查询条件
     * @param request 当前 HTTP 请求，用于获取 requestId
     * @return VO 分页数据
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<ProjectVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                      @ModelAttribute ProjectDto condition,
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
    @GetMapping("/list")
    public ApiResponse<List<ProjectVo>> list(@ModelAttribute ProjectDto condition,
                                               HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单条部署项目。
     *
     * @param id 数据主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含敏感字段的单条 VO
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<ProjectVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
    }

    /**
     * 根据请求 DTO 新增一条部署项目。
     *
     * @param dto 新增请求参数，主键和审计字段由服务端维护
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 保存完成后的 VO
     */
    @OperLog(title = "项目管理", businessType = BusinessType.INSERT)
    @PostMapping("/create")
    public ApiResponse<ProjectVo> create(@Valid @RequestBody ProjectDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.create(dto), requestId(request));
    }

    /**
     * 根据路径主键和请求 DTO 修改部署项目。
     *
     * @param id 待修改数据的主键
     * @param dto 修改请求参数，不能通过请求体修改主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 修改完成后的 VO
     */
    @OperLog(title = "项目管理", businessType = BusinessType.UPDATE, resourceType = "project")
    @PutMapping("/update/{id}")
    public ApiResponse<ProjectVo> update(@PathVariable String id,
                                           @Valid @RequestBody ProjectDto dto,
                                           HttpServletRequest request) {
        return ApiResponse.success(service.update(id, dto), requestId(request));
    }

    /**
     * 提交一次项目连通性测试任务。
     *
     * <p>测试在项目的构建机上执行，校验代码仓库可否拉取、镜像仓库可否登录，
     * 把这些配置问题提前暴露，而不是拖到真正构建时才以「构建失败」的形式出现。
     * 接口只创建异步任务并立即返回，页面用返回的任务主键轮询 /taskLog/pull 查看执行日志。</p>
     *
     * @param id 目标项目主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 新建的异步任务（状态为 0 排队中）
     */
    @OperLog(title = "项目管理", businessType = BusinessType.EXECUTE)
    @PostMapping("/test/{id}")
    public ApiResponse<TaskVo> test(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.submitConnectivityTest(id), requestId(request));
    }

    /**
     * 根据主键逻辑删除部署项目。
     *
     * @param id 待删除数据的主键
     * @param request 当前 HTTP 请求，用于读取本次请求的 requestId
     * @return 不包含业务数据的统一成功响应
     */
    @OperLog(title = "项目管理", businessType = BusinessType.DELETE, resourceType = "project")
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
