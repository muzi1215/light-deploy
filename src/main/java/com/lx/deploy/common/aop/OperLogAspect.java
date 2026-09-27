package com.lx.deploy.common.aop;

import com.fasterxml.jackson.databind.JsonNode;
import com.lx.deploy.audit.model.AuditLog;
import com.lx.deploy.audit.service.AuditLogService;
import com.lx.deploy.common.annotation.OperLog;
import com.lx.deploy.common.constants.AuditResult;
import com.lx.deploy.common.util.SecretMasker;
import com.lx.deploy.common.web.ClientInfoResolver;
import com.lx.deploy.common.web.RequestTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * 操作日志切面：为带 {@link OperLog} 注解的 Controller 方法统一记录审计日志。
 *
 * <p>写日志这件事放在切面而不是各 Service，是为了让「哪些操作需要留痕」变成一处声明：
 * 散落在业务代码里的日志调用必然会有遗漏，而遗漏恰恰发生在最需要追溯的地方
 * （部署目标的环境归属被改、凭据被替换这类低频高危操作）。</p>
 *
 * <p>三条硬性边界：</p>
 * <ol>
 *   <li><b>绝不改变业务结果</b>：异常照原样往上抛，落库失败只记 ERROR 不干扰请求；
 *       审计是旁路，不能反过来让业务失败。</li>
 *   <li><b>失败也要留痕</b>：一次失败的新增往往比成功的更值得查，因此 catch 分支同样落库，
 *       只是 result 记为失败并带上原因。</li>
 *   <li><b>敏感字段先脱敏再落库</b>：请求参数过 {@link SecretMasker#maskJsonFields(String)}，
 *       异常消息过 {@link SecretMasker#maskSecret(String)}（AGENTS.md §7）。</li>
 * </ol>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
@Slf4j
@Aspect
@Component
public class OperLogAspect {

    /** 操作者请求头：过渡期没有登录态，由前端从登录信息里带过来。 */
    private static final String HEADER_OPERATOR_ID = "X-Operator-Id";

    /** 操作者名称请求头。 */
    private static final String HEADER_OPERATOR_NAME = "X-Operator-Name";

    /** 取不到操作者时的占位值：不能留空，否则页面筛选「谁干的」时会漏掉这批记录。 */
    private static final String DEFAULT_OPERATOR = "system";

    /** 请求参数保存上限，超出截断。 */
    private static final int MAX_PARAM_LENGTH = 2000;

    /** 返回结果保存上限，超出截断。 */
    private static final int MAX_RESULT_LENGTH = 1000;

    /** 失败原因保存上限，超出截断。 */
    private static final int MAX_ERROR_LENGTH = 500;

    /** 截断标记，提示页面内容不完整。 */
    private static final String TRUNCATED_SUFFIX = "...(已截断)";

    /**
     * 参数名发现器，用于按参数名取出资源主键。
     *
     * <p>与 Spring MVC 解析 {@code @PathVariable} 用的是同一套机制，行为保持一致；
     * 取不到参数名（极端编译配置）时降级为「本次无变更明细」，不报错。</p>
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /** 字段级变更明细采集器。 */
    private final ResourceDiffRecorder resourceDiffRecorder;

    /** 操作日志服务，只调用其新增能力。 */
    private final AuditLogService auditLogService;

    /**
     * 应用系统名称，来自配置 {@code light-deploy.application-name}。
     *
     * <p>「操作的是哪个系统」是实例级事实：同一个平台里所有接口的取值必然相同，
     * 因此放在配置里由切面统一填充，而不是让每个 {@link OperLog} 注解各写一遍——
     * 后者在系统改名时要改几十处且必然漏改，漏改的表现是同一张表里出现两种系统名，
     * 比字段缺失更难解释。</p>
     */
    private final String applicationName;

    /**
     * 注入变更采集器、操作日志服务与应用系统名称。
     *
     * <p>刻意<b>不注入 {@code ObjectMapper}</b>：Spring Boot 4 起容器只提供 Jackson 3
     * （{@code tools.jackson}）的实例，按通用做法注入会直接导致上下文启动失败；
     * 更重要的是，审计文本不该跟随业务序列化器的全局配置变动，
     * 因此统一走 {@link SecretMasker} 提供的静态序列化能力。</p>
     *
     * @param resourceDiffRecorder 字段级变更明细采集器
     * @param auditLogService 操作日志服务
     * @param applicationName 应用系统名称，缺省回落到「轻量部署发布系统」
     */
    public OperLogAspect(ResourceDiffRecorder resourceDiffRecorder,
                         AuditLogService auditLogService,
                         @Value("${light-deploy.application-name}") String applicationName) {
        this.resourceDiffRecorder = resourceDiffRecorder;
        this.auditLogService = auditLogService;
        this.applicationName = applicationName;
    }

    /**
     * 环绕执行被注解标记的方法，在返回或抛异常后落一条操作日志。
     *
     * <p>用 {@code @Around} 而不是「{@code @AfterReturning} + {@code @AfterThrowing}」两个通知：
     * 耗时需要同一份开始时间，成败也需要统一收口，拆成两个通知就得靠 ThreadLocal 传状态，
     * 反而更容易在异常路径上漏写。</p>
     *
     * @param joinPoint 被拦截的连接点
     * @return 业务方法的返回值，原样透传
     * @throws Throwable 业务方法抛出的异常，原样向上抛，切面不吞异常
     */
    @Around("@annotation(com.lx.deploy.common.annotation.OperLog)")
    public Object recordOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 取目标类上的方法而不是接口/代理上的：注解声明在实现方法上。
        Method targetMethod = AopUtils.getMostSpecificMethod(signature.getMethod(), joinPoint.getTarget().getClass());
        OperLog operLog = AnnotatedElementUtils.findMergedAnnotation(targetMethod, OperLog.class);
        if (operLog == null) {
            return joinPoint.proceed();
        }
        AuditLog audit = buildBaseLog(joinPoint, targetMethod, operLog);
        // 资源定位与变更前快照都必须在业务方法执行之前取：方法一旦执行，旧值就被覆盖了。
        String resourceType = operLog.resourceType();
        String resourceId = StringUtils.hasText(resourceType)
                ? resolveResourceId(signature, joinPoint.getArgs(), operLog.resourceIdParam())
                : null;
        audit.setResourceType(StringUtils.hasText(resourceType) ? resourceType : null);
        audit.setResourceId(resourceId);
        JsonNode before = resourceDiffRecorder.snapshot(resourceType, resourceId);
        long startedAt = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            audit.setResult(AuditResult.SUCCESS);
            if (operLog.recordResult()) {
                // 结果同样过一遍脱敏：统一响应里可能带回凭据类字段（如服务器密文），
                // 「请求参数脱敏了、返回结果没脱敏」等于防线只修了一半。
                audit.setJsonResult(truncate(SecretMasker.maskJsonFields(serialize(result)), MAX_RESULT_LENGTH));
            }
            if (before != null) {
                audit.setChangedFields(resourceDiffRecorder.captureDiff(before,
                        resourceDiffRecorder.snapshot(resourceType, resourceId)));
            }
            return result;
        } catch (Throwable ex) {
            // 失败同样留痕：谁在什么时候试图做什么、为什么被拒，往往是排查的起点。
            audit.setResult(AuditResult.FAILURE);
            audit.setErrorMessage(truncate(SecretMasker.maskSecret(ex.getMessage()), MAX_ERROR_LENGTH));
            throw ex;
        } finally {
            audit.setCostTime((int) (System.currentTimeMillis() - startedAt));
            saveQuietly(audit);
        }
    }

    /**
     * 组装与目标方法、请求相关的固定字段。
     *
     * <p>在方法执行前组装：请求上下文只在当前线程可用，方法返回后仍可用但没必要推迟；
     * 更重要的是参数必须在执行前取值——执行过程中入参对象可能被业务代码就地修改。</p>
     *
     * @param joinPoint 被拦截的连接点
     * @param targetMethod 被拦截的目标方法
     * @param operLog 方法上的操作日志注解
     * @return 已填充固定字段、待补结果的审计实体
     */
    private AuditLog buildBaseLog(ProceedingJoinPoint joinPoint, Method targetMethod, OperLog operLog) {
        HttpServletRequest request = currentRequest();
        AuditLog audit = new AuditLog();
        audit.setApplication(applicationName);
        audit.setTraceId(currentTraceId(request));
        audit.setOperatorId(resolveHeader(request, HEADER_OPERATOR_ID));
        audit.setOperatorName(resolveHeader(request, HEADER_OPERATOR_NAME));
        audit.setTitle(operLog.title());
        audit.setBusinessType(operLog.businessType().getCode());
        audit.setMethod(targetMethod.getDeclaringClass().getName() + "." + targetMethod.getName());
        audit.setRequestMethod(request == null ? null : request.getMethod());
        // 只记 URI 不记查询串：查询串可能携带敏感参数，与访问日志的口径一致（AGENTS.md §14）。
        audit.setRequestUri(request == null ? null : request.getRequestURI());
        audit.setOperIp(ClientInfoResolver.resolveIp(request));
        audit.setOs(ClientInfoResolver.resolveOs(userAgent(request)));
        audit.setBrowser(ClientInfoResolver.resolveBrowser(userAgent(request)));
        audit.setOperParam(operLog.recordParam() ? serializeParams(joinPoint.getArgs()) : null);
        return audit;
    }

    /**
     * 落库，失败只记日志不影响业务。
     *
     * <p>审计是旁路能力：写不进去最多是「这次操作没留痕」，绝不能反过来让操作本身失败。
     * 但静默吞掉同样不行（AGENTS.md §14.3），因此这里记 ERROR 并保留堆栈，
     * 至少让「日志没写进去」这件事自己可见。</p>
     *
     * @param audit 待保存的审计实体
     */
    private void saveQuietly(AuditLog audit) {
        try {
            auditLogService.save(audit);
        } catch (Exception ex) {
            log.error("写入操作日志失败，title={}，method={}", audit.getTitle(), audit.getMethod(), ex);
        }
    }

    /**
     * 序列化请求参数并脱敏。
     *
     * <p>过滤掉 Servlet 容器参数：{@code HttpServletRequest} 无法序列化，
     * 强行序列化要么抛异常要么输出一大段与业务无关的对象内容。</p>
     *
     * @param args 方法入参，可能为 null
     * @return 脱敏并截断后的 JSON 文本；无有效入参时返回空数组文本
     */
    private String serializeParams(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<Object> values = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest)
                        && !(arg instanceof HttpServletResponse)
                        && !(arg instanceof MultipartFile))
                .toList();
        if (values.isEmpty()) {
            return "[]";
        }
        return truncate(SecretMasker.maskJsonFields(serialize(values)), MAX_PARAM_LENGTH);
    }

    /**
     * 将对象序列化为 JSON。
     *
     * <p>走 {@link SecretMasker#toJson(Object)} 的静态能力而不是注入 {@code ObjectMapper}：
     * Spring Boot 4 只提供 Jackson 3 的实例，注入 Jackson 2 类型会直接导致启动失败；
     * 审计文本也不该随业务序列化器的全局配置变动而改变。</p>
     *
     * @param value 待序列化对象，允许为 null
     * @return JSON 文本；序列化失败时返回 null，由调用方决定如何呈现
     */
    private String serialize(Object value) {
        String json = SecretMasker.toJson(value);
        if (json == null && value != null) {
            log.warn("操作日志序列化对象失败，类型={}", value.getClass().getName());
        }
        return json;
    }

    /**
     * 按参数名从方法入参里取出资源主键。
     *
     * <p>失败时返回 null 而不是抛异常：漏记一条变更明细的代价远小于让操作本身失败。
     * 参数名取不到通常意味着编译期未保留参数信息，属于环境问题而非业务错误。</p>
     *
     * @param signature 方法签名
     * @param args 方法入参，可能为 null
     * @param paramName 承载资源主键的参数名
     * @return 主键字符串；参数不存在或值为空时返回 null
     */
    private String resolveResourceId(MethodSignature signature, Object[] args, String paramName) {
        if (args == null || args.length == 0) {
            return null;
        }
        String[] names = parameterNameDiscoverer.getParameterNames(signature.getMethod());
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length && i < args.length; i++) {
            if (paramName.equals(names[i]) && args[i] != null) {
                return String.valueOf(args[i]);
            }
        }
        return null;
    }

    /**
     * 截断超长文本。
     *
     * @param text 待截断文本，允许为 null
     * @param maxLength 最大长度
     * @return 截断后的文本；原文本未超长时原样返回
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + TRUNCATED_SUFFIX;
    }

    /** 取当前请求；非 Web 线程（如异步任务）返回 null。 */
    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /**
     * 取本次请求的追踪 ID，优先读请求属性，回落 MDC。
     *
     * @param request 当前请求，允许为 null
     * @return 追踪 ID；都取不到时返回 null
     */
    private String currentTraceId(HttpServletRequest request) {
        if (request != null) {
            Object requestId = request.getAttribute(RequestTraceFilter.ATTRIBUTE_NAME);
            if (requestId != null) {
                return requestId.toString();
            }
        }
        return MDC.get("requestId");
    }

    /**
     * 读取操作者请求头，缺失时回落到占位值。
     *
     * @param request 当前请求，允许为 null
     * @param headerName 请求头名称
     * @return 请求头取值；为空时返回 {@code system}
     */
    private String resolveHeader(HttpServletRequest request, String headerName) {
        if (request == null) {
            return DEFAULT_OPERATOR;
        }
        String value = request.getHeader(headerName);
        return value == null || value.isBlank() ? DEFAULT_OPERATOR : value.trim();
    }

    /**
     * 读取 User-Agent。
     *
     * @param request 当前请求，允许为 null
     * @return User-Agent 文本；请求不存在时返回 null
     */
    private String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
