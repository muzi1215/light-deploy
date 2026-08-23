package com.lx.deploy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;


@SpringBootApplication
@MapperScan("com.lx.deploy.**.mapper")
@Slf4j
public class LightDeployApplication {

    /** 启动 Light Deploy Spring Boot 应用。 */
    public static void main(String[] args) {
        SpringApplication.run(LightDeployApplication.class, args);
        log.info("轻量化DevOps部署平台.....");
    }

}
