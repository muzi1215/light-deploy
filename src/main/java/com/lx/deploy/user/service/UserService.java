package com.lx.deploy.user.service;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.user.mapper.UserMapper;
import com.lx.deploy.user.model.User;
import org.springframework.stereotype.Service;

/**
 * 临时用户 CRUD 服务。
 *
 * <p>本服务只为统一权限中心接入前的本地用户提供基础数据操作，不创建额外的 Service 接口。
 * 登录、密码哈希校验和账号锁定等安全逻辑将在鉴权模块开发时以独立业务方法实现。</p>
 */
@Service
public class UserService extends ServiceImpl<UserMapper, User> {
}
