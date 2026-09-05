package com.lx.deploy.common.generator;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.config.builder.CustomFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Light Deploy MyBatis-Plus 代码生成器。
 *
 * <p>生成器使用项目根目录的 generator.properties 配置数据库、模块、表名和覆盖策略。
 * 生成实体时继承 BaseEntity，生成 Service 实现类、Mapper 和 Controller，并使用项目自定义模板。</p>
 */
public final class CodeGenerator {

    private CodeGenerator() {
        // 工具类不允许被实例化。
    }

    /**
     * 执行代码生成流程。
     *
     * <p>配置文件读取优先级为：main 方法第一个参数、generator.config 系统属性、
     * 项目根目录 generator.properties。配置不存在或关键字段缺失时直接抛出异常，
     * 避免使用错误的默认数据库和表名生成代码。</p>
     *
     * @param args 第一个参数可以传入 properties 文件路径
     */
    public static void main(String[] args) {
        // 根据启动参数和系统属性确定配置文件位置。
        Path projectPath = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path configPath = resolveConfigPath(args, projectPath);
        Properties properties = loadProperties(configPath);

        // 从外置配置读取连接、模块和生成范围，密码不再硬编码在 Java 源码中。
        String url = required(properties, "generator.datasource.url");
        String username = required(properties, "generator.datasource.username");
        String password = required(properties, "generator.datasource.password");
        String moduleName = required(properties, "generator.module");
        String tableNames = required(properties, "generator.tables");
        String packageName = properties.getProperty("generator.package", "com.lx.deploy");
        boolean overwrite = Boolean.parseBoolean(properties.getProperty("generator.overwrite", "false"));

        // DTO 和 VO 属于自定义输出文件，分别放在当前模块 model/dto 与 model/vo 目录。
        CustomFile.Builder dtoFileBuilder = new CustomFile.Builder()
                .fileName("Dto.java")
                .templatePath("/templates/dto.java.ftl")
                .packageName("model.dto");
        CustomFile.Builder voFileBuilder = new CustomFile.Builder()
                .fileName("Vo.java")
                .templatePath("/templates/vo.java.ftl")
                .packageName("model.vo");
        if (overwrite) {
            // 自定义 DTO、VO 文件遵循与其他生成文件相同的显式覆盖开关。
            dtoFileBuilder.enableFileOverride();
            voFileBuilder.enableFileOverride();
        }

        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> {
                    // 统一生成作者、输出目录和数据库注释时间格式。
                    builder.author(properties.getProperty("generator.author", "lx"))
                            .outputDir(projectPath.resolve("src/main/java").toString())
                            .enableSwagger()
                            .commentDate("yyyy-MM-dd HH:mm:ss");
                })
                .packageConfig(builder -> builder
                        // 模块名隔离不同业务生成的 model、mapper、service 和 controller 包。
                        .parent(packageName)
                        .moduleName(moduleName)
                        .entity("model")
                        .serviceImpl("service")
                        .mapper("mapper")
                        .controller("controller")
                        .pathInfo(Collections.singletonMap(OutputFile.xml,
                                projectPath.resolve("src/main/resources/mapper").toString())))
                .strategyConfig(builder -> {
                    // 将逗号分隔的表名转换为生成器需要的表集合。
                    builder.addInclude(Arrays.stream(tableNames.split(","))
                            .map(String::trim)
                            .filter(name -> !name.isEmpty())
                            .toList());
                    // 任务表去掉 lds_ 前缀后生成 Task、TaskLog 等 Java 类名。
                    if ("task".equalsIgnoreCase(moduleName)) {
                        builder.addTablePrefix("lds_");
                    }
                    // 3.5.17 将覆盖开关放在各类文件的策略 Builder 上，而不是 GlobalConfig。
                    var entityBuilder = builder.entityBuilder()
                            .superClass("com.lx.deploy.common.model.BaseEntity")
                            .addSuperEntityColumns("id", "create_time", "update_time", "is_deleted")
                            .idType(IdType.ASSIGN_UUID)
                            .enableLombok()
                            .enableTableFieldAnnotation()
                            .fieldUseJavaDoc(true)
                            .logicDeleteColumnName("is_deleted")
                            .disableSerialVersionUID();
                    var serviceBuilder = builder.serviceBuilder()
                            .disableService()
                            .formatServiceImplFileName("%sService");
                    var controllerBuilder = builder.controllerBuilder().enableRestStyle();
                    var mapperBuilder = builder.mapperBuilder().enableMapperAnnotation();
                    if (overwrite) {
                        // 只有配置明确开启时才覆盖已有文件，默认保护手写代码。
                        entityBuilder.enableFileOverride();
                        serviceBuilder.enableFileOverride();
                        controllerBuilder.enableFileOverride();
                        mapperBuilder.enableFileOverride();
                    }
                })
                .injectionConfig(builder -> builder.customFile(List.of(
                        dtoFileBuilder.build(), voFileBuilder.build())))
                .templateConfig(builder -> builder
                        .entity("/templates/entity.java")
                        .serviceImpl("/templates/serviceImpl.java")
                        .controller("/templates/controller.java"))
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();

        // 后端生成完成后，继续生成前端 API 与 Vue 页面到项目独立目录，供人工审查后拷入 lighit-deploy-ui。
        FrontendCodeGenerator.generate(projectPath, properties);
    }

    /**
     * 解析生成器配置文件路径。
     *
     * @param args 启动参数；第一个参数可直接指定配置文件
     * @param projectPath 项目根目录
     * @return 实际配置文件路径
     */
    private static Path resolveConfigPath(String[] args, Path projectPath) {
        // 第一个参数适合在多个数据库或多个模块配置之间切换。
        if (args.length > 0 && !args[0].isBlank()) {
            return Paths.get(args[0]).toAbsolutePath().normalize();
        }
        // 系统属性适合 IDEA Run Configuration 或 Maven 命令行传入。
        String systemConfig = System.getProperty("generator.config");
        if (systemConfig != null && !systemConfig.isBlank()) {
            return Paths.get(systemConfig).toAbsolutePath().normalize();
        }
        // 默认读取项目根目录的 generator.properties。
        return projectPath.resolve("generator.properties");
    }

    /**
     * 读取 properties 配置文件。
     *
     * @param path 配置文件路径
     * @return 已加载的配置对象
     * @throws IllegalStateException 配置文件不存在或无法读取时抛出
     */
    private static Properties loadProperties(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("代码生成器配置文件不存在：" + path
                    + "，请复制 generator.properties.example 为 generator.properties 后修改");
        }
        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(path)) {
            properties.load(inputStream);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("代码生成器配置文件读取失败：" + path, ex);
        }
    }

    /**
     * 获取必填配置项。
     *
     * @param properties 配置集合
     * @param key 配置键
     * @return 去除首尾空白后的配置值
     * @throws IllegalStateException 配置不存在或为空时抛出
     */
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("代码生成器缺少必填配置：" + key);
        }
        return value.trim();
    }
}
