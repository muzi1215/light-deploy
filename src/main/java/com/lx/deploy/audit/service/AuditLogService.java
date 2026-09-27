package com.lx.deploy.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.lx.deploy.audit.mapper.AuditLogMapper;
import com.lx.deploy.audit.model.AuditLog;
import com.lx.deploy.audit.model.dto.AuditLogDto;
import com.lx.deploy.audit.model.vo.AuditFieldChangeVo;
import com.lx.deploy.audit.model.vo.AuditLogVo;
import com.lx.deploy.common.constants.AuditResult;
import com.lx.deploy.common.enums.audit.BusinessType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.SecretMasker;
import com.lx.deploy.common.util.TimeRangeSupport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作审计日志服务。
 *
 * <p>对外只暴露查询：分页与详情。审计记录的写入由 AOP 切面调用继承来的 {@code save} 完成，
 * 不提供新增 / 修改 / 删除接口——审计数据被改写就失去了存在的意义，
 * 与其靠约定禁止，不如让接口层根本没有这个入口。</p>
 *
 * <p>日志可能增长很快，因此分页是唯一的查询入口，且默认按时间倒序：
 * 「最近谁动了什么」是绝大多数追溯场景的起点。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Service
public class AuditLogService extends ServiceImpl<AuditLogMapper, AuditLog> {

    /**
     * 按条件分页查询操作日志。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 查询条件，字段为空表示不参与过滤
     * @return 统一分页结果，按操作时间倒序
     */
    public PageResult<AuditLogVo> pageByCondition(PageQuery pageQuery, AuditLogDto condition) {
        Page<AuditLog> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<AuditLogVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 查询单条操作日志详情。
     *
     * @param id 日志主键
     * @return 日志响应对象
     * @throws DeployException 日志不存在时抛出
     */
    public AuditLogVo getRequiredById(String id) {
        AuditLog entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "操作日志不存在");
        }
        return toVo(entity);
    }

    /**
     * 将实体转换为响应对象，并补上两类中文描述。
     *
     * <p>描述在服务端生成而不是前端映射：取值语义归后端所有，前端只负责渲染，
     * 避免两边各维护一份枚举文案（AGENTS.md §4.5）。</p>
     *
     * @param entity 日志实体
     * @return 日志响应对象
     */
    private AuditLogVo toVo(AuditLog entity) {
        AuditLogVo vo = new AuditLogVo();
        vo.setId(entity.getId());
        vo.setApplication(entity.getApplication());
        vo.setTraceId(entity.getTraceId());
        vo.setOperId(entity.getOperatorId());
        vo.setOperName(entity.getOperatorName());
        vo.setTitle(entity.getTitle());
        vo.setBusinessType(entity.getBusinessType());
        vo.setBusinessTypeText(BusinessType.describe(entity.getBusinessType()));
        vo.setResourceType(entity.getResourceType());
        vo.setResourceId(entity.getResourceId());
        vo.setMethod(entity.getMethod());
        vo.setRequestMethod(entity.getRequestMethod());
        vo.setOperUrl(entity.getRequestUri());
        vo.setOperIp(entity.getOperIp());
        vo.setOs(entity.getOs());
        vo.setBrowser(entity.getBrowser());
        vo.setOperParam(entity.getOperParam());
        vo.setJsonResult(entity.getJsonResult());
        vo.setResult(entity.getResult());
        vo.setResultText(AuditResult.describe(entity.getResult()));
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCostTime(entity.getCostTime());
        vo.setChangedFields(parseChangedFields(entity.getChangedFields()));
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }

    /**
     * 把变更明细 JSON 还原为明细列表。
     *
     * <p>解析失败返回空列表而不是抛异常：明细只是详情页的一部分，
     * 一条读不出来的记录不该让整个请求失败（AGENTS.md §14.3 要求吞掉的异常至少留下日志，
     * 这里连异常都取不到，因此只在文本非空却解析为空时告警）。</p>
     *
     * @param changedFields 变更明细 JSON 文本，允许为空
     * @return 明细列表；无内容时为不可变空列表
     */
    private List<AuditFieldChangeVo> parseChangedFields(String changedFields) {
        JsonNode root = SecretMasker.parseTree(changedFields);
        if (root == null || !root.isArray() || root.isEmpty()) {
            return List.of();
        }
        List<AuditFieldChangeVo> changes = new ArrayList<>(root.size());
        for (JsonNode item : root) {
            changes.add(new AuditFieldChangeVo(
                    textOf(item, "field"), textOf(item, "oldValue"), textOf(item, "newValue")));
        }
        return changes;
    }

    /**
     * 读取明细项里的一个属性。
     *
     * @param item 明细项节点
     * @param field 属性名
     * @return 属性值文本；节点缺失或为空时返回空串
     */
    private String textOf(JsonNode item, String field) {
        JsonNode node = item.get(field);
        return node == null || node.isNull() ? "" : node.asText();
    }

    /**
     * 根据查询条件构造动态条件。
     *
     * @param condition 查询条件，允许为 null
     * @return 条件构造器
     */
    private LambdaQueryWrapper<AuditLog> buildQueryWrapper(AuditLogDto condition) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            wrapper.orderByDesc(AuditLog::getCreateTime);
            return wrapper;
        }
        // 操作人与模块用模糊匹配：检索日志时用户通常只记得名称片段。
        wrapper.like(StringUtils.hasText(condition.getOperName()),
                AuditLog::getOperatorName, condition.getOperName());
        wrapper.like(StringUtils.hasText(condition.getTitle()),
                AuditLog::getTitle, condition.getTitle());
        // 类型、请求方式、结果都是固定取值，等值匹配即可。
        wrapper.eq(StringUtils.hasText(condition.getBusinessType()),
                AuditLog::getBusinessType, condition.getBusinessType());
        wrapper.eq(StringUtils.hasText(condition.getRequestMethod()),
                AuditLog::getRequestMethod, condition.getRequestMethod());
        wrapper.eq(condition.getResult() != null, AuditLog::getResult, condition.getResult());
        // 时间区间走公共实现：全平台所有列表同一个口径，开始晚于截止直接判参数错误。
        TimeRangeSupport.apply(wrapper, AuditLog::getCreateTime, condition);
        wrapper.orderByDesc(AuditLog::getCreateTime);
        return wrapper;
    }
}
