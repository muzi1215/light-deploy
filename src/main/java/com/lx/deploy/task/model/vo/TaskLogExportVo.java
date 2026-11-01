package com.lx.deploy.task.model.vo;

/**
 * 任务执行日志导出结果。
 *
 * <p>日志导出是纯文本附件下载，不是 JSON 响应，因此本类型不参与 Jackson 序列化：
 * 它只在 Service 与 Controller 之间搬运「建议的下载文件名」与「文本正文」两段内容，
 * 由 Controller 拼装 {@code Content-Disposition} 与响应体。之所以单独定义类型而不是
 * 直接返回字符串，是为了让文件名与正文成对传递——文件名由服务端按任务编号统一生成，
 * 避免 Controller 再查一次任务，也避免前端各自拼一套命名规则。</p>
 *
 * @param fileName 建议的下载文件名，形如 {@code 20260925155150123456-执行日志.txt}
 * @param content  导出正文，UTF-8 纯文本，含任务头部摘要与全部日志行
 * @author lx
 * @since 2026-09-25 17:05:00
 */
public record TaskLogExportVo(String fileName, String content) {
}
