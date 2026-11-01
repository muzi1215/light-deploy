package com.lx.deploy.user.bootstrap;

import com.lx.deploy.user.service.AuthService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动引导：初始化默认管理员。
 *
 * <p>应用启动完成后执行，仅当本地 user 表为空时插入默认管理员（admin / admin123），
 * 保证过渡期平台首次部署后能登录。表已有数据时直接跳过，可重复执行。</p>
 */
@Component
public class AdminInitRunner implements CommandLineRunner {

    private final AuthService authService;

    public AdminInitRunner(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(String... args) {
        authService.initAdminIfEmpty();
    }
}
