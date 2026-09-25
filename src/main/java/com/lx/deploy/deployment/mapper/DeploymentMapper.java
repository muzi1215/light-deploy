package com.lx.deploy.deployment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.deployment.model.Deployment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部署主任务 Mapper。
 *
 * <p>纯数据访问层，无自定义 SQL：按项目、环境、状态过滤与排序都由 MyBatis-Plus 条件构造器完成。
 * 跨模块（项目、环境、版本、服务器）校验统一注入对方 Mapper，不注入对方 Service，避免循环依赖。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Mapper
public interface DeploymentMapper extends BaseMapper<Deployment> {
}
