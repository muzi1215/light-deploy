package com.lx.deploy.common.model;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/** 与具体 ORM 分页对象解耦的接口分页响应。 */
public record PageResult<T>(
        long pageNum,
        long pageSize,
        long total,
        long pages,
        List<T> records) {

    /**
     * 将 MyBatis-Plus 分页结果转换为接口层分页结果。
     *
     * <p>转换后前端不需要感知 MyBatis-Plus 的 IPage 类型，后续替换持久层实现时
     * 也不会影响接口响应结构。</p>
     *
     * @param page MyBatis-Plus 返回的分页对象
     * @param <T> 分页记录类型
     * @return 包含页码、每页数量、总数、总页数和数据列表的分页结果
     */
    public static <T> PageResult<T> from(IPage<T> page) {
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), page.getRecords());
    }
}
