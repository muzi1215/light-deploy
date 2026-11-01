package com.lx.deploy.aiops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * AIops 联动事件 Mapper。
 *
 * <p>纯数据访问层，无自定义 SQL：待发送/待重试事件的筛选由 MyBatis-Plus 条件构造器完成。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Mapper
public interface AiopsEventMapper extends BaseMapper<AiopsEvent> {
}
