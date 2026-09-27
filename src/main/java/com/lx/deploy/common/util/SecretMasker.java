package com.lx.deploy.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 命令、文本与 JSON 的脱敏工具。
 *
 * <p>凡是可能含凭据明文的字符串（远程命令、异常消息、操作日志的请求参数）在落库或进任务日志前
 * 都必须过一遍这里。构建链路、项目连通性测试与操作日志切面共用同一份规则，避免出现
 * 「构建的日志脱敏了、测试的没脱敏」这类漏点。</p>
 *
 * <p>注意：本工具只是**兜底**。更可靠的做法是从一开始就不让凭据出现在命令里
 * （如镜像仓库密码走 {@code --password-stdin}、Git 私钥走临时文件），脱敏只用于
 * 无法避免的情形（令牌必须落在 URL 密码位）。</p>
 *
 * @author lx
 * @since 2026-09-26 02:10:00
 */
public final class SecretMasker {

    /** {@code -p 密码} / {@code --password 密码} 形式的命令行凭据。 */
    private static final Pattern PASSWORD_ARG = Pattern.compile("(?i)(-p|--password)\\s+\\S+");

    /** {@code https://用户名:密码@主机} 形式的 URL userinfo。 */
    private static final Pattern URL_USER_INFO = Pattern.compile("(https?)://[^@/\\s]+@");

    /**
     * JSON 里敏感键的字符串值，作为 Jackson 解析失败时的正则兜底。
     *
     * <p>只能覆盖 {@code "key": "字符串值"} 这一种形态，因此优先级低于
     * {@link #maskJsonFields(String)} 的树遍历；但它不需要能解析出合法 JSON，
     * 适合处理被截断或混入了非 JSON 内容的文本。</p>
     */
    private static final Pattern JSON_SENSITIVE_VALUE = Pattern.compile(
            "(?i)\"([^\"]*(?:password|credential|secret|token|privateKey|encrypted)[^\"]*)\"\\s*:\\s*\"[^\"]*\"");

    /**
     * 判定为敏感的键名片段，与代码生成器排除敏感查询字段的清单保持一致（AGENTS.md §11.3）。
     *
     * <p>用「包含」而不是「等于」：实际字段形如 {@code sshPassword}、{@code registryToken}，
     * 逐个枚举会漏，而误伤（把含 password 的键也脱掉）的代价远小于漏脱敏。</p>
     */
    private static final List<String> SENSITIVE_KEY_PARTS =
            List.of("password", "credential", "secret", "token", "privatekey", "encrypted");

    /**
     * JSON 解析与序列化，线程安全可静态复用。
     *
     * <p><b>必须注册 {@link JavaTimeModule}</b>：Jackson 2.21 起 {@code java.time} 类型默认要求显式处理器，
     * 未注册时序列化 {@code LocalDateTime} 会直接抛 {@code InvalidDefinitionException}
     * （报文中提示 add Module jackson-datatype-jsr310）。审计链路里到处都是时间字段——
     * 统一响应 {@code ApiResponse.timestamp}、实体基类的 create_time / update_time——
     * 一旦抛异常就会被 {@link #toJson(Object)} 吞成 null，表现为「接口明明有返回，日志里 json_result 却是空的」，
     * 更隐蔽的是 {@link #toTree(Object)} 同样失败，会让字段级变更明细永远采集不到。
     * 因此这里注册模块并关掉时间戳输出（写数组不利于人读）。</p>
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** 脱敏后使用的占位文本。 */
    private static final String MASK = "***";

    private SecretMasker() {
    }

    /**
     * 把命令中的凭据片段替换为 {@code ***}。
     *
     * <p>覆盖两类：命令行上的密码参数，以及 URL 里的 userinfo（含令牌注入后的地址）。
     * 识别不到任何凭据时原样返回。</p>
     *
     * @param command 待脱敏的命令，允许为 null
     * @return 脱敏后的文本；入参为 null 时返回 null
     */
    public static String maskSecret(String command) {
        if (command == null) {
            return null;
        }
        String masked = PASSWORD_ARG.matcher(command).replaceAll("$1 " + MASK);
        return URL_USER_INFO.matcher(masked).replaceAll("$1://" + MASK + "@");
    }

    /**
     * 把 JSON 中敏感键的值替换为 {@code ***}，供操作日志记录请求参数使用。
     *
     * <p>先把 JSON 解析成树再递归替换，而不是正则替换文本：键名可能出现在任意层级（数组里的对象、
     * 嵌套的 runtime_config_json），正则挡不住；树遍历则与结构无关。</p>
     *
     * <p>解析失败（文本不是合法 JSON、已被截断）时退回正则兜底：宁可只盖住一部分，
     * 也不能因为解析异常就把原文原样返回——那等于脱敏被静默跳过。</p>
     *
     * @param json 待脱敏的 JSON 文本，允许为 null 或空
     * @return 脱敏后的 JSON 文本；入参为 null 时返回 null
     */
    public static String maskJsonFields(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            JsonNode root = JSON.readTree(json);
            maskNode(root);
            return JSON.writeValueAsString(root);
        } catch (Exception ex) {
            // 解析不了就走正则兜底，绝不返回原文。
            return JSON_SENSITIVE_VALUE.matcher(json).replaceAll("\"$1\":\"" + MASK + "\"");
        }
    }

    /**
     * 递归遍历 JSON 树，替换敏感键的值。
     *
     * <p>命中敏感键时直接覆盖整值（不再往里遍历）：键名已经说明该值整体敏感，
     * 递归进子结构只会让「脱了一半」的对象看起来像正常数据。</p>
     *
     * @param node 当前节点，会被原地修改
     */
    private static void maskNode(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : (ArrayNode) node) {
                maskNode(child);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (isSensitiveKey(field.getKey())) {
                object.put(field.getKey(), MASK);
            } else {
                maskNode(field.getValue());
            }
        }
    }

    /**
     * 判断键名是否属于敏感字段。
     *
     * <p>对外暴露：操作日志的字段级变更明细要用同一套判定跳过敏感字段，
     * 不能出现「请求参数里的密码被脱敏、变更明细里的同一字段却留下原文」这种漏点。</p>
     *
     * @param key JSON 键名，允许为 null
     * @return true 表示键名包含敏感片段，需要脱敏或跳过
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEY_PARTS.stream().anyMatch(lower::contains);
    }

    /**
     * 把任意对象序列化为 JSON 文本。
     *
     * <p>审计链路专用，刻意<b>不使用 Spring 容器管理的 {@code ObjectMapper}</b>：
     * Spring Boot 4 起容器只提供 Jackson 3（{@code tools.jackson}）的实例，而带全局 Jackson 配置
     * （命名策略、日期格式、混入）的序列化器一旦被业务调整，历史日志的可读性就会跟着变；
     * 审计文本要能独立解读，所以这里用一个干净的静态实例。</p>
     *
     * @param value 待序列化对象，允许为 null
     * @return JSON 文本；序列化失败时返回 null，由调用方决定如何呈现
     */
    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 把对象转换为 JSON 树，供调用方按字段遍历。
     *
     * <p>与被 {@link #maskJsonFields(String)} 处理的文本走同一个 {@code ObjectMapper}，
     * 保证「先取树再比较」与「先转文本再脱敏」两条路径的结构判定一致。</p>
     *
     * @param value 待转换对象，允许为 null
     * @return JSON 树；入参为 null 或转换失败时返回 null
     */
    public static JsonNode toTree(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.valueToTree(value);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 把 JSON 文本解析为树，供需要按字段读取已落库文本的调用方使用。
     *
     * <p>典型场景是操作日志的变更明细回读。解析失败返回 null 而不是抛出：
     * 一条读不出来的明细不该让整个详情页失败，调用方降级为空集合即可。</p>
     *
     * @param json 待解析文本，允许为 null 或空
     * @return JSON 树；文本为空或解析失败时返回 null
     */
    public static JsonNode parseTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }
}
