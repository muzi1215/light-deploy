package com.lx.deploy.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.server.model.Server;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 部署服务器 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Mapper
public interface ServerMapper extends BaseMapper<Server> {

}
