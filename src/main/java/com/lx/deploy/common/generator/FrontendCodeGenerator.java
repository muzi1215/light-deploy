package com.lx.deploy.common.generator;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Light Deploy 前端代码生成器。
 *
 * <p>作为 {@link CodeGenerator} 的补充步骤，读取与后端生成器相同的外置配置和数据库元数据，
 * 基于 {@code frontend-api.ts.ftl} 与 {@code frontend-index.vue.ftl} 两个模板，
 * 为模块内的每张业务表生成对应的 TypeScript API 与 Vue3 列表页。</p>
 *
 * <p>生成结果默认输出到项目根目录 {@code generated-ui/}（可通过
 * {@code generator.frontend.output} 配置），目录结构为：</p>
 * <pre>
 * generated-ui/
 *   api/{module}.ts                 模块内所有表的类型定义与接口函数
 *   views/{entityPath}/index.vue    每张表一个标准 CRUD 列表页
 * </pre>
 *
 * <p>生成的文件是待人工审查的骨架：字段标签、表单控件、校验规则和枚举渲染由模板按
 * 数据库类型做启发式推断，拷入 {@code lighit-deploy-ui/src/} 前需要按业务精化。</p>
 *
 * <p><b>输出通道说明</b>：本类是开发期命令行工具，仅由 {@link CodeGenerator} 的 main 方法调用，
 * 进度信息统一走标准输出 {@code System.out}，<b>不是</b>运行时日志。后续维护时不要把它改成
 * logger，也不要把它注册成 Spring Bean——它不参与任何线上发布链路。</p>
 */
public final class FrontendCodeGenerator {

    /** 数据库数值类型集合，映射为 TypeScript 的 number 类型。 */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "int", "tinyint", "smallint", "mediumint", "bigint",
            "decimal", "numeric", "float", "double", "real", "bit");

    /** 数据库长文本类型集合，映射为表单 textarea 控件。 */
    private static final Set<String> TEXT_TYPES = Set.of("text", "tinytext", "mediumtext", "longtext");

    /** 搜索表单最多渲染的文本框数量，避免页面搜索条件过于拥挤。 */
    private static final int MAX_SEARCH_FIELDS = 4;

    private FrontendCodeGenerator() {
        // 工具类不允许被实例化。
    }

    /**
     * 执行前端代码生成流程。
     *
     * <p>该方法由 {@link CodeGenerator#main(String[])} 在后端生成完成后调用，
     * 复用同一份 properties 中的数据库连接、模块和表名配置。前端生成不修改后端生成产物。</p>
     *
     * @param projectPath 项目根目录，用于解析输出目录
     * @param properties  与后端生成器共享的外置配置
     */
    public static void generate(Path projectPath, Properties properties) {
        String url = required(properties, "generator.datasource.url");
        String username = required(properties, "generator.datasource.username");
        String password = required(properties, "generator.datasource.password");
        String module = required(properties, "generator.module");
        String tableNames = required(properties, "generator.tables");
        boolean overwrite = Boolean.parseBoolean(properties.getProperty("generator.overwrite", "false"));
        String outputDir = properties.getProperty("generator.frontend.output", "generated-ui").trim();

        List<String> tables = Arrays.stream(tableNames.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        Path outputRoot = projectPath.resolve(outputDir).normalize();

        // 前端元数据只读数据库，不复用 MyBatis-Plus 的内部表模型。
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            String schema = connection.getCatalog();
            Map<String, String> tableComments = readTableComments(connection, schema, tables);
            List<Map<String, Object>> entities = new ArrayList<>();
            for (String table : tables) {
                List<ColumnMeta> columns = readColumns(connection, schema, table);
                entities.add(buildEntity(module, table, tableComments.get(table), columns));
            }
            render(entities, module, outputRoot, overwrite);
            System.out.println("前端代码已生成到目录：" + outputRoot);
        } catch (SQLException ex) {
            throw new IllegalStateException("前端代码生成器读取数据库元数据失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 读取目标表的表注释。
     *
     * @param connection 数据库连接
     * @param schema     数据库名
     * @param tables     目标表名集合
     * @return 表名到表注释的映射，注释缺失时返回 null
     * @throws SQLException 查询信息架构失败时抛出
     */
    private static Map<String, String> readTableComments(Connection connection, String schema, List<String> tables)
            throws SQLException {
        String placeholders = tables.stream().map(t -> "?").collect(Collectors.joining(","));
        String sql = "SELECT TABLE_NAME, TABLE_COMMENT FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (" + placeholders + ")";
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            int index = 2;
            for (String table : tables) {
                ps.setString(index++, table);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("TABLE_NAME"), rs.getString("TABLE_COMMENT"));
                }
            }
        }
        return result;
    }

    /**
     * 读取单张表的列元数据，按数据库序号升序返回。
     *
     * @param connection 数据库连接
     * @param schema     数据库名
     * @param table      表名
     * @return 列元数据列表
     * @throws SQLException 查询信息架构失败时抛出
     */
    private static List<ColumnMeta> readColumns(Connection connection, String schema, String table) throws SQLException {
        String sql = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        List<ColumnMeta> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("COLUMN_NAME");
                    String dataType = rs.getString("DATA_TYPE");
                    boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
                    String defaultValue = rs.getString("COLUMN_DEFAULT");
                    String extra = rs.getString("EXTRA");
                    String comment = rs.getString("COLUMN_COMMENT");
                    result.add(new ColumnMeta(name, dataType, nullable, defaultValue, extra, comment));
                }
            }
        }
        return result;
    }

    /**
     * 把一张表的元数据构建为 FreeMarker 数据模型。
     *
     * <p>字段分类规则：基础字段和审计字段不进入前端；敏感内容字段（密文、哈希、Token、
     * 私钥）不进入任何前端接口；其余字段进入 API 类型定义，并按控件类型分配到搜索、表格和表单。</p>
     *
     * @param module       模块名，用于任务域表的 lds_ 前缀剥离
     * @param tableName    数据库表名
     * @param tableComment 表注释
     * @param columns      列元数据
     * @return 单表对应的 FreeMarker 数据模型
     */
    private static Map<String, Object> buildEntity(String module, String tableName, String tableComment,
                                                   List<ColumnMeta> columns) {
        // 任务域表剥离 lds_ 前缀后再转类名，与后端生成器保持一致。
        String classTableName = "task".equalsIgnoreCase(module) && tableName.startsWith("lds_")
                ? tableName.substring("lds_".length())
                : tableName;
        String entityName = toPascalCase(classTableName);
        String entityPath = decapitalize(entityName);
        String comment = (tableComment == null || tableComment.isBlank()) ? entityName : tableComment.trim();

        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> searchFields = new ArrayList<>();
        List<Map<String, Object>> tableFields = new ArrayList<>();
        List<Map<String, Object>> normalFormFields = new ArrayList<>();
        List<Map<String, Object>> textareaFormFields = new ArrayList<>();

        for (ColumnMeta column : columns) {
            if (isExcludedColumn(column.name()) || isSensitiveColumn(column.name())) {
                continue;
            }
            String camelName = toCamelCase(column.name());
            String control = resolveControl(column.name(), column.dataType());
            String tsType = resolveTsType(column.dataType());
            String label = resolveLabel(column.comment(), camelName);
            boolean required = resolveRequired(column.nullable(), column.defaultValue(), column.extra(), control);

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("camelName", camelName);
            field.put("label", label);
            field.put("comment", (column.comment() == null || column.comment().isBlank()) ? label : column.comment().trim());
            field.put("tsType", tsType);
            field.put("nullable", column.nullable());
            field.put("control", control);
            field.put("required", required);
            fields.add(field);

            if ("textarea".equals(control)) {
                // 文本域不参与搜索和表格，且统一放到表单最底部（遵守 AGENTS.md §20.2）。
                textareaFormFields.add(field);
            } else {
                normalFormFields.add(field);
                tableFields.add(field);
                if ("input".equals(control) && searchFields.size() < MAX_SEARCH_FIELDS) {
                    searchFields.add(field);
                }
            }
        }

        List<Map<String, Object>> formFields = new ArrayList<>(normalFormFields);
        formFields.addAll(textareaFormFields);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("comment", comment);
        entity.put("entityName", entityName);
        entity.put("entityPath", entityPath);
        entity.put("fields", fields);
        entity.put("searchFields", searchFields);
        entity.put("tableFields", tableFields);
        entity.put("formFields", formFields);
        return entity;
    }

    /**
     * 使用 FreeMarker 渲染前端模板并写入输出目录。
     *
     * @param entities 模块内所有实体的数据模型
     * @param module   模块名
     * @param outputRoot 前端输出根目录
     * @param overwrite 是否覆盖已存在文件
     */
    private static void render(List<Map<String, Object>> entities, String module, Path outputRoot, boolean overwrite) {
        Configuration configuration = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setClassLoaderForTemplateLoading(FrontendCodeGenerator.class.getClassLoader(), "/templates");

        // 1. 模块级 API 文件：{module}.ts，包含模块内所有表的类型与接口函数。
        Map<String, Object> apiModel = new HashMap<>();
        apiModel.put("module", module);
        apiModel.put("entities", entities);
        Path apiFile = outputRoot.resolve("api").resolve(module + ".ts");
        writeRendered(configuration, "frontend-api.ts.ftl", apiModel, apiFile, overwrite);

        // 2. 每张表一个 Vue 列表页：views/{entityPath}/index.vue。
        for (Map<String, Object> entity : entities) {
            Map<String, Object> vueModel = new HashMap<>();
            vueModel.put("module", module);
            vueModel.put("comment", entity.get("comment"));
            vueModel.put("entityName", entity.get("entityName"));
            vueModel.put("entityPath", entity.get("entityPath"));
            vueModel.put("viewDir", entity.get("entityPath"));
            vueModel.put("searchFields", entity.get("searchFields"));
            vueModel.put("tableFields", entity.get("tableFields"));
            vueModel.put("formFields", entity.get("formFields"));
            Path vueFile = outputRoot.resolve("views").resolve((String) entity.get("entityPath")).resolve("index.vue");
            writeRendered(configuration, "frontend-index.vue.ftl", vueModel, vueFile, overwrite);
        }
    }

    /**
     * 渲染单个模板并写入目标文件，未开启覆盖时跳过已存在的文件。
     *
     * @param configuration FreeMarker 配置
     * @param templateName  模板资源名
     * @param model         数据模型
     * @param target        目标文件路径
     * @param overwrite     是否覆盖已存在文件
     */
    private static void writeRendered(Configuration configuration, String templateName,
                                      Map<String, Object> model, Path target, boolean overwrite) {
        if (!overwrite && Files.exists(target)) {
            System.out.println("File [" + target.getFileName() + "] already exists. Overwrite mode is disabled.");
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Template template = configuration.getTemplate(templateName);
            StringWriter writer = new StringWriter();
            template.process(model, writer);
            Files.writeString(target, writer.toString(), StandardCharsets.UTF_8);
            System.out.println("Generated: " + target);
        } catch (IOException | TemplateException ex) {
            throw new IllegalStateException("前端模板渲染失败：" + templateName, ex);
        }
    }

    /**
     * 判断列是否为后端维护、前端无需关心的基础字段或审计字段。
     *
     * @param name 数据库列名
     * @return 是基础/审计字段时返回 true
     */
    private static boolean isExcludedColumn(String name) {
        return switch (name) {
            case "id", "create_time", "update_time", "is_deleted", "created_by", "updated_by" -> true;
            default -> false;
        };
    }

    /**
     * 判断列名是否代表敏感内容字段（密文、哈希、Token、私钥）。
     *
     * <p>这些字段要么只写不读、要么存的是加密/哈希后的内容，永远不会出现在前端 CRUD 接口中。</p>
     *
     * @param name 数据库列名
     * @return 是敏感字段时返回 true
     */
    private static boolean isSensitiveColumn(String name) {
        String lower = name.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("privatekey") || lower.contains("private_key")
                || lower.contains("ciphertext") || lower.contains("encrypted");
    }

    /**
     * 根据列名和数据库类型推导表单控件类型。
     *
     * <p>长文本/JSON/描述类字段映射为 textarea；布尔语义的 TINYINT 映射为 radio；
     * 数值类型映射为 number；其余字符串映射为 input。枚举字符串字段统一先映射为 input，
     * 由开发者在精化阶段按后端枚举补充分段控件。</p>
     *
     * @param name     数据库列名
     * @param dataType 数据库类型
     * @return input / number / textarea / radio 之一
     */
    private static String resolveControl(String name, String dataType) {
        String lower = name.toLowerCase();
        String type = dataType == null ? "" : dataType.toLowerCase();
        if (TEXT_TYPES.contains(type) || "json".equals(type) || lower.contains("json")
                || lower.equals("description") || lower.equals("remark") || lower.contains("content")) {
            return "textarea";
        }
        if ("tinyint".equals(type)
                && (lower.startsWith("is_") || lower.endsWith("_enabled") || lower.endsWith("_flag") || lower.startsWith("has_"))) {
            return "radio";
        }
        if (NUMERIC_TYPES.contains(type)) {
            return "number";
        }
        return "input";
    }

    /**
     * 根据数据库类型推导 TypeScript 类型。
     *
     * @param dataType 数据库类型
     * @return number 或 string
     */
    private static String resolveTsType(String dataType) {
        String type = dataType == null ? "" : dataType.toLowerCase();
        return NUMERIC_TYPES.contains(type) ? "number" : "string";
    }

    /**
     * 从列注释提取表单/表格展示标签。
     *
     * <p>列注释常形如"运行时：DOCKER/JAR/STATIC"，标签取冒号前的主干；
     * 注释缺失或为空时回退为驼峰字段名。</p>
     *
     * @param comment   列注释
     * @param camelName 驼峰字段名
     * @return 展示标签
     */
    private static String resolveLabel(String comment, String camelName) {
        if (comment == null || comment.isBlank()) {
            return camelName;
        }
        String text = comment.trim();
        int colon = -1;
        for (char separator : new char[]{'：', ':'}) {
            int index = text.indexOf(separator);
            if (index > 0) {
                colon = index;
                break;
            }
        }
        if (colon > 0) {
            text = text.substring(0, colon).trim();
        }
        return text.isBlank() ? camelName : text;
    }

    /**
     * 判断表单字段是否必填。
     *
     * <p>启发式规则：非空、无默认值、非自增且非文本域的字段视为必填，
     * 与后端 Service 校验形成镜像，最终以后端校验为准。</p>
     *
     * @param nullable     是否允许为空
     * @param defaultValue 数据库默认值
     * @param extra        列附加属性（如 auto_increment）
     * @param control      表单控件类型
     * @return 是否必填
     */
    private static boolean resolveRequired(boolean nullable, String defaultValue, String extra, String control) {
        if (nullable || "textarea".equals(control) || "auto_increment".equalsIgnoreCase(extra)) {
            return false;
        }
        return defaultValue == null || defaultValue.isBlank();
    }

    /**
     * 将下划线命名转为帕斯卡命名（类名）。
     *
     * @param name 下划线命名
     * @return 帕斯卡命名
     */
    private static String toPascalCase(String name) {
        StringBuilder builder = new StringBuilder();
        for (String part : name.toLowerCase().split("_")) {
            if (!part.isEmpty()) {
                builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return builder.toString();
    }

    /**
     * 将下划线命名转为驼峰命名（字段名）。
     *
     * @param name 下划线命名
     * @return 驼峰命名
     */
    private static String toCamelCase(String name) {
        return decapitalize(toPascalCase(name));
    }

    /**
     * 首字母小写。
     *
     * @param value 字符串
     * @return 首字母小写后的字符串
     */
    private static String decapitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * 获取必填配置项。
     *
     * @param properties 配置集合
     * @param key        配置键
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

    /**
     * 数据库列的元数据，供前端字段模型构建使用。
     *
     * @param name         列名
     * @param dataType     数据库类型
     * @param nullable     是否允许为空
     * @param defaultValue 数据库默认值
     * @param extra        列附加属性
     * @param comment      列注释
     */
    private record ColumnMeta(String name, String dataType, boolean nullable,
                              String defaultValue, String extra, String comment) {
    }
}
