package com.lx.deploy.task.executor;

import com.lx.deploy.task.model.Task;

/**
 * 任务处理器：一种任务类型对应一个实现。
 *
 * <p>执行器负责调度、状态流转和日志落库，处理器只负责「拿到一条已进入执行中状态的任务后，
 * 按业务语义把这件事做完」。处理器不修改任务状态：抛出的异常会由执行器统一收敛成
 * 任务失败与失败原因，正常返回即视为成功。</p>
 *
 * <p>新增任务类型时只需要新建一个实现类并注册为 Spring Bean，执行器会自动发现，
 * 不需要修改调度代码。</p>
 *
 * @author lx
 * @since 2026-09-23 02:10:00
 */
public interface TaskHandler {

    /**
     * 该处理器负责任务类型编码。
     *
     * @return 与 {@code TaskType} 枚举编码一致的任务类型，例如 SERVER_TEST
     */
    String taskType();

    /**
     * 计算任务执行的互斥键。
     *
     * <p>互斥键相同的任务在平台内串行执行，用于落实「同一服务器同一时刻只执行一个任务」
     * （AGENTS.md §1）。连通性测试这类以服务器为操作对象的任务返回 {@code server:服务器ID}；
     * 互斥粒度由本方法决定，不要求与任务主键一致。</p>
     *
     * @param task 待执行的任务实体，可能只填充了 taskType 与 bizId
     * @return 互斥键文本，不允许为空
     */
    String lockKey(Task task);

    /**
     * 执行任务主体。
     *
     * <p>执行过程中应通过 TaskService.updateProgress 汇报阶段与进度、
     * 通过 TaskLogService 写入执行日志。执行失败时抛出异常，
     * 异常消息会作为任务失败原因展示给用户，因此不允许携带凭据等敏感信息。</p>
     *
     * @param task 已完成状态抢占的任务实体，id 与 bizId 必定非空
     */
    void execute(Task task);
}
