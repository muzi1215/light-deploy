package com.lx.deploy.config;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.lx.deploy.common.util.IdGenerator;
import org.springframework.stereotype.Component;

/**
 * MyBatis-Plus 字符串主键生成器。
 *
 * <p>Spring Boot 启动时会自动发现该 Bean，并将其注册到 MyBatis-Plus 全局配置。
 * 对于实体上的 {@code IdType.ASSIGN_UUID}，MyBatis-Plus 会调用 {@link #nextUUID(Object)}，
 * 从而统一使用项目的 {@link IdGenerator}。</p>
 */
@Component
public class UuidIdentifierGenerator implements IdentifierGenerator {

    /**
     * 生成数据库字符串主键。
     *
     * @param entity 待插入的实体对象
     * @return 32 位不带连字符的小写 UUID
     */
    @Override
    public String nextUUID(Object entity) {
        return IdGenerator.generateUUID();
    }

    /**
     * 兼容可能使用 ASSIGN_ID 的数值主键实体。
     *
     * <p>当前项目业务表统一使用 VARCHAR(32) 字符串主键，因此该分支不会用于现有表；
     * 保留默认实现是为了避免外部扩展实体使用数值主键时被错误影响。</p>
     *
     * @param entity 待插入的实体对象
     * @return MyBatis-Plus 默认数值主键
     */
    @Override
    public Number nextId(Object entity) {
        return DefaultIdentifierGenerator.getInstance().nextId(entity);
    }
}
