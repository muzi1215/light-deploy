package com.lx.deploy.audit.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段级变更明细。
 *
 * <p>回答「这一列从什么改成了什么」，是操作日志里唯一能直接证明变更内容的部分：
 * 请求参数只有新值，看不出改动前后的对照，而运维要查的往往正是
 * 「环境被从 vm-prod 改成了 lx-prod」这类单点变更。</p>
 *
 * <p>三个字段一律用字符串承载：值的类型在已经发生的事实面前没有意义，
 * 数字、枚举、JSON 配置统一转成文本后才能在页面上同表展示。</p>
 *
 * @author lx
 * @since 2026-09-27 01:15:00
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "字段级变更明细")
public class AuditFieldChangeVo {

    /**
     * 字段名，实体的属性名。
     *
     * <p>中文名由前端映射后展示：翻译表属于展示层职责，放在后端会让每次新增字段都要改后端。</p>
     */
    @Schema(description = "字段名")
    private String field;

    /**
     * 变更前的展示值。
     *
     * <p>取不到（新增记录、快照失败）时为占位符 {@code -}，留空会让页面分不清
     * 「原本没值」与「没记到」。</p>
     */
    @Schema(description = "变更前的值")
    private String oldValue;

    /**
     * 变更后的展示值；删除操作时为 {@code (已删除)}。
     */
    @Schema(description = "变更后的值")
    private String newValue;
}
