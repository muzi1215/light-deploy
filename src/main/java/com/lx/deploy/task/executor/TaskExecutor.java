package com.lx.deploy.task.executor;

import com.lx.deploy.common.constants.TaskStatus;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务执行器：轮询排队任务、按服务器互斥地并发执行、收敛中断任务。
 *
 * <p>设计取舍（当前为单实例轻量部署，未引入 Redis 队列）：</p>
 * <ul>
 *   <li><b>触发方式</b>：定时轮询 lds_task 中状态为 0（排队中）的任务，按创建时间升序领取。
 *       任务表本身就是队列，省掉一套消息中间件，应用重启后排队任务不会丢；</li>
 *   <li><b>抢占方式</b>：通过 TaskService.markRunning 的条件更新把「排队中 → 执行中」做成原子操作，
 *       即使将来部署多个实例，同一个任务也只会被一个实例领走；</li>
 *   <li><b>并发约束</b>：线程池大小即平台并发执行上限（AGENTS.md 基线为 6），
 *       并用互斥键保证同一服务器同一时刻只跑一个任务，不同服务器可并发；</li>
 *   <li><b>中断任务</b>：任务执行进度只存在于内存与数据库中，进程重启后停留在执行中的任务
 *       不可能续跑，启动时统一标记为失败并说明原因，避免页面永远显示执行中。</li>
 * </ul>
 *
 * <p>日志写入失败不影响任务执行结果，但会记录 WARN：日志是过程留痕，不应该成为任务失败的原因。</p>
 *
 * @author lx
 * @since 2026-09-23 02:10:00
 */
@Slf4j
@Component
public class TaskExecutor implements SmartLifecycle {

    /** 执行器自身产生的日志所属阶段。 */
    private static final String STAGE_SYSTEM = "系统";

    /** 未知任务类型使用的互斥键前缀，保证不同未知任务之间也不会互相阻塞。 */
    private static final String LOCK_KEY_UNKNOWN = "unknown:";

    /** 失败原因写入数据库前的长度上限，避免超长文本塞满 error_message。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;

    /** 任务与日志服务。 */
    private final TaskService taskService;

    /** 任务执行日志服务，用于写入执行过程留痕。 */
    private final TaskLogService taskLogService;

    /** 任务类型到处理器的映射，启动时由 Spring 注入的所有处理器构建。 */
    private final Map<String, TaskHandler> handlers;

    /** 单次轮询最多领取的任务数。 */
    private final int pollBatchSize;

    /** 轮询间隔（毫秒）。 */
    private final int pollIntervalMs;

    /** 工作线程池，大小即平台并发执行上限。 */
    private final ThreadPoolExecutor workerPool;

    /** 并发令牌：领取任务前先获取令牌，保证线程池内不会堆积等待执行的任务。 */
    private final Semaphore concurrency;

    /** 已提交到线程池的任务主键，防止同一任务在状态流转完成前被重复提交。 */
    private final Set<String> submittedTaskIds = ConcurrentHashMap.newKeySet();

    /** 已被占用的互斥键，实现同一服务器同一时刻只执行一个任务。 */
    private final Set<String> busyLockKeys = ConcurrentHashMap.newKeySet();

    /** 轮询失败日志开关：连续失败只打印一次，恢复后再失败才会再次打印，避免刷爆错误日志。 */
    private final AtomicBoolean pollFailureLogged = new AtomicBoolean(false);

    /** 运行标记，停止过程中不再领取新任务。 */
    private volatile boolean running;

    /** 轮询线程。 */
    private ScheduledExecutorService poller;

    /**
     * 创建任务执行器。
     *
     * @param taskService     任务服务，负责状态流转与排队任务查询
     * @param taskLogService  任务日志服务，负责执行过程留痕
     * @param handlerList     Spring 容器中全部任务处理器
     * @param poolSize        并发执行上限，默认 6（AGENTS.md 服务器规模基线）
     * @param pollIntervalMs  排队任务轮询间隔（毫秒），默认 1000
     * @param pollBatchSize   单次轮询最多领取的任务数，默认 20
     * @throws IllegalStateException 同一任务类型存在多个处理器时抛出
     */
    public TaskExecutor(TaskService taskService,
                        TaskLogService taskLogService,
                        List<TaskHandler> handlerList,
                        @Value("${light-deploy.task.pool-size:6}") int poolSize,
                        @Value("${light-deploy.task.poll-interval-ms:1000}") int pollIntervalMs,
                        @Value("${light-deploy.task.poll-batch-size:20}") int pollBatchSize) {
        this.taskService = taskService;
        this.taskLogService = taskLogService;
        this.pollIntervalMs = pollIntervalMs;
        this.pollBatchSize = pollBatchSize;

        // —— 任务类型路由表构建（仅构造期执行一次，运行期只读）——
        // 路由本质：Map<taskType字符串, 对应 Handler 实例>。
        // 来源：所有被 Spring 管理的 TaskHandler 实现（@Component），由 Spring 自动收集成 List 注入上方 handlerList 参数。
        // 约定：每个 Handler 通过 taskType() 自报类型编码，必须与 Service 插入 lds_task 时写入的 task_type 完全一致，
        //       否则运行期 handlers.get(taskType) 返回 null，任务会落到"未支持类型"分支。
        Map<String, TaskHandler> handlerMap = new HashMap<>();
        StringBuilder routeInfo = new StringBuilder();
        for (TaskHandler handler : handlerList) {
            // 用 Handler 自报的 taskType() 作为 key，把"类型字符串"映射到"具体编排器实例" —— 这就是运行期路由的依据
            TaskHandler exists = handlerMap.put(handler.taskType(), handler);
            if (exists != null) {
                // 同一 taskType 出现两个实现 = 配置错误：启动即失败，避免运行期把一种任务静默路由错 Handler
                throw new IllegalStateException("任务类型 " + handler.taskType() + " 存在多个处理器："
                        + exists.getClass().getName() + " 与 " + handler.getClass().getName());
            }
            if (routeInfo.length() > 0) {
                routeInfo.append(", ");
            }
            routeInfo.append(handler.taskType()).append('=').append(handler.getClass().getSimpleName());
        }
        // Map.copyOf 生成不可变路由表：后续只查不改，杜绝运行期被误写
        this.handlers = Map.copyOf(handlerMap);
        // 把"类型 → 编排器"路由表显式打印，启动排障一眼可见当前平台支持哪些任务类型、各由谁处理
        log.info("[中央调度中枢] 任务类型路由表（type → 编排器）：{}", routeInfo);

        // 固定大小线程池 + 与池同宽的令牌：提交前先拿令牌，池内不会有排队任务，
        // 被提交的任务可以立即开始抢占状态，不会长时间停留在「已提交未开始」。
        AtomicInteger threadSeq = new AtomicInteger(1);
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "lds-task-worker-" + threadSeq.getAndIncrement());
            // 守护线程：异常情况下不阻塞 JVM 退出，正常停止流程会先优雅关闭线程池。
            thread.setDaemon(true);
            return thread;
        };
        this.workerPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, threadFactory);
        this.concurrency = new Semaphore(poolSize);
    }

    /**
     * 启动任务执行器。
     *
     * <p>整体职责：这是一个「基于数据库轮询」的任务执行器的启动入口。它完成两件事：
     * <ol>
     *   <li>先对上次进程异常退出（kill / OOM / 断电）时残留在数据库中的任务做一次故障恢复；</li>
     *   <li>再启动一个单线程、守护、固定延迟的调度器，周期性调用 {@link #pollOnce()} 去
     *       数据库拉取待执行任务并分发执行。</li>
     * </ol>
     *
     * <p>执行顺序（不可随意调换）：
     * <pre>
     * start()
     *   ├─ recoverInterruptedTasks()        // ① 恢复上次遗留任务（DB 层修复）
     *   ├─ running = true                   // ② 打开运行开关（对 pollOnce 可见）
     *   ├─ 创建 poller（单线程/守护/命名）   // ③ 构造调度器
     *   ├─ scheduleWithFixedDelay(pollOnce) // ④ 立即起跑，之后「结束→等待→再跑」
     *   └─ log.info(...)                    // ⑤ 打印启动快照
     * </pre>
     *
     * <p>⚠️ 设计约束（详见方法末尾的「复核清单」块注释）：
     * <ul>
     *   <li>{@code running} / {@code poller} 字段应为 {@code volatile}（跨线程可见性）；</li>
     *   <li>{@code start()} 应做幂等保护，防止重复启动产生多个 poller；</li>
     *   <li>{@code pollOnce()} 内部必须全量 try-catch，否则一次异常会导致调度被永久取消；</li>
     *   <li>守护线程在 JVM 退出时会被强杀，必须配套优雅停机钩子。</li>
     * </ul>
     */
    public void start() {

        // ① 崩溃恢复：必须在 running=true 和启动 poller 之前执行。
        //    因为任务状态持久化在 DB 中，进程重启时 DB 里可能残留状态为「执行中」的任务
        //    （上次进程被 kill / OOM / 断电，来不及回写结果）。
        //    典型逻辑：把 status=RUNNING 且 locked_until 已过期的任务重置为 PENDING，
        //    或把「卡在 RUNNING 但属于本实例」的任务重新入队。
        //    ⚠️ 若先启动 poller 再恢复，轮询线程可能先跑起来，与恢复逻辑产生竞态。
        //    ⚠️ 多实例部署时，恢复逻辑需加锁/乐观版本号，防止多实例重复处理同一批任务。
        recoverInterruptedTasks();

        // ② 先置为运行中，再启动轮询线程，避免首轮调度因为标记未就绪而空跑。
        //    scheduleWithFixedDelay 的 initialDelay=0，线程池一建好就立即执行第一轮 pollOnce；
        //    若先启动线程后设标志，存在极短时间窗口内 pollOnce 读到 running=false 而空跑，
        //    对轮询间隔较大的系统，等于启动后要白等几十秒才真正干活。
        //    ⚠️ running 字段必须是 volatile：写发生在主线程，读发生在 lds-task-poller 线程，
        //       跨线程可见性必须有保障，否则这里的「先置 true」在另一线程可能不可见。
        running = true;

        // ③ 创建调度器。三个决策：
        //    (1) newSingleThreadScheduledExecutor —— 单线程调度器
        //        保证轮询逻辑串行执行，不会出现两轮 pollOnce 并发拉取同一批任务（重复消费）。
        //        注意分工：调度串行，真正的业务执行靠 concurrency(Semaphore) 控制的业务线程池。
        //    (2) 自定义 ThreadFactory
        //        - 命名 "lds-task-poller"：排查问题 jstack / top -H 一眼认出；
        //        - setDaemon(true)：守护线程，所有非守护线程结束后不会阻止 JVM 退出，
        //          避免忘记 shutdown 时 JVM 挂住。
        //        ⚠️ 代价：JVM 关闭时守护线程被直接杀掉，不会走完当前 pollOnce，
        //           因此必须配合优雅停机（先 running=false，再 poller.shutdown() 并 awaitTermination），
        //           否则任务状态可能写一半被打断，依赖 recoverInterruptedTasks() 下次启动兜底。
        //    (3) 赋值给成员变量 poller，供 shutdown() 关闭使用。
        //        ⚠️ poller 字段若在 start/shutdown 跨线程访问，也应为 volatile；
        //        ⚠️ start() 若无幂等保护，重复调用会创建多个调度线程、双重轮询，很危险。
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lds-task-poller");
            thread.setDaemon(true);
            return thread;
        });

        // ④ 固定延迟调度（注意不是 FixedRate）：
        //    - initialDelay = 0L            → 立即执行第一轮，不等待；
        //    - delay = pollIntervalMs       → 本轮「结束之后」再等该间隔才开始下一轮。
        //
        //    为什么用 FixedDelay 而不是 FixedRate：
        //    若某轮 pollOnce 因数据库慢查询卡了 30s，FixedRate 会立刻连发好几轮追赶进度，
        //    造成任务堆积甚至雪崩；FixedDelay 则保证两轮之间至少有 pollIntervalMs 的喘息，
        //    任务不会堆积，是数据库轮询场景的正确选择。
        //
        //    ⚠️ 极其容易踩的坑：ScheduledExecutorService 中，若提交的 task 抛出未捕获异常，
        //       后续调度会被「静默取消」，poller 从此装死且不报错。
        //       所以 pollOnce() 内部必须自己 try-catch 兜住所有异常，绝不能让异常冒泡到调度器。
        poller.scheduleWithFixedDelay(this::pollOnce, 0L, pollIntervalMs, TimeUnit.MILLISECONDS);

        // ⑤ 启动快照日志：三个诊断信息用于排查问题
        //    - concurrency.availablePermits()：Semaphore 当前可用许可数。刚启动时应等于总许可数
        //      （并发上限）；若这里打印值小于上限，说明 start 被重复调用或恢复任务时已占用许可，
        //      正好能暴露异常状态。
        //      （注：语义上「上限」更准确地应打印构造时保存的总许可数，availablePermits 是当前可用量。）
        //    - pollIntervalMs：排查「任务为什么延迟这么久才被执行」的第一手资料。
        //    - handlers.keySet()：快速核对配置的任务类型是否都注册上了，
        //      防止某个 handler 漏注册导致对应类型任务永远不被消费。
        // ★ 中央调度中枢启用日志：打印已注册的编排器清单（类名 + taskType），
        // 便于从日志一眼确认「构建 / 发布 / 回滚 / 连通性测试」各任务类型都已挂到对应 Handler 上，
        // 也作为后续每次分发日志的起点（详见 executeTask 的分发日志）。
        String registered = handlers.entrySet().stream()
                .map(e -> e.getValue().getClass().getSimpleName() + "(" + e.getKey() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        log.info("[中央调度中枢] TaskExecutor 已启用：并发上限={}，轮询间隔={}ms，已注册编排器=[{}]",
                concurrency.availablePermits(), pollIntervalMs, registered);
    }

    /**
     * 停止执行器：先停轮询，再等正在执行的任务收尾，最后释放线程池。
     */
    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.shutdownNow();
        }
        workerPool.shutdown();
        try {
            // 给正在执行的任务一段时间收尾；超时后放弃等待，由进程退出统一回收。
            if (!workerPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("任务执行器停止时仍有任务在执行，已放弃等待");
            }
        } catch (InterruptedException e) {
            // 恢复中断标记后立即返回，停止流程不再等待。
            Thread.currentThread().interrupt();
        }
        log.info("任务执行器已停止");
    }

    /**
     * 执行器是否处于运行状态。
     *
     * @return true 表示已启动且未停止
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 生命周期阶段。
     *
     * <p>返回 0 表示与容器默认阶段一致，在 Web 容器就绪后启动，保证开始轮询时数据库连接池可用。</p>
     *
     * @return 生命周期阶段值
     */
    @Override
    public int getPhase() {
        return 0;
    }

    // ==================== 调度 ====================

    /**
     * 单轮调度：领取可执行的排队任务并提交到线程池。
     *
     * <p>领取动作只在单个轮询线程内串行执行，避免同一任务被重复提交。
     * 数据库不可用时只记录一次 WARN 并按间隔重试，不影响应用本身的可用性。</p>
     */
    private void pollOnce() {
        if (!running) {
            return;
        }
        try {
            // 没有空闲并发令牌时本轮不领取：宁可不领，也不让任务在池中排队等待。
            if (concurrency.availablePermits() == 0) {
                return;
            }
            for (Task task : taskService.listQueued(pollBatchSize)) {
                if (!running) {
                    return;
                }
                // 返回 false 表示本轮无法继续领取（令牌耗尽或线程池已关闭），提前结束本轮。
                if (!trySubmit(task)) {
                    return;
                }
            }
            pollFailureLogged.set(false);
        } catch (Exception e) {
            // 连续失败只打印第一条，恢复后再次失败会重新打印，避免每秒刷一条错误日志。
            if (pollFailureLogged.compareAndSet(false, true)) {
                log.warn("任务调度轮询失败，将按间隔重试：{}", e.getMessage(), e);
            } else {
                log.debug("任务调度轮询仍然失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 尝试把一条排队任务提交到线程池。
     *
     * @param task 待提交的任务实体
     * @return true 表示本轮可以继续处理后续任务；false 表示应结束本轮调度
     */
    private boolean trySubmit(Task task) {
        // —— 运行期路由查表：用任务里存好的 task_type 字符串，从构造期建好的路由表 handlers 取出对应编排器实例 ——
        // 这一步就是"Spring 注入 + 构造函数建 Map"的最终落点：任务本身不持有 Handler 引用，只持有 type 字符串，
        // 由调度器在这里按 type 反查到真正的执行器。若返回 null 表示库里出现本平台未实现的任务类型。
        TaskHandler handler = handlers.get(task.getTaskType());
        if (handler == null) {
            // 未注册的任务类型：没有 Handler 能处理它，必须领取并以失败收尾，否则任务会永远停在 status=0 被反复轮询。
            log.warn("[中央调度分发] 未找到任务类型 {} 对应的编排器，任务将标记失败：taskId={}",
                    task.getTaskType(), task.getId());
        }
        // 未知类型也必须领取执行，否则任务会永远停留在排队中；这里用任务主键作为互斥键。
        String lockKey = handler == null ? LOCK_KEY_UNKNOWN + task.getId() : handler.lockKey(task);

        // 同一任务只提交一次：抢占完成前任务仍是排队中，会被下一轮重复查到。
        if (!submittedTaskIds.add(task.getId())) {
            return true;
        }
        // 互斥键已被占用说明该服务器正在执行另一个任务，本轮跳过，下一轮再试。
        if (!busyLockKeys.add(lockKey)) {
            submittedTaskIds.remove(task.getId());
            return true;
        }
        // 先取令牌再提交，保证线程池不会积压等待执行的任务。
        if (!concurrency.tryAcquire()) {
            busyLockKeys.remove(lockKey);
            submittedTaskIds.remove(task.getId());
            return false;
        }
        try {
            workerPool.execute(() -> runTask(task.getId(), lockKey, handler));
            return true;
        } catch (RejectedExecutionException e) {
            // 线程池已关闭（应用正在停止）：撤销本次占用，交给下一次启动处理。
            concurrency.release();
            busyLockKeys.remove(lockKey);
            submittedTaskIds.remove(task.getId());
            log.warn("任务执行器正在停止，任务暂不执行：taskId={}", task.getId());
            return false;
        }
    }

    /**
     * 工作线程入口：执行任务并保证并发令牌与互斥键一定被释放。
     *
     * @param taskId  任务主键
     * @param lockKey 该任务占用的互斥键
     * @param handler 任务处理器；为 null 表示任务类型尚无实现
     */
    private void runTask(String taskId, String lockKey, TaskHandler handler) {
        try {
            executeTask(taskId, handler);
        } catch (Throwable t) {
            // 兜底：任何未预期异常都不能让工作线程退出，也不能让任务卡在执行中。
            log.error("任务执行出现未预期异常，taskId={}", taskId, t);
            failTask(taskId, "任务执行出现未预期异常：" + safeMessage(t));
        } finally {
            // 释放顺序与占用顺序相反：先放互斥键，再放令牌，最后允许该任务被重新调度。
            busyLockKeys.remove(lockKey);
            submittedTaskIds.remove(taskId);
            concurrency.release();
        }
    }

    /**
     * 领取并执行一条任务。
     *
     * <p>处理器抛出的异常在这里收敛：先把失败原因落库（任务日志 + error_message），
     * 同时写一条控制台日志——业务性失败记 WARN 不打堆栈，未预期异常记 ERROR 带堆栈。
     * 只落库不写日志会让任务失败在服务端日志里完全不可见。</p>
     *
     * @param taskId  任务主键
     * @param handler 任务处理器；为 null 表示任务类型尚无实现
     */
    private void executeTask(String taskId, TaskHandler handler) {
        try {
            // 抢占：把状态从排队中原子地改成执行中，失败说明任务已被取消或被其它实例领走。
            taskService.markRunning(taskId);
        } catch (DeployException e) {
            log.info("任务不满足开始条件，跳过执行，taskId={}，原因：{}", taskId, e.getMessage());
            return;
        }
        Task task = taskService.getById(taskId);
        if (task == null) {
            log.warn("任务在开始执行前已被删除，taskId={}", taskId);
            return;
        }
        if (handler == null) {
            failTask(taskId, "暂不支持的任务类型：" + task.getTaskType());
            return;
        }
        // —— 中央调度分发点（全平台异步任务唯一的「分派」动作）——
        // handlers 是构造函数按 taskType 收集的 Map；这里按 task.getTaskType() 取出对应编排器并调用 execute(task)。
        // 控制台日志显式打印「编排器类名 + 任务类型 + 业务主键」，把一次发布/构建从「只看到 taskId」
        // 变成能从日志追溯到具体编排器与业务维度（发布 / 回滚 / 构建 / 连通性测试）。
        // 这正是解耦架构的代价：Controller 从不调用 Handler，Handler 只在这里被统一扇出。
        String orchestrator = handler.getClass().getSimpleName();
        log.info("[中央调度分发] 分派 taskId={} → 编排器 {} (taskType={})，业务主键 bizId={}",
                taskId, orchestrator, task.getTaskType(), task.getBizId());
        appendLog(task, STAGE_SYSTEM, TaskLogLevel.INFO,
                "任务开始执行：" + handler.taskType() + "，关联业务主键 " + task.getBizId());
        try {
            handler.execute(task);
            appendLog(task, STAGE_SYSTEM, TaskLogLevel.INFO, "任务执行成功");
            markSuccess(taskId);
        } catch (Exception e) {
            // 任务失败必须同时留痕到控制台：原先只调用 failTask 写库（lds_task_log + error_message），
            // 控制台与日志文件里一行都没有——排查时既拿不到堆栈，也不知道是哪一步、哪台机器出的问题。
            if (e instanceof DeployException) {
                // 业务性失败（目标机命令报错、校验不通过等）：原因已足够定位，不打堆栈避免刷屏。
                log.error("任务执行失败，taskId={}，类型 {}，原因：{}", taskId, handler.taskType(), safeMessage(e));
            } else {
                // 未预期异常：保留完整堆栈，这是唯一能定位代码缺陷的地方。
                log.error("任务执行出现未预期异常，taskId={}，类型 {}", taskId, handler.taskType(), e);
            }
            failTask(taskId, safeMessage(e));
        }
    }

    // ==================== 状态收敛 ====================

    /**
     * 标记任务成功。
     *
     * <p>任务在执行过程中可能被人工取消，此时状态已不是执行中，标记成功会被拒绝；
     * 这种情况只记录 WARN，因为取消是用户明确意图，不应再把任务改回成功。</p>
     *
     * @param taskId 任务主键
     */
    private void markSuccess(String taskId) {
        try {
            taskService.markSuccess(taskId);
        } catch (Exception e) {
            log.warn("标记任务成功时状态已变更（可能已被取消），taskId={}，原因：{}", taskId, e.getMessage());
        }
    }

    /**
     * 标记任务失败并写入失败原因。
     *
     * @param taskId 任务主键
     * @param reason 失败原因，会同时写入任务日志与 error_message
     */
    private void failTask(String taskId, String reason) {
        String message = trimMessage(reason);
        Task task = taskService.getById(taskId);
        if (task != null) {
            appendLog(task, STAGE_SYSTEM, TaskLogLevel.ERROR, "任务执行失败：" + message);
        }
        try {
            taskService.markFailed(taskId, message);
        } catch (Exception e) {
            log.warn("标记任务失败时状态已变更（可能已被取消），taskId={}，原因：{}", taskId, e.getMessage());
        }
    }

    /**
     * 收敛上次运行残留的中断任务。
     *
     * <p>任务执行进度只存在于内存与数据库中，进程重启后不可能续跑，
     * 因此把停留在执行中的任务统一标记为失败，避免页面永远显示执行中。
     * 该方法在应用启动阶段执行，失败只记录错误日志，不阻断应用启动。</p>
     */
    private void recoverInterruptedTasks() {
        try {
            List<Task> interrupted = taskService.listByStatus(TaskStatus.RUNNING);
            for (Task task : interrupted) {
                appendLog(task, STAGE_SYSTEM, TaskLogLevel.INFO, "平台重启，任务执行已中断");
                taskService.markFailed(task.getId(), "平台重启导致任务执行中断，请重新提交");
                log.warn("已收敛中断的任务，taskId={}, taskNo={}", task.getId(), task.getTaskNo());
            }
            if (!interrupted.isEmpty()) {
                log.warn("共收敛 {} 条中断任务", interrupted.size());
            }
        } catch (Exception e) {
            log.error("收敛中断任务失败，可能有任务停留在执行中状态", e);
        }
    }

    // ==================== 日志写入 ====================

    /**
     * 写入一条任务执行日志。
     *
     * <p>日志是过程留痕，写入失败不应该让任务本身失败，因此只记录 WARN。
     * 顺序号由 TaskLogService 按任务内最大值加一分配，同一任务的日志始终由单个工作线程写入，
     * 不存在同任务并发写日志的竞态。</p>
     *
     * @param task    任务实体
     * @param stage   日志所属阶段
     * @param level   日志级别
     * @param content 日志内容
     */
    private void appendLog(Task task, String stage, TaskLogLevel level, String content) {
        TaskLogDto dto = new TaskLogDto();
        dto.setTaskId(task.getId());
        dto.setStage(stage);
        dto.setLogLevel(level.getCode());
        dto.setContent(content);
        try {
            taskLogService.create(dto);
        } catch (Exception e) {
            log.warn("写入任务日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }

    /**
     * 提取异常的可读消息。
     *
     * @param throwable 原始异常
     * @return 异常消息；消息为空时返回异常类名
     */
    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (StringUtils.hasText(message)) {
            return message;
        }
        return throwable.getClass().getSimpleName();
    }

    /**
     * 截断失败原因。
     *
     * @param message 原始失败原因
     * @return 不超过长度上限的失败原因
     */
    private String trimMessage(String message) {
        if (message == null) {
            return "任务执行失败";
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) + "..."
                : message;
    }
}
