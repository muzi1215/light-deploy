package com.lx.deploy.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.audit.model.AuditLog;

/**
 * 操作审计日志数据访问。
 *
 * <p>只依赖 MyBatis-Plus 提供的通用能力：本表是追加写的（切面新增、页面只读），
 * 没有自定义 SQL，因此不需要对应的 XML。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
