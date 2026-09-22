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

        // 构建任务类型到处理器的映射；同一类型出现两个实现属于配置错误，启动即失败更安全。
        Map<String, TaskHandler> handlerMap = new HashMap<>();
        for (TaskHandler handler : handlerList) {
            TaskHandler exists = handlerMap.put(handler.taskType(), handler);
            if (exists != null) {
                throw new IllegalStateException("任务类型 " + handler.taskType() + " 存在多个处理器："
                        + exists.getClass().getName() + " 与 " + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(handlerMap);

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
     * 启动执行器：先收敛上次运行残留的中断任务，再开始轮询排队任务。
     */
    @Override
    public void start() {
        recoverInterruptedTasks();
        // 先置为运行中，再启动轮询线程，避免首轮调度因为标记未就绪而空跑。
        running = true;
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lds-task-poller");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(this::pollOnce, 0L, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("任务执行器已启动，并发上限 {}，轮询间隔 {} ms，已注册任务类型 {}",
                concurrency.availablePermits(), pollIntervalMs, handlers.keySet());
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
        TaskHandler handler = handlers.get(task.getTaskType());
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
        appendLog(task, STAGE_SYSTEM, TaskLogLevel.INFO,
                "任务开始执行：" + handler.taskType() + "，关联业务主键 " + task.getBizId());
        try {
            handler.execute(task);
            appendLog(task, STAGE_SYSTEM, TaskLogLevel.INFO, "任务执行成功");
            markSuccess(taskId);
        } catch (Exception e) {
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
                appendLog(task, STAGE_SYSTEM, TaskLogLevel.WARN, "平台重启，任务执行已中断");
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
