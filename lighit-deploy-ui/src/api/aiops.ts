/**
 * AIops 联动 API（只读）。
 *
 * 对应后端 com.lx.deploy.aiops.controller.AiopsLinkController，接口前缀 /aiops。
 *
 * 业务约定：
 * 1. 这里只有查询，没有写操作。建窗与推事件都发生在发布链路内部，
 *    页面手工补建会绕开幂等键与 fail-open 降级判断，因此后端刻意不提供该入口；
 * 2. 返回体允许「部分为空」：没建过窗、总闸关、环境关、AIops 不可用，
 *    都以 `silence` 为空 + `silenceMessage` 说明原因的形式返回，不是异常；
 * 3. 事件是流水（一次发布多条），页面按 `sequence` 排序展示。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, AiopsLinkVo, AiopsStatusVo } from './deployTypes.ts'

/** 查询一次发布的联动全景（静默窗 + 事件流水） */
export function getDeploymentAiopsLink(deploymentId: string): Promise<ApiResponse<AiopsLinkVo>> {
  return request({ url: `/aiops/link/${deploymentId}`, method: 'get' })
}

/**
 * 查询平台级 AIops 联动状态（首页实时状态卡）。
 *
 * 只读，无写操作；总闸关闭或对端不可达时接口仍返回 200，
 * reachable 字段区分「没开联动」与「对端挂了」，前端据此呈现三档状态。
 */
export function getAiopsStatus(): Promise<ApiResponse<AiopsStatusVo>> {
  return request({ url: `/aiops/status`, method: 'get' })
}
