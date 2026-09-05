package com.lx.deploy.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.task.model.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 异步任务 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {

}
