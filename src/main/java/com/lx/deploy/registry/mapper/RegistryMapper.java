package com.lx.deploy.registry.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.registry.model.Registry;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 镜像仓库 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-09-23 02:13:27
 */
@Mapper
public interface RegistryMapper extends BaseMapper<Registry> {

}
