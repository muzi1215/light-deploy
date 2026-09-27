package com.lx.deploy.common.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 列表查询的创建时间区间条件基类。
 *
 * <p>所有带查询区的分页列表都由它提供 {@code createTimeBegin} / {@code createTimeEnd}，
 * 由 Service 用 {@code TimeRangeSupport.apply} 落到对应实体的 {@code create_time} 上。
 * 放在基类而不是各 DTO 里各写一份，是因为「按创建时间筛」这件事对每一个列表都是同一个语义：
 * 字段散在各处必然出现命名分歧（有的叫 startTime 有的叫 createTimeBegin），
 * 而命名分歧会直接变成前端拼参数的 bug。</p>
 *
 * <p><b>只筛 {@code create_time}</b>，不引入「业务时间」维度：构建有 started_at / finished_at、
 * 任务有开始与结束时间，但那些列在排队或执行中时是空的，按它们筛会让“明明有数据却查不到”变成常态。
 * 创建时间对任何一行都一定存在，语义也最直白——「这段时间里新增了哪些」。</p>
 *
 * <p>本类只承载字段，不带任何查询逻辑：DTO 同时被新增 / 修改接口复用（项目约定），
 * 往里塞 MyBatis 的 wrapper 依赖会让「请求参数」和「查询条件」两种职责彻底纠缠在一起。</p>
 *
 * @author lx
 * @since 2026-09-27 17:30:00
 */
public abstract class TimeRangeQuery {

    /**
     * 创建时间起始（含）。
     *
     * <p>只传它表示「某时刻之后」，不传表示不限制下界。</p>
     */
    @Schema(description = "创建时间起始（含），格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeBegin;

    /**
     * 创建时间截止（含）。
     *
     * <p>只传它表示「某时刻之前」，不传表示不限制上界。</p>
     */
    @Schema(description = "创建时间截止（含），格式 yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTimeEnd;

    public LocalDateTime getCreateTimeBegin() {
        return createTimeBegin;
    }

    public void setCreateTimeBegin(LocalDateTime createTimeBegin) {
        this.createTimeBegin = createTimeBegin;
    }

    public LocalDateTime getCreateTimeEnd() {
        return createTimeEnd;
    }

    public void setCreateTimeEnd(LocalDateTime createTimeEnd) {
        this.createTimeEnd = createTimeEnd;
    }
}
