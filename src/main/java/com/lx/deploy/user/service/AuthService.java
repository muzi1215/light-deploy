package com.lx.deploy.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.common.crypto.Pbkdf2PasswordEncoder;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.security.LoginUser;
import com.lx.deploy.common.util.IdGenerator;
import com.lx.deploy.user.mapper.UserMapper;
import com.lx.deploy.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 后台鉴权服务（过渡期本地实现）。
 *
 * <p>负责账号密码校验、登录态所需的用户信息组装，以及首次启动时默认管理员的初始化。
 * 安全逻辑（密码哈希、账号状态）集中在此，不散落到 Controller。接入统一权限中心（auth-cloud）后，
 * 本类将改为调用权限中心校验，本地 user 表仅保留兼容与迁移用途。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 过渡期默认管理员账号。 */
    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    /** 过渡期默认管理员密码。 */
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final UserMapper userMapper;

    @Autowired
    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 校验账号密码并组装登录用户。
     *
     * @param username 登录用户名
     * @param rawPassword 明文密码
     * @return 登录成功后的用户快照（不含敏感字段）
     * @throws DeployException 账号不存在、密码错误、账号停用或锁定时抛出
     */
    public LoginUser login(String username, String rawPassword) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .eq(User::getIsDeleted, 0));
        // 账号不存在与密码错误统一返回同一提示，避免泄露账号是否注册。
        if (user == null || !Pbkdf2PasswordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new DeployException(ResponseCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new DeployException(ResponseCode.FORBIDDEN, "账号已停用或锁定，请联系管理员");
        }
        // 更新最后登录时间；仅改该列，不影响其他字段。
        User updater = new User();
        updater.setId(user.getId());
        updater.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(updater);
        return new LoginUser(user.getId(), user.getUsername(), user.getNickname());
    }

    /**
     * 首次启动时若本地无任何用户，插入默认管理员。
     *
     * <p>幂等：仅在 user 表（未删除）为空时插入一次。默认密码为弱口令，
     * 仅用于过渡期首次登录，启动日志会告警提醒尽快修改。</p>
     */
    public void initAdminIfEmpty() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getIsDeleted, 0));
        if (count != null && count > 0) {
            return;
        }
        User admin = new User();
        admin.setId(IdGenerator.generateNumericId());
        admin.setUsername(DEFAULT_ADMIN_USERNAME);
        admin.setPasswordHash(Pbkdf2PasswordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
        admin.setNickname("系统管理员");
        admin.setStatus(1);
        userMapper.insert(admin);
        log.warn("===== 已初始化默认管理员账号 admin / admin123，这是弱口令，请登录后尽快修改密码！=====");
    }
}
