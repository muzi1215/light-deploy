package com.lx.deploy.build.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.build.model.Build;
import org.apache.ibatis.annotations.Mapper;

/**
 * 构建任务 Mapper。
 *
 * <p>当前为纯数据访问层，无自定义 SQL；复杂查询（按项目、状态过滤）由 MyBatis-Plus 条件构造器完成。
 * 跨模块校验统一注入本 Mapper，不注入对方 Service，避免 Service 循环依赖。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Mapper
public interface BuildMapper extends BaseMapper<Build> {
}
