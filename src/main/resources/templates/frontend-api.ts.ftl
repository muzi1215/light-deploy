<#-- 前端 API 模块模板：生成 {module}.ts，包含模块内所有表的类型定义与接口函数 -->
/**
 * ${module} 模块 API（由代码生成器生成，拷入 lighit-deploy-ui/src/api/ 后人工精化）。
 *
 * 对应后端 com.lx.deploy.${module}.controller 包下的 Controller，
 * 统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'

<#list entities as ent>
// ==================== ${ent.comment} ====================

/** ${ent.comment} VO（响应对象） */
export interface ${ent.entityName}Vo {
  id: string
  createTime: string
  updateTime: string
<#list ent.fields as field>
  /** ${field.comment} */
  ${field.camelName}: ${field.tsType}<#if field.nullable> | null</#if>
</#list>
}

/** ${ent.comment} DTO（查询/新增/修改请求参数） */
export interface ${ent.entityName}Dto {
<#list ent.fields as field>
  /** ${field.comment} */
  ${field.camelName}?: ${field.tsType}
</#list>
}

/** 条件分页查询${ent.comment} */
export function page${ent.entityName}(params: any): Promise<any> {
  return request({ url: '/${ent.entityPath}/page', method: 'get', params })
}

/** 条件列表查询${ent.comment}（下拉选择场景，不分页） */
export function list${ent.entityName}s(params?: any): Promise<any> {
  return request({ url: '/${ent.entityPath}/list', method: 'get', params })
}

/** 根据主键查询${ent.comment}详情 */
export function get${ent.entityName}(id: string): Promise<any> {
  return request({ url: `/${ent.entityPath}/${r'${id}'}`, method: 'get' })
}

/** 新增${ent.comment} */
export function create${ent.entityName}(data: ${ent.entityName}Dto): Promise<any> {
  return request({ url: '/${ent.entityPath}', method: 'post', data })
}

/** 修改${ent.comment} */
export function update${ent.entityName}(id: string, data: ${ent.entityName}Dto): Promise<any> {
  return request({ url: `/${ent.entityPath}/${r'${id}'}`, method: 'put', data })
}

/** 逻辑删除${ent.comment} */
export function delete${ent.entityName}(id: string): Promise<any> {
  return request({ url: `/${ent.entityPath}/${r'${id}'}`, method: 'delete' })
}

</#list>
