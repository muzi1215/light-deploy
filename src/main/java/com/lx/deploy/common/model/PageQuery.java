package com.lx.deploy.common.model;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 通用分页请求，页码从 1 开始。 */
public record PageQuery(
        @Min(value = 1, message = "页码不能小于1") long pageNum,
        @Min(value = 1, message = "每页数量不能小于1")
        @Max(value = 100, message = "每页数量不能超过100") long pageSize) {

    /**
     * 规范化分页参数。
     *
     * <p>Spring 未传递分页字段时，基础类型会得到0，因此在校验前将0转换为默认值。
     * 显式传递负数或超过100的每页数量仍由 Bean Validation 拒绝。</p>
     */
    public PageQuery {
        // 未传页码时从第一页开始查询。
        if (pageNum == 0) {
            pageNum = 1;
        }
        // 未传每页数量时默认查询10条记录。
        if (pageSize == 0) {
            pageSize = 10;
        }
    }

    /**
     * 将通用分页参数转换为 MyBatis-Plus 分页对象。
     *
     * @param <T> 分页记录的实体类型
     * @return 可直接传入 MyBatis-Plus Service.page 方法的分页对象
     */
    public <T> Page<T> toPage() {
        return Page.of(pageNum, pageSize);
    }
}
