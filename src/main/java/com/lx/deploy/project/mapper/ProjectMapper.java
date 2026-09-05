package com.lx.deploy.project.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.project.model.Project;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 部署项目 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-09-02 02:05:03
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

}
