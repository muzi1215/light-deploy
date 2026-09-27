package com.lx.deploy.common.annotation;

import com.lx.deploy.common.enums.audit.BusinessType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标注在 Controller 方法上，声明该操作需要留痕。
 *
 * <p>切面 {@code OperLogAspect} 拦截所有带本注解的方法，记录操作人、模块、类型、请求方式、
 * 地址、IP、参数、结果、耗时与成败，写入 {@code audit_log}。业务代码不需要（也不应该）
 * 自己拼日志——「哪些操作要留痕」全部收敛到这一处声明。</p>
 *
 * <p>使用约定：只标注<b>会改变状态</b>的接口（新增 / 修改 / 删除 / 发布 / 回滚 / 取消 / 触发构建）。
 * 查询类接口不标注：它们量大且没有追溯价值，全量记录只会让操作日志变成另一份访问日志。</p>
 *
 * <p>失败同样落库：一次失败的新增往往比成功的更值得查（谁在什么时候试图改了什么、为什么被拒）。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /**
     * 操作模块名称，面向人阅读，如「服务器管理」「发布管理」。
     *
     * @return 操作模块名称
     */
    String title() default "";

    /**
     * 操作类型。
     *
     * @return 业务类型枚举，默认 {@link BusinessType#OTHER}
     */
    BusinessType businessType() default BusinessType.OTHER;

    /**
     * 是否记录请求参数。
     *
     * <p>参数里含凭据明文（如新建服务器、保存凭据）时设为 false：此时连脱敏后的 JSON 都不该落库，
     * 宁可少记一项，也不要让明文有落库的机会。默认 true（写入前仍会按敏感键脱敏）。</p>
     *
     * @return true 记录参数，false 完全跳过
     */
    boolean recordParam() default true;

    /**
     * 是否记录返回结果。
     *
     * <p>列表类写操作返回内容较长且与追溯无关时设为 false。默认 true，写入前会截断。</p>
     *
     * @return true 记录返回结果，false 跳过
     */
    boolean recordResult() default true;

    /**
     * 资源类型，用于采集「字段级变更明细」。
     *
     * <p>取值是 Mapper bean 名去掉 {@code Mapper} 后缀的形式：服务器 {@code server}、
     * 部署目标 {@code projectEnvironmentTarget}、凭据 {@code credential} 等，
     * 由 {@code ResourceDiffRecorder} 用它索引对应的 Mapper。</p>
     *
     * <p><b>留空表示不采集变更明细</b>（默认）。只在「改已有记录」的方法上声明即可——
     * 新增操作没有旧值可比，比出来只会是一列「- → 新值」的噪音。</p>
     *
     * <p>之所以由注解声明而不是从请求体反推：资源类型属于方法本身的语义，
     * 反推只能靠 URI 正则，接口一改路径就失效，而这种失效是静默的（明细悄悄没了）。</p>
     *
     * @return 资源类型标识；空串表示不记录变更明细
     */
    String resourceType() default "";

    /**
     * 资源主键所在的方法参数名。
     *
     * <p>默认 {@code id}，与所有更新接口的 {@code @PathVariable} 命名一致。
     * 参数名由 Spring 的参数名发现机制解析，取不到时本次不记录变更明细（不报错）。</p>
     *
     * @return 承载资源主键的参数名
     */
    String resourceIdParam() default "id";
}
