package com.lx.deploy.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.constants.TaskStatus;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.task.mapper.TaskLogMapper;
import com.lx.deploy.task.mapper.TaskMapper;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.TaskLog;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.model.vo.TaskLogExportVo;
import com.lx.deploy.task.model.vo.TaskLogPullVo;
import com.lx.deploy.task.model.vo.TaskLogVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务执行日志服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不创建 Service 接口。日志由任务执行器
 * 在执行过程中写入，sequence_no 由本服务按任务内递增分配，查询结果按顺序号升序返回，
 * 保证页面展示的执行过程与实际执行顺序一致。</p>
 *
 * <p>写入数据库的日志会同步输出到应用日志（控制台与 info.log），运维在服务器上
 * 直接 tail 日志就能看到与页面一致的执行过程，不必登录平台页面；同步输出可通过
 * light-deploy.task.log-echo-enabled 关闭。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:01
 */
@Slf4j
@Service
public class TaskLogService extends ServiceImpl<TaskLogMapper, TaskLog> {

    /** 增量拉取未指定数量时的默认条数。 */
    private static final int DEFAULT_PULL_LIMIT = 200;

    /** 增量拉取单次最大条数：限制单次响应体大小，超出的部分留给下一次拉取。 */
    private static final int MAX_PULL_LIMIT = 500;

    /** 导出日志的时间格式：含日期，构建类任务可能跨天，只给时分秒无法定位。 */
    private static final DateTimeFormatter EXPORT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 导出正文的换行符固定为 LF：导出件会被贴进工单、邮件与差异对比工具，LF 兼容性最好。 */
    private static final String EXPORT_LINE_SEPARATOR = "\n";

    /** 导出正文中任务头部摘要与日志区之间的分隔行。 */
    private static final String EXPORT_SEPARATOR =
            "--------------------------------------------------------------------------------";

    /** 任务 Mapper，用于写入日志前校验任务存在，避免日志悬空。 */
    private final TaskMapper taskMapper;

    /**
     * 任务日志是否同步输出到应用日志。
     *
     * <p>开启后每条任务日志都会按级别打印到控制台与 info.log，便于脱离页面排障；
     * 构建类任务输出行较多，日志量敏感时可关闭本开关，页面日志不受影响。</p>
     */
    private final boolean echoEnabled;

    /**
     * 注入任务 Mapper。
     *
     * @param taskMapper  任务 Mapper
     * @param echoEnabled 任务日志是否同步输出到应用日志，默认开启
     */
    public TaskLogService(TaskMapper taskMapper,
                          @Value("${light-deploy.task.log-echo-enabled:true}") boolean echoEnabled) {
        this.taskMapper = taskMapper;
        this.echoEnabled = echoEnabled;
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
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "日志级别必须为 INFO 或 ERROR");
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
        // 同步打印到应用日志，服务端控制台与页面看到的执行过程保持一致。
        echoToConsole(entity);
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
     * 按顺序号增量拉取任务日志，并返回任务当前状态。
     *
     * <p>这是页面实时查看执行过程的接口：只返回顺序号大于 lastSeq 的日志，
     * 因此不会重复、不会乱序，也不需要在页面侧做去重。响应里同时带上任务状态与
     * finished 标记，前端在一个请求里就能判断是继续拉还是停止轮询。</p>
     *
     * <p>增量锚点用 sequence_no（同一任务内递增的顺序号），不能用主键 id：
     * 主键是 32 位 UUID 字符串，不具备单调递增性，{@code id > lastId} 没有意义。</p>
     *
     * @param taskId  任务主键，必填
     * @param lastSeq 已获取到的最大顺序号；首次拉取传 null 或 0
     * @param limit   单次最多返回条数；为空取默认 200，超出上限按上限截断
     * @return 增量日志与任务状态
     * @throws DeployException 任务主键为空或任务不存在时抛出
     */
    public TaskLogPullVo pull(String taskId, Long lastSeq, Integer limit) {
        // 任务主键必填：日志必须挂在具体任务下才有展示意义。
        if (!StringUtils.hasText(taskId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务ID不能为空");
        }
        // 任务必须存在；同时任务状态要随本次响应返回给前端。
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "所属任务不存在");
        }
        // 顺序号锚点：负数没有意义，统一按 0 处理（即从头拉取）。
        long afterSequence = lastSeq == null ? 0L : Math.max(lastSeq, 0L);
        // 数量上限双端约束：至少 1 条，最多 MAX_PULL_LIMIT 条。
        int size = limit == null ? DEFAULT_PULL_LIMIT : Math.min(Math.max(limit, 1), MAX_PULL_LIMIT);

        // 只查增量：task_id + sequence_no 上有联合索引，查询不会随日志总量退化。
        List<TaskLog> records = page(new Page<>(1, size, false), new LambdaQueryWrapper<TaskLog>()
                .eq(TaskLog::getTaskId, taskId)
                .gt(TaskLog::getSequenceNo, afterSequence)
                .orderByAsc(TaskLog::getSequenceNo))
                .getRecords();

        // 逐条转 VO，并记录本批最大顺序号作为下一次拉取的锚点。
        List<TaskLogVo> logs = new ArrayList<>(records.size());
        long nextSequence = afterSequence;
        for (TaskLog record : records) {
            logs.add(toVo(record));
            nextSequence = record.getSequenceNo();
        }

        TaskLogPullVo vo = new TaskLogPullVo();
        vo.setTaskId(taskId);
        vo.setStatus(task.getStatus());
        // 状态文案由服务端统一转换，前端只负责展示。
        vo.setStatusText(TaskStatus.describe(task.getStatus()));
        vo.setFinished(TaskStatus.isFinal(task.getStatus()));
        vo.setCurrentStage(task.getCurrentStage());
        vo.setProgress(task.getProgress());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setLogs(logs);
        vo.setLastSeq(nextSequence);
        return vo;
    }

    /**
     * 把指定任务的全部执行日志导出为纯文本。
     *
     * <p>导出件是「任务头部摘要 + 全部日志行」：头部给出任务编号、类型、状态、关联业务主键、
     * 起止时间与失败原因，日志行按顺序号升序排列。任务列表的失败原因只有一行、页面日志区也
     * 可能被滚动淹没，导出件必须自带完整上下文，运维把它贴进工单或与同事协查时不需要再回平台截图。</p>
     *
     * <p>不做条数上限：导出的意义就是拿全量。单条日志的长度在写入侧已由处理器限制，
     * 不会出现一条日志撑爆整个文件的情况。</p>
     *
     * <p>换行统一用 LF，正文始终以换行结尾：方便直接 diff 两次构建的日志差异，
     * 也避免部分 Windows 编辑器把末尾一行吞掉。</p>
     *
     * @param taskId 任务主键，必填
     * @return 建议的下载文件名与导出正文
     * @throws DeployException 任务主键为空或任务不存在时抛出
     */
    public TaskLogExportVo exportText(String taskId) {
        // 任务主键必填：日志必须挂在具体任务下才有导出意义。
        if (!StringUtils.hasText(taskId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "任务ID不能为空");
        }
        // 任务必须存在：头部摘要与文件名都要用到任务编号与状态。
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "所属任务不存在");
        }
        // 顺序号升序取全量：导出件要能顺序阅读，也要便于按行对比两次执行的差异。
        List<TaskLog> records = list(new LambdaQueryWrapper<TaskLog>()
                .eq(TaskLog::getTaskId, taskId)
                .orderByAsc(TaskLog::getSequenceNo));

        StringBuilder text = new StringBuilder();
        appendExportHeader(text, task);
        if (records.isEmpty()) {
            // 排队中或刚创建的任务可能还没有日志，明确写出来，避免打开文件后误以为导出失败。
            text.append("（该任务还没有执行日志）").append(EXPORT_LINE_SEPARATOR);
        } else {
            for (TaskLog record : records) {
                text.append(formatExportLine(record)).append(EXPORT_LINE_SEPARATOR);
            }
        }
        // 结尾补导出时间与条数：导出件可能被转发多次，需要能判断它是哪个时间点的快照。
        text.append(EXPORT_SEPARATOR).append(EXPORT_LINE_SEPARATOR)
                .append("共 ").append(records.size()).append(" 条日志，导出时间：")
                .append(LocalDateTime.now().format(EXPORT_TIME_FORMAT)).append(EXPORT_LINE_SEPARATOR);

        return new TaskLogExportVo(buildExportFileName(task), text.toString());
    }

    /**
     * 追加导出正文的任务头部摘要。
     *
     * <p>任务类型与状态都按中文描述输出，而不是数据库编码：导出件的读者是运维，
     * 不应该要求他去查 `DEPLOY` 与 `3` 分别代表什么。</p>
     *
     * @param text 导出正文缓冲
     * @param task 任务实体
     */
    private void appendExportHeader(StringBuilder text, Task task) {
        TaskType type = TaskType.fromCode(task.getTaskType());
        text.append("任务编号：").append(orDash(task.getTaskNo())).append(EXPORT_LINE_SEPARATOR);
        text.append("任务类型：")
                .append(type == null ? orDash(task.getTaskType()) : type.getMessage())
                .append(EXPORT_LINE_SEPARATOR);
        text.append("任务状态：").append(TaskStatus.describe(task.getStatus())).append(EXPORT_LINE_SEPARATOR);
        text.append("关联业务主键：").append(orDash(task.getBizId())).append(EXPORT_LINE_SEPARATOR);
        text.append("当前阶段：").append(orDash(task.getCurrentStage())).append(EXPORT_LINE_SEPARATOR);
        text.append("执行进度：")
                .append(task.getProgress() == null ? "-" : task.getProgress() + "%")
                .append(EXPORT_LINE_SEPARATOR);
        text.append("开始时间：").append(formatExportTime(task.getStartedAt())).append(EXPORT_LINE_SEPARATOR);
        text.append("结束时间：").append(formatExportTime(task.getFinishedAt())).append(EXPORT_LINE_SEPARATOR);
        // 失败原因只在失败任务上出现，未失败时整行省略，避免导出件里堆一串「失败原因：-」。
        if (StringUtils.hasText(task.getErrorMessage())) {
            text.append("失败原因：").append(task.getErrorMessage()).append(EXPORT_LINE_SEPARATOR);
        }
        text.append(EXPORT_SEPARATOR).append(EXPORT_LINE_SEPARATOR);
    }

    /**
     * 把一条日志格式化为导出正文中的一行。
     *
     * <p>列顺序与页面控制台一致（顺序号 / 时间 / 级别 / 阶段 / 内容），顺序号左对齐补空格，
     * 便于在编辑器里竖排扫读，也便于用 grep 按级别过滤。</p>
     *
     * @param record 日志实体
     * @return 不含换行的单行文本
     */
    private String formatExportLine(TaskLog record) {
        String seq = record.getSequenceNo() == null ? "-" : String.valueOf(record.getSequenceNo());
        String stage = StringUtils.hasText(record.getStage()) ? "[" + record.getStage() + "] " : "";
        return String.format("#%-4s %s  %-5s  %s%s",
                seq, formatExportTime(record.getCreateTime()), orDash(record.getLogLevel()),
                stage, record.getContent());
    }

    /**
     * 生成下载文件名。
     *
     * <p>优先用任务编号（形如 TASK-20260925155150-985a7990），运维在下载目录里一眼能对上
     * 平台上的哪条任务；任务编号缺失属于异常数据，回落到任务主键，保证文件名始终可生成。</p>
     *
     * @param task 任务实体
     * @return 形如 {@code TASK-20260925155150-985a7990-执行日志.txt}
     */
    private String buildExportFileName(Task task) {
        return (StringUtils.hasText(task.getTaskNo()) ? task.getTaskNo() : task.getId()) + "-执行日志.txt";
    }

    /**
     * 格式化导出正文中的时间。
     *
     * @param time 时间，允许为空（任务尚未开始或尚未结束）
     * @return 形如 2026-09-25 15:51:51 的文本；为空时返回短横线
     */
    private String formatExportTime(LocalDateTime time) {
        return time == null ? "-" : time.format(EXPORT_TIME_FORMAT);
    }

    /**
     * 把空值统一替换为短横线，避免导出件里出现「任务编号：」这种读不出信息的空行。
     *
     * @param value 原始值
     * @return 原值；为空时返回 {@code -}
     */
    private String orDash(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }

    /**
     * 把已落库的任务日志按级别同步输出到应用日志。
     *
     * <p>输出行固定带上任务 ID 与阶段，运维在服务端日志里按任务 ID 检索即可还原整条执行过程；
     * 级别沿用任务日志自身级别，ERROR 会同时进入 error.log，便于告警采集。</p>
     *
     * <p>级别只有 INFO 与 ERROR 两级：ERROR 同时进入 error.log，便于告警采集；
     * 服务端日志里只需检索 ERROR 就能定位失败任务，不必在控制台分辨多种级别。</p>
     *
     * @param entity 已保存的任务日志实体
     */
    private void echoToConsole(TaskLog entity) {
        if (!echoEnabled) {
            return;
        }
        TaskLogLevel level = TaskLogLevel.fromCode(entity.getLogLevel());
        if (level == null) {
            level = TaskLogLevel.INFO;
        }
        String stage = StringUtils.hasText(entity.getStage()) ? entity.getStage() : "-";
        String message = "[task=" + entity.getTaskId() + "][" + stage + "] " + entity.getContent();
        if (level == TaskLogLevel.ERROR) {
            log.error(message);
        } else {
            log.info(message);
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
