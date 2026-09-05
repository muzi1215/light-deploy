package com.lx.deploy.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.task.model.TaskLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 任务执行日志 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@Mapper
public interface TaskLogMapper extends BaseMapper<TaskLog> {

}
