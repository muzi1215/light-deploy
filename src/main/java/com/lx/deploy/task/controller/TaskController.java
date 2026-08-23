package com.lx.deploy.task.controller;

import lombok.extern.slf4j.Slf4j;
import com.lx.deploy.common.response.ApiResponse;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务模块临时接口。
 *
 * <p>当前接口用于验证统一响应、请求追踪和日志配置。正式开发任务模块后，
 * 将由任务查询、任务日志和任务取消等真实接口替换。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class TaskController {

    @GetMapping("/task")
    /**
     * 返回任务模块测试信息并写入一条 INFO 日志。
     *
     * @param request 当前 HTTP 请求，用于读取 RequestTraceFilter 写入的追踪ID
     * @return 包含测试文本和请求追踪ID的统一成功响应
     */
    public ApiResponse<String> taskInfo(HttpServletRequest request){
        // 输出测试日志，用于验证控制台和滚动文件日志是否同时生效。
        log.info("这是一个测试任务！！！");
        // 从请求属性读取追踪ID，并原样放入响应对象返回给调用方。
        Object traceId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
        return ApiResponse.success("这是轻量化部署平台的第一个测试接口",
                traceId == null ? null : traceId.toString());
    }
}
