package com.lx.deploy.credential.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.credential.model.Credential;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 加密凭据 Mapper 接口
 * </p>
 *
 * @author lx
 * @since 2026-09-23 02:13:26
 */
@Mapper
public interface CredentialMapper extends BaseMapper<Credential> {

}
