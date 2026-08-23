package com.lx.deploy.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.user.model.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 临时用户数据访问接口。
 *
 * <p>当前只使用 MyBatis-Plus 基础 CRUD。登录查询需要按 username 和 is_deleted 建立明确条件，
 * 不得在 Mapper 或日志中输出 passwordHash。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
