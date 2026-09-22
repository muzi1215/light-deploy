package com.lx.deploy.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/** MyBatis-Plus 分页、逻辑删除和审计时间字段配置。 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 创建 MyBatis-Plus 拦截器链并注册 MySQL 分页插件。
     *
     * <p>分页拦截器会根据 Page 参数自动生成 count 查询和 limit 语句，
     * DbType 明确指定为 MySQL，避免运行时自动识别数据库类型。</p>
     *
     * @return 配置完成的 MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 创建统一拦截器容器，后续乐观锁等插件也在此处按顺序加入。
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 注册 MySQL 分页插件，使 IService.page 等分页方法真正生成分页 SQL。
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 创建基础审计时间字段的自动填充处理器。
     *
     * <p>新增数据时填充 createTime 和 updateTime，更新数据时只刷新 updateTime。
     * 实体字段必须配置相应的 FieldFill 策略，处理器才会执行填充。</p>
     *
     * <p>更新时间必须使用「无条件覆盖」而不是 strictUpdateFill：修改类 Service 的写法是
     * 先从数据库查出实体、再覆盖 DTO 字段后 updateById，此时实体上的 updateTime 已经是
     * 库里的旧值（非 null），而 strictUpdateFill 只在字段为 null 时才填充，结果旧值被显式
     * 写进 SET 子句，MySQL 的 ON UPDATE CURRENT_TIMESTAMP 因为「列被显式赋值」而不再生效，
     * 表现为「改了数据但 update_time 不变」。</p>
     *
     * @return MyBatis-Plus 元对象字段填充处理器
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            /**
             * 在执行 INSERT 前填充创建时间和更新时间。
             *
             * @param metaObject 当前待持久化实体的 MyBatis 元对象
             */
            @Override
            public void insertFill(MetaObject metaObject) {
                // strictInsertFill 只在目标字段为空且配置为 INSERT 填充时写入数据。
                strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            /**
             * 在执行 UPDATE 前刷新更新时间。
             *
             * @param metaObject 当前待更新实体的 MyBatis 元对象
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                // 只刷新 updateTime，不修改原始 createTime。
                // 无条件覆盖：实体上的 updateTime 可能带着从库里读出的旧值，strictUpdateFill 会因此跳过填充。
                if (metaObject.hasSetter("updateTime")) {
                    metaObject.setValue("updateTime", LocalDateTime.now());
                }
            }
        };
    }
}
