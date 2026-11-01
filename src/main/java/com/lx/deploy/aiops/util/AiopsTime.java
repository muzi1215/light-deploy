package com.lx.deploy.aiops.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AIops 联动的时间格式化。
 *
 * <p>两端不传时间戳、也不传「本地时间字符串」，一律传带时区偏移的 ISO8601
 * （如 {@code 2026-11-02T01:00:00+08:00}）：平台与 AIops 可能不在同一时区，
 * 传裸的 {@code LocalDateTime} 会在跨时区部署时把静默窗开到期前或到期后。</p>
 *
 * <p>平台运行在东八区，这里显式固定偏移而不是用系统默认时区——
 * 服务器时区被误改成 UTC 时，静默窗不该跟着漂 8 小时。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
public final class AiopsTime {

    /** 平台业务时区。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定格式，避免秒为 0 时被省略成 {@code HH:mm} 引起对端解析歧义。 */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private AiopsTime() {
    }

    /**
     * 把本地时间格式化为带偏移的 ISO8601。
     *
     * @param time 本地时间，为空时取当前时间
     * @return 形如 {@code 2026-11-02T01:00:00+08:00}
     */
    public static String iso(LocalDateTime time) {
        LocalDateTime base = time == null ? LocalDateTime.now() : time;
        return FORMATTER.format(base.atZone(ZONE));
    }

    /**
     * 当前时间之后若干分钟的 ISO8601。
     *
     * @param minutes 偏移分钟数
     * @return 带偏移的时间字符串
     */
    public static String isoAfterMinutes(int minutes) {
        return iso(LocalDateTime.now().plusMinutes(minutes));
    }

    /**
     * 截断过长文本，供错误信息入列。
     *
     * @param text  原始文本
     * @param limit 最大长度
     * @return 截断后的文本，入参为空时返回 null
     */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
