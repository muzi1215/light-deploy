/**
 * 操作日志模块 API。
 *
 * 对应后端 com.lx.deploy.audit.controller.AuditLogController，接口前缀 /auditLog。
 * 日志由后端 AOP 切面写入，本模块只提供查询：审计记录不开放任何写接口。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, AuditLogVo, AuditLogQuery } from './deployTypes.ts'

/** 条件分页查询操作日志（默认按操作时间倒序） */
export function pageAuditLog(params: AuditLogQuery): Promise<ApiResponse<PageResult<AuditLogVo>>> {
  return request({ url: '/auditLog/page', method: 'get', params })
}

/** 查询单条操作日志详情（含请求参数与返回结果） */
export function getAuditLog(id: string): Promise<ApiResponse<AuditLogVo>> {
  return request({ url: `/auditLog/detail/${id}`, method: 'get' })
}
