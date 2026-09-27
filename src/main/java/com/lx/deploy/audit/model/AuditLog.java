package com.lx.deploy.audit.model;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lx.deploy.common.model.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 操作审计日志。
 *
 * <p>记录「谁在什么时候、通过哪个接口、改了什么、成功还是失败」，由 {@code @OperLog} 注解
 * 声明、AOP 切面统一写入，业务代码不手工调用——写日志这件事散落在各 Service 里必然会有遗漏，
 * 注解则让「哪些操作需要留痕」变成一处声明。</p>
 *
 * <p>本表是<b>追加写</b>的：只有新增，没有修改与删除接口（页面也只提供查询与详情）。
 * 审计记录被改写就失去了意义，软删除字段保留只为与统一基础实体一致。</p>
 *
 * <p>字段语义说明：{@code action} 复用早期「操作动作」列承载操作类型编码（INSERT/UPDATE/...），
 * 不再新增语义重叠的 business_type 列；{@code request_body} 存脱敏后的请求参数 JSON，
 * 敏感键会在写入前被替换成 {@code ***}（AGENTS.md §7）。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Data
@TableName("audit_log")
@Schema(description = "操作审计日志")
public class AuditLog extends BaseEntity {

    /**
     * 请求追踪 ID，由服务端维护。
     *
     * <p>与响应头 {@code X-Request-Id}、日志文件里的 {@code %X{requestId}} 是同一个值，
     * 拿着它可以把「一条操作日志」与「当次请求的全部日志」串起来，是排查的主线索。</p>
     */
    @TableField("trace_id")
    @Schema(description = "请求追踪ID")
    private String traceId;

    /**
     * 应用系统名称，由服务端从配置 {@code light-deploy.application-name} 读取并统一写入。
     *
     * <p>回答「这条操作发生在哪个系统」，是多系统共用同一张审计表时的分区依据。
     * 取值与接口无关，因此不由 {@code @OperLog} 注解声明——注解只描述「这次操作做了什么」，
     * 系统名属于部署实例的属性，放在配置里改一处即可（AGENTS.md §26.3）。</p>
     */
    @TableField("application")
    @Schema(description = "应用系统名称")
    private String application;

    /**
     * 操作者 ID，由服务端从请求头 {@code X-Operator-Id} 读取。
     *
     * <p>过渡期没有登录态，取不到时记 {@code system}；接入统一权限中心后由认证信息填充（AGENTS.md §16）。</p>
     */
    @TableField("operator_id")
    @Schema(description = "操作者ID")
    private String operatorId;

    /**
     * 操作者名称，由服务端从请求头 {@code X-Operator-Name} 读取，取不到时记 {@code system}。
     */
    @TableField("operator_name")
    @Schema(description = "操作者名称")
    private String operatorName;

    /**
     * 操作模块，来自 {@code @OperLog} 注解的 title，如「服务器管理」。
     *
     * <p>面向人阅读的归类，与数据库资源类型无关；未声明注解的接口不产生日志，本字段也就为空。</p>
     */
    @TableField("title")
    @Schema(description = "操作模块")
    private String title;

    /**
     * 操作类型编码，来自 {@code @OperLog} 注解的 businessType，取值见 {@code BusinessType}。
     *
     * <p>INSERT 新增 / UPDATE 修改 / DELETE 删除 / EXPORT 导出 / IMPORT 导入 /
     * EXECUTE 执行 / ROLLBACK 回滚 / CANCEL 取消 / OTHER 其他。</p>
     */
    @TableField("action")
    @Schema(description = "操作类型：INSERT/UPDATE/DELETE/EXPORT/IMPORT/EXECUTE/ROLLBACK/CANCEL/OTHER")
    private String businessType;

    /**
     * 资源类型，预留字段。
     *
     * <p>当前切面不解析具体资源，保持为空；后续按业务对象归档时再填充。</p>
     */
    @TableField("resource_type")
    @Schema(description = "资源类型，预留")
    private String resourceType;

    /**
     * 资源 ID，预留字段，语义同 {@link #resourceType}。
     */
    @TableField("resource_id")
    @Schema(description = "资源ID，预留")
    private String resourceId;

    /**
     * 被切的方法，格式「类全名#方法名」。
     *
     * <p>接口路径会被重构，方法签名不会——定位代码时它比 URI 更可靠。</p>
     */
    @TableField("method")
    @Schema(description = "操作方法，类全名#方法名")
    private String method;

    /**
     * HTTP 请求方法：GET / POST / PUT / DELETE。
     */
    @TableField("request_method")
    @Schema(description = "HTTP请求方法")
    private String requestMethod;

    /**
     * 请求地址，由服务端维护。
     *
     * <p>只记 URI 不记查询串：查询串可能携带凭据与业务敏感参数，
     * 与访问日志 {@code RequestTraceFilter} 的处理口径一致（AGENTS.md §14）。</p>
     */
    @TableField("request_uri")
    @Schema(description = "请求地址，不含查询串")
    private String requestUri;

    /**
     * 操作 IP，优先取 {@code X-Forwarded-For} 首段（前端经 Nginx 反代，直接取远端地址会全是网关地址）。
     */
    @TableField("oper_ip")
    @Schema(description = "操作IP")
    private String operIp;

    /**
     * 操作系统，由 User-Agent 粗粒度解析，识别不出时为空。
     */
    @TableField("os")
    @Schema(description = "操作系统")
    private String os;

    /**
     * 浏览器，由 User-Agent 粗粒度解析，识别不出时为空。
     */
    @TableField("browser")
    @Schema(description = "浏览器")
    private String browser;

    /**
     * 请求参数 JSON，由服务端维护。
     *
     * <p>写入前已按敏感键名（password / credential / secret / token / privateKey / ciphertext 等）
     * 把值替换为 {@code ***}，并对整段做长度截断。</p>
     */
    @TableField("request_body")
    @Schema(description = "请求参数JSON，敏感键已脱敏")
    private String operParam;

    /**
     * 返回结果 JSON，由服务端维护并截断。
     *
     * <p>只保留前若干字符：接口可能返回整页列表，全量落库既浪费空间也更容易带出敏感数据。</p>
     */
    @TableField("json_result")
    @Schema(description = "返回结果，超长截断")
    private String jsonResult;

    /**
     * 操作结果数字编码，取值 0 失败 / 1 成功，由服务端维护。
     *
     * <p>取值见 {@code AuditResult} 常量；业务异常、参数校验失败、未预期异常统一记为失败。</p>
     */
    @TableField("result")
    @Schema(description = "操作结果：0失败，1成功")
    private Integer result;

    /**
     * 失败原因，成功时为空，由服务端维护并截断。
     */
    @TableField("error_message")
    @Schema(description = "错误信息")
    private String errorMessage;

    /**
     * 操作耗时（毫秒），由切面在方法返回前后计算。
     */
    @TableField("cost_time")
    @Schema(description = "操作耗时，单位毫秒")
    private Integer costTime;

    /**
     * 字段级变更明细 JSON，由服务端在注解声明了资源类型时写入。
     *
     * <p>格式是数组，每项为 {@code {field, oldValue, newValue}}，记录「哪些字段从什么改成了什么」。
     * 请求参数只有新值，回答不了「环境是不是被从 A 改成了 B」这类问题，明细才是真正可追溯的部分。</p>
     *
     * <p>基础字段（主键、创建/更新时间、软删除标记）与敏感字段（凭据、私钥、令牌）
     * 不进入明细：前者每次必变且无业务含义，后者按 AGENTS.md §7 一律不得落库。
     * 删除操作查不到变更后记录时，旧值全部以 {@code (已删除)} 作为新值呈现。</p>
     *
     * <p>无变更、未声明资源类型或快照取不到时保持为空，不能凭空构造。</p>
     */
    @TableField("changed_fields")
    @Schema(description = "字段级变更明细JSON，[{field,oldValue,newValue}]")
    private String changedFields;
}
