package com.lx.deploy.deployment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.deployment.model.DeploymentTarget;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部署目标任务 Mapper。
 *
 * <p>纯数据访问层，无自定义 SQL：按部署 ID 查全部目标、按服务器查历史目标等场景
 * 都由 MyBatis-Plus 条件构造器完成，排序统一按主键创建顺序（与目标快照顺序一致）。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Mapper
public interface DeploymentTargetMapper extends BaseMapper<DeploymentTarget> {
}
