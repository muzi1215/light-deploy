package com.lx.deploy.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.task.enums.TaskLogLevel;
import com.lx.deploy.task.mapper.TaskLogMapper;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.TaskLog;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.model.vo.TaskLogVo;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 任务执行日志服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。日志由任务执行器
 * 在执行过程中写入，sequence_no 由本服务按任务内递增分配，查询结果按顺序号升序返回，
 * 保证页面展示的执行过程与实际执行顺序一致。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@Service
public class TaskLogService extends ServiceImpl<TaskLogMapper, TaskLog> {

    /** 任务 Mapper，用于写入日志前校验任务存在，避免日志悬空。 */
    private final TaskMapper taskMapper;

    /**
     * 注入任务 Mapper。
     *
     * @param taskMapper 任务 Mapper
     */
    public TaskLogService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 根据 DTO 中的非空条件分页查询任务执行日志。
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 请求查询条件，仅允许非敏感业务字段参与查询
     * @return 只包含 VO 的统一分页结果
     */
    public PageResult<TaskLogVo> pageByCondition(PageQuery pageQuery, TaskLogDto condition) {
        // 持久层使用实体分页，接口返回前逐条转换为 VO，避免实体泄漏到 Controller。
        Page<TaskLog> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<TaskLogVo> records = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据 DTO 中的非空条件查询全部匹配记录。
     *
     * @param condition 请求查询条件；为 null 时查询所有未删除记录
     * @return 转换后的 VO 列表
     */
    public List<TaskLogVo> listByCondition(TaskLogDto condition) {
        // 查询结果在 Service 层统一脱敏并转换为 VO。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询并返回任务执行日志响应对象。
     *
     * @param id 数据主键
     * @return 不包含敏感字段的响应对象
     * @throws DeployException 数据不存在时抛出
     */
    public TaskLogVo getRequiredById(String id) {
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据请求 DTO 新增一条任务执行日志。
     *
     * <p>日志内容必填，日志级别未传入时默认 INFO；sequence_no 由本方法按任务内
     * 最大顺序号加一自动分配，调用方不允许指定，保证同一任务内顺序号连续唯一。
     * 写入前校验所属任务真实存在，避免日志悬空。</p>
     *
     * @param dto 待新增的请求参数，不包含主键和审计字段
     * @return 保存完成后的响应对象
     * @throws DeployException 任务不存在、内容为空或保存失败时抛出
     */
    public TaskLogVo create(TaskLogDto dto) {
        // 日志内容必填，空日志没有记录价值。
        if (!StringUtils.hasText(dto.getContent())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "日志内容不能为空");
        }
        // 日志级别可选，传入时必须是受支持的枚举值。
        if (dto.getLogLevel() != null && TaskLogLevel.fromCode(dto.getLogLevel()) == null) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "日志级别必须为 DEBUG、INFO、WARN 或 ERROR");
        }
        // 任务 ID 必填且任务必须真实存在，避免日志指向不存在的任务。
        if (!StringUtils.hasText(dto.getTaskId()) || taskMapper.selectById(dto.getTaskId()) == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "所属任务不存在");
        }
        // DTO 转换为新的持久化实体，主键和时间字段由 MyBatis-Plus 维护。
        TaskLog entity = toEntity(dto);
        // 顺序号由服务端按任务内最大值加一分配，调用方传入值一律忽略。
        entity.setSequenceNo(nextSequenceNo(dto.getTaskId()));
        // 未指定级别时默认 INFO。
        if (!StringUtils.hasText(entity.getLogLevel())) {
            entity.setLogLevel(TaskLogLevel.INFO.getCode());
        }
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增任务执行日志失败");
        }
        return toVo(entity);
    }

    /**
     * 根据路径主键和请求 DTO 修改任务执行日志。
     *
     * <p>任务日志是执行过程的留痕记录，只允许修正内容，不允许变更所属任务、
     * 顺序号和级别，防止破坏日志与任务执行顺序的对应关系。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后业务字段的请求参数
     * @return 修改后的最新响应对象
     * @throws DeployException 数据不存在或修改失败时抛出
     */
    public TaskLogVo update(String id, TaskLogDto dto) {
        // 先校验目标存在，再将 DTO 内容复制到该实体，主键始终采用路径参数。
        TaskLog entity = getRequiredEntityById(id);
        // 任务、顺序号、级别为执行留痕字段，不允许通过普通更新接口修改。
        dto.setTaskId(null);
        dto.setSequenceNo(null);
        dto.setLogLevel(null);
        BeanUtils.copyProperties(dto, entity);
        entity.setId(id);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改任务执行日志失败");
        }
        return getRequiredById(id);
    }

    /**
     * 根据主键逻辑删除任务执行日志。
     *
     * @param id 数据主键
     * @throws DeployException 数据不存在或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前校验记录存在；BaseEntity 的逻辑删除配置保证不会物理删除。
        getRequiredEntityById(id);
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除任务执行日志失败");
        }
    }

    /**
     * 计算指定任务的下一个日志顺序号。
     *
     * <p>取该任务当前最大顺序号加一；任务还没有日志时从 1 开始。
     * 顺序号配合数据库唯一键 uk_lds_task_log_sequence 保证任务内连续唯一。</p>
     *
     * @param taskId 任务主键
     * @return 下一个可用顺序号
     */
    private Long nextSequenceNo(String taskId) {
        // 查询该任务最新一条日志，取其顺序号加一。
        TaskLog last = getOne(new LambdaQueryWrapper<TaskLog>()
                .eq(TaskLog::getTaskId, taskId)
                .orderByDesc(TaskLog::getSequenceNo)
                .last("LIMIT 1"));
        // 任务还没有日志时从 1 开始。
        return last == null ? 1L : last.getSequenceNo() + 1;
    }

    /**
     * 查询数据库实体，供 Service 内部更新、删除和 VO 转换使用。
     *
     * @param id 数据主键
     * @return 数据库实体
     * @throws DeployException 数据不存在或已逻辑删除时抛出
     */
    private TaskLog getRequiredEntityById(String id) {
        TaskLog entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "任务执行日志不存在");
        }
        return entity;
    }

    /**
     * 将请求 DTO 转换为数据库实体。
     *
     * @param dto Controller 接收的请求参数
     * @return 可由 Mapper 持久化的新实体
     */
    private TaskLog toEntity(TaskLogDto dto) {
        TaskLog entity = new TaskLog();
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
    private TaskLogVo toVo(TaskLog entity) {
        TaskLogVo vo = new TaskLogVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /**
     * 根据请求 DTO 构建 MyBatis-Plus 动态查询条件。
     *
     * <p>日志内容不适合作为等值过滤条件，因此不参与查询；
     * 结果按顺序号升序排列，与任务实际执行顺序一致。</p>
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<TaskLog> buildQueryWrapper(TaskLogDto condition) {
        LambdaQueryWrapper<TaskLog> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 任务主键：非空字符串使用等值条件查询，页面按任务查看日志的主要入口。
        wrapper.eq(StringUtils.hasText(condition.getTaskId()),
                TaskLog::getTaskId, condition.getTaskId());
        // 日志所属任务阶段：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getStage()),
                TaskLog::getStage, condition.getStage());
        // 日志级别：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getLogLevel()),
                TaskLog::getLogLevel, condition.getLogLevel());
        // 按顺序号升序返回，保证展示顺序与执行顺序一致。
        wrapper.orderByAsc(TaskLog::getSequenceNo);
        return wrapper;
    }
}
