package com.lx.deploy.build.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.build.model.BuildArtifact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 构建产物 Mapper。
 *
 * <p>当前为纯数据访问层，无自定义 SQL；按 build_id 查询产物列表由 MyBatis-Plus 条件构造器完成。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Mapper
public interface BuildArtifactMapper extends BaseMapper<BuildArtifact> {
}
