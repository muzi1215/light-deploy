package com.lx.deploy.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.common.util.IdGenerator;
import com.lx.deploy.task.enums.TaskStatus;
import com.lx.deploy.task.enums.TaskType;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 异步任务服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。除了标准查询接口，
 * 本服务集中管理任务编号生成与唯一性、任务状态单向流转（供后续任务执行器调用），
 * 终态任务不允许再次流转或修改。</p>
 *
 * @author lx
 * @since 2026-09-03 01:59:45
 */
@Service
public class TaskService extends ServiceImpl<TaskMapper, Task> {

    /** 任务编号时间部分格式。 */
    private static final DateTimeFormatter TASK_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 根据 DTO 中的非空条件分页查询异步任务。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<TaskVo> pageByCondition(PageQuery pageQuery, TaskDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<Task> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<TaskVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<TaskVo> listByCondition(TaskDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回异步任务响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public TaskVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条异步任务。
     *
     * <p>任务编号未传入时按 TASK-时间戳-随机段 规则自动生成；状态强制初始化为 QUEUED，
     * 不允许调用方直接创建非排队状态的任务，保证任务一定经过执行器调度。</p>
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 参数不合法、任务编号重复或保存失败时抛出
     */
    public TaskVo create(TaskDto dto) {
        // 任务类型必填且必须是受支持的枚举值。
        if (TaskType.fromCode(dto.getTaskType()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务类型必须为 SERVER_TEST、BUILD 或 DEPLOY");
        }
        // 进度传入时必须在 0 到 100 之间。
        if (dto.getProgress() != null && (dto.getProgress() < 0 || dto.getProgress() > 100)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务进度必须在 0 到 100 之间");
        }
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        Task entity = toEntity(dto);
        // 任务编号未传入时自动生成，传入时做唯一性预检（数据库还有唯一键兜底）。
        if (!StringUtils.hasText(entity.getTaskNo())) {
            entity.setTaskNo(generateTaskNo());
        } else if (count(new LambdaQueryWrapper<Task>().eq(Task::getTaskNo, entity.getTaskNo())) > 0) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "任务编号已存在：" + entity.getTaskNo());
        }
        // 新建任务强制从排队状态开始，不允许调用方指定其他初始状态。
        entity.setStatus(TaskStatus.QUEUED.getCode());
        // 未指定进度时从 0 开始。
        entity.setProgress(entity.getProgress() == null ? 0 : entity.getProgress());
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增异步任务失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改异步任务。
     *
     * <p>任务状态、开始时间和结束时间由状态流转方法维护，本方法不接受修改，
     * 防止普通更新接口绕过状态机。终态任务不允许修改。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在、终态任务或修改失败时抛出
     */
    public TaskVo update(String id, TaskDto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        Task entity = getRequiredEntityById(id);
        // 终态任务（成功/失败/已取消）不允许修改。
        if (TaskStatus.isFinal(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "任务已结束，不允许修改");
        }
        // 任务类型传入时必须是受支持的枚举值。
        if (dto.getTaskType() != null && TaskType.fromCode(dto.getTaskType()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务类型必须为 SERVER_TEST、BUILD 或 DEPLOY");
        }
        // 进度传入时必须在 0 到 100 之间。
        if (dto.getProgress() != null && (dto.getProgress() < 0 || dto.getProgress() > 100)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务进度必须在 0 到 100 之间");
        }
        // 状态、起止时间属于流转字段，普通更新接口不允许修改，复制前先剔除。
        dto.setStatus(null);
        dto.setStartedAt(null);
        dto.setFinishedAt(null);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改异步任务失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除异步任务。
     *
     * <p>排队中和执行中的任务承载后续调度状态，不允许删除；
     * 只有终态任务（成功/失败/已取消）可以清理。</p>
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在、任务未结束或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        Task entity = getRequiredEntityById(id);
        // 非终态任务不允许删除，避免执行器持有悬空任务。
        if (!TaskStatus.isFinal(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "任务尚未结束，不允许删除");
        }
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除异步任务失败");
        }
    }

    // ==================== 状态流转（任务执行器骨架） ====================

    /**
     * 将任务标记为执行中。
     *
     * <p>仅 QUEUED 状态允许进入 RUNNING；进入 RUNNING 时记录任务开始时间。
     * 该方法供任务执行器在真正开始执行时调用。</p>
     *
     * @param id 任务主键
     * @return 流转后的最新任务 VO
     * @throws DeployException 任务不存在或当前状态不允许开始时抛出
     */
    public TaskVo markRunning(String id) {
        Task entity = getRequiredEntityById(id);
        // 只有排队中的任务允许开始执行。
        if (!TaskStatus.QUEUED.getCode().equals(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "任务当前状态为 " + entity.getStatus() + "，不允许开始执行");
        }
        entity.setStatus(TaskStatus.RUNNING.getCode());
        entity.setStartedAt(LocalDateTime.now());
        updateById(entity);
        return toVo(entity);
    }

    /**
     * 将任务标记为成功。
     *
     * <p>仅 RUNNING 状态允许进入 SUCCESS，成功时记录结束时间并清空失败原因。</p>
     *
     * @param id 任务主键
     * @return 流转后的最新任务 VO
     * @throws DeployException 任务不存在或当前状态不允许完成时抛出
     */
    public TaskVo markSuccess(String id) {
        Task entity = getRequiredEntityById(id);
        // 只有执行中的任务允许标记成功。
        if (!TaskStatus.RUNNING.getCode().equals(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "任务当前状态为 " + entity.getStatus() + "，不允许标记成功");
        }
        entity.setStatus(TaskStatus.SUCCESS.getCode());
        entity.setFinishedAt(LocalDateTime.now());
        entity.setErrorMessage(null);
        updateById(entity);
        return toVo(entity);
    }

    /**
     * 将任务标记为失败。
     *
     * <p>仅 RUNNING 状态允许进入 FAILED，失败原因必须提供并记录到 error_message，
     * 保证任务失败可追溯。禁止在失败原因中携带凭据等敏感信息。</p>
     *
     * @param id 任务主键
     * @param errorMessage 失败原因，面向页面展示
     * @return 流转后的最新任务 VO
     * @throws DeployException 任务不存在、原因缺失或当前状态不允许失败时抛出
     */
    public TaskVo markFailed(String id, String errorMessage) {
        // 失败原因必填。
        if (!StringUtils.hasText(errorMessage)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "失败原因不能为空");
        }
        Task entity = getRequiredEntityById(id);
        // 只有执行中的任务允许标记失败。
        if (!TaskStatus.RUNNING.getCode().equals(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "任务当前状态为 " + entity.getStatus() + "，不允许标记失败");
        }
        entity.setStatus(TaskStatus.FAILED.getCode());
        entity.setFinishedAt(LocalDateTime.now());
        entity.setErrorMessage(errorMessage);
        updateById(entity);
        return toVo(entity);
    }

    /**
     * 取消任务。
     *
     * <p>QUEUED 和 RUNNING 状态允许取消，终态任务取消无意义直接拒绝；
     * 取消时记录结束时间。该方法供人工干预或执行器超时兜底调用。</p>
     *
     * @param id 任务主键
     * @return 流转后的最新任务 VO
     * @throws DeployException 任务不存在或任务已结束时抛出
     */
    public TaskVo cancel(String id) {
        Task entity = getRequiredEntityById(id);
        // 终态任务不允许取消。
        if (TaskStatus.isFinal(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "任务当前状态为 " + entity.getStatus() + "，不允许取消");
        }
        entity.setStatus(TaskStatus.CANCELED.getCode());
        entity.setFinishedAt(LocalDateTime.now());
        updateById(entity);
        return toVo(entity);
    }

    /**
     * 更新任务执行进度。
     *
     * <p>仅 RUNNING 状态允许更新进度，进度取值 0 到 100。
     * 该方法供任务执行器在执行过程中定期汇报进度时调用。</p>
     *
     * @param id 任务主键
     * @param stage 当前执行阶段描述
     * @param progress 进度百分比，0 到 100
     * @throws DeployException 任务不存在、未在执行中或进度非法时抛出
     */
    public void updateProgress(String id, String stage, Integer progress) {
        // 进度必须在 0 到 100 之间。
        if (progress == null || progress < 0 || progress > 100) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务进度必须在 0 到 100 之间");
        }
        Task entity = getRequiredEntityById(id);
        // 只有执行中的任务允许更新进度。
        if (!TaskStatus.RUNNING.getCode().equals(entity.getStatus())) {
            throw new DeployException(ResponseCode.DATA_CONFLICT,
                    "任务当前状态为 " + entity.getStatus() + "，不允许更新进度");
        }
        entity.setCurrentStage(stage);
        entity.setProgress(progress);
        updateById(entity);
    }

    /**
     * 生成任务编号。
     *
     * <p>规则为 TASK-yyyyMMddHHmmss-8位随机段：时间部分便于人工排序检索，
     * 随机段取自统一 IdGenerator 的 UUID，避免同一秒内冲突。</p>
     *
     * @return 形如 TASK-20260903015900-1a2b3c4d 的任务编号
     */
    private String generateTaskNo() {
        // 时间戳 + UUID 前 8 位，兼顾可读性和唯一性。
        return "TASK-" + LocalDateTime.now().format(TASK_NO_FORMATTER)
                + "-" + IdGenerator.generateUUID().substring(0, 8);
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private Task getRequiredEntityById(String id) {
        Task entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "异步任务不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private Task toEntity(TaskDto dto) {
        Task entity = new Task();
        BeanUtils.copyProperties(dto, entity);
        return entity;
    }

    /**
     * 将数据库实体转换为接口响应 VO。
     *
     * <p>VO 类型本身不声明敏感字段，因此属性复制不会把密码、密文、Token 或私钥返回给前端。</p>
     *
     * @param entity 数据库实体
     * @return 可安全返回给 Controller 的响应对象
     */
    private TaskVo toVo(Task entity) {
        TaskVo vo = new TaskVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Task> buildQueryWrapper(TaskDto condition) {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 任务编号：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getTaskNo()),
                Task::getTaskNo, condition.getTaskNo());
        // 任务类型：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getTaskType()),
                Task::getTaskType, condition.getTaskType());
        // 关联业务主键：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getBizId()),
                Task::getBizId, condition.getBizId());
        // 任务状态：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getStatus()),
                Task::getStatus, condition.getStatus());
        // 默认按创建时间倒序返回，优先展示最近提交的任务。
        wrapper.orderByDesc(Task::getCreateTime);
        return wrapper;
    }
}
