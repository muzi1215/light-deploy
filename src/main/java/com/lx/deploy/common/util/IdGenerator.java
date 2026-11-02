package com.lx.deploy.common.util;

import javax.lang.model.SourceVersion;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 平台统一 ID 生成工具。
 *
 * <p>数据库主键统一使用不带连字符的 32 位 UUID 字符串。所有业务代码和 MyBatis-Plus
 * 主键生成器都必须调用本类，避免各处出现不同的 ID 格式。</p>
 */
public final class IdGenerator {

    private IdGenerator() {
        // 工具类不允许实例化。
    }

    /**
     * 生成不带连字符的小写 UUID。
     *
     * @return 32 位小写 UUID 字符串
     */
    public static String generateUUID() {
        // UUID 标准格式包含四个连字符，数据库主键只保留 32 个十六进制字符。
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 生成不带连字符的大写 UUID。
     *
     * @return 32 位大写 UUID 字符串
     */
    public static String generateUUIDUpperCase() {
        // 先复用统一的小写生成逻辑，再转换为大写，保证长度和格式一致。
        return generateUUID().toUpperCase();
    }

    /**
     * 生成 6-8 位纯数字随机 ID。
     * 位数在 6、7、8 中随机，首位允许为 0（若需首位非 0 见方案三）。
     *
     * @return 6-8 位数字字符串
     */
    public static String generateNumericId() {
        // 位数在 [6, 8] 闭区间内随机
        int length = ThreadLocalRandom.current().nextInt(6, 9);
        StringBuilder sb = new StringBuilder(length);

        // 首位固定为 1-9，避免出现前导 0
        sb.append(ThreadLocalRandom.current().nextInt(1, 10));

        // 其余位为 0-9
        for (int i = 1; i < length; i++) {
            sb.append(ThreadLocalRandom.current().nextInt(10));
        }

        return sb.toString();
    }
}
