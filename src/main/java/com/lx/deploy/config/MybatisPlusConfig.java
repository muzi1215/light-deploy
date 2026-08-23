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
                // 只更新 updateTime，不修改原始 createTime。
                strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
