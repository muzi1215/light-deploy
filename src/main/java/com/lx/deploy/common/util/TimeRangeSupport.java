package com.lx.deploy.common.util;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.TimeRangeQuery;
import com.lx.deploy.common.response.ResponseCode;

import java.time.LocalDateTime;

/**
 * 创建时间区间条件的统一应用入口。
 *
 * <p>全平台所有列表的「创建时间区间」只有这一份实现：各 Service 的 {@code buildQueryWrapper}
 * 都调用 {@link #apply(LambdaQueryWrapper, SFunction, TimeRangeQuery)}，
 * 不各自写 {@code ge} / {@code le} 与区间校验。理由与凭据拼装、日志查看器一致——
 * 复制十遍的判空与校验，第十一遍总会漏掉「开始晚于截止」这类边界，
 * 而漏掉的表现是「查出来 0 条」而不是报错，属于最难被发现的一类问题。</p>
 *
 * <p><b>边界是闭区间</b>（{@code >=} 与 {@code <=}）：页面上选的是「9 月 1 日 00:00:00 到 9 月 30 日 23:59:59」，
 * 用户的心智模型是包含两端的；用开区间会让恰好落在边界上的记录消失，
 * 而这类消失只会发生在「正好那一刻」的记录上，排查时几乎不可能复现。</p>
 *
 * @author lx
 * @since 2026-09-27 17:30:00
 */
public final class TimeRangeSupport {

    private TimeRangeSupport() {
    }

    /**
     * 把时间区间条件追加到查询构造器上。
     *
     * <p>两端都为空时不做任何处理（等价于不筛选）；只传一端时退化为单边限制。
     * 开始晚于截止直接判参数错误：与其返回 0 条让人以为「这段时间没数据」，
     * 不如当场说清楚是条件填反了。</p>
     *
     * @param wrapper 查询构造器
     * @param column 实体的创建时间列引用，如 {@code Server::getCreateTime}
     * @param query 查询条件，允许为 null
     * @param <T> 实体类型
     * @throws DeployException 开始时间晚于截止时间时抛出
     */
    public static <T> void apply(LambdaQueryWrapper<T> wrapper,
                                 SFunction<T, ?> column,
                                 TimeRangeQuery query) {
        if (wrapper == null || query == null) {
            return;
        }
        apply(wrapper, column, query.getCreateTimeBegin(), query.getCreateTimeEnd());
    }

    /**
     * 把时间区间条件追加到查询构造器上。
     *
     * @param wrapper 查询构造器
     * @param column 实体的创建时间列引用
     * @param begin 起始时间（含），允许为 null
     * @param end 截止时间（含），允许为 null
     * @param <T> 实体类型
     * @throws DeployException 开始时间晚于截止时间时抛出
     */
    public static <T> void apply(LambdaQueryWrapper<T> wrapper,
                                 SFunction<T, ?> column,
                                 LocalDateTime begin,
                                 LocalDateTime end) {
        if (wrapper == null) {
            return;
        }
        if (begin != null && end != null && begin.isAfter(end)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "开始时间不能晚于截止时间");
        }
        wrapper.ge(begin != null, column, begin);
        wrapper.le(end != null, column, end);
    }
}
