package com.lx.deploy.release.controller;

import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import com.lx.deploy.release.model.dto.ReleaseRecordDto;
import com.lx.deploy.release.model.vo.ReleaseRecordVo;
import com.lx.deploy.release.service.ReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布版本接口（只读）。
 *
 * <p>发布版本由构建成功自动创建，本接口只提供查询，供发布模块选择「要发哪个版本」。
 * 不提供新增/修改/删除：版本必须与真实构建产物对应，手工写入只会造出无法发布的空版本。</p>
 *
 * <p>路径风格遵循 AGENTS.md §12.2：动作在前、{id} 在后。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@RestController
@RequestMapping("/release")
public class ReleaseController {

    /** 发布版本服务，由 Spring 容器完成字段注入。 */
    @Autowired
    private ReleaseService service;

    /**
     * 分页查询发布版本。
     *
     * @param pageQuery 页码和每页数量
     * @param condition 查询条件（项目、状态）
     * @param request   当前 HTTP 请求
     * @return 发布版本 VO 分页数据
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<ReleaseRecordVo>> page(@Valid @ModelAttribute PageQuery pageQuery,
                                                         @ModelAttribute ReleaseRecordDto condition,
                                                         HttpServletRequest request) {
        return ApiResponse.success(service.pageByCondition(pageQuery, condition), requestId(request));
    }

    /**
     * 查询全部匹配发布版本（发布页版本下拉使用）。
     *
     * @param condition 查询条件；通常传 projectId 与 status=1（可发布）
     * @param request   当前 HTTP 请求
     * @return 发布版本 VO 列表
     */
    @GetMapping("/list")
    public ApiResponse<List<ReleaseRecordVo>> list(@ModelAttribute ReleaseRecordDto condition,
                                                   HttpServletRequest request) {
        return ApiResponse.success(service.listByCondition(condition), requestId(request));
    }

    /**
     * 根据主键查询单个发布版本。
     *
     * @param id      发布版本主键
     * @param request 当前 HTTP 请求
     * @return 发布版本 VO
     */
    @GetMapping("/detail/{id}")
    public ApiResponse<ReleaseRecordVo> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.getRequiredById(id), requestId(request));
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
