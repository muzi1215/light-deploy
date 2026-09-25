package com.lx.deploy.task.executor;

import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.enums.server.ServerAuthType;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.task.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 服务器连通性测试任务处理器。
 *
 * <p>这是平台第一个真正执行远程操作的任务类型，用于验证「凭据解密 → SSH 连接 → 命令执行 →
 * 日志回传」整条链路。执行内容是一组只读探活命令，不修改目标机任何状态：
 * 检查系统信息、构建依赖（git / Maven）、运行时（Java / Docker）、磁盘容量，
 * 以及部署根目录是否可写。</p>
 *
 * <p>探活项分两类：必需项失败直接判定服务器不可用（任务失败），可选项失败只记 INFO 并在
 * 带 hint 时给出补装建议。这样「能连上但没装 Docker / Maven」会被如实报告为环境提示，
 * 而不是把整次测试判成不通。</p>
 *
 * <p>同一服务器同一时刻只允许一个任务：互斥键为 {@code server:服务器ID}，
 * 由 {@link TaskExecutor} 保证串行（AGENTS.md §1）。</p>
 *
 * @author lx
 * @since 2026-09-23 02:10:00
 */
@Slf4j
@Component
public class ServerTestTaskHandler implements TaskHandler {

    /** 任务类型编码，与 TaskType.SERVER_TEST 保持一致。 */
    private static final String TASK_TYPE = TaskType.SERVER_TEST.getCode();

    /** 互斥键前缀，保证同一服务器的任务串行执行。 */
    private static final String LOCK_KEY_PREFIX = "server:";

    /** 单条命令输出最多写入几条日志，避免 df 之类的大输出把任务日志刷屏。 */
    private static final int MAX_OUTPUT_LINES = 20;

    /** 探活项执行阶段：建立连接。 */
    private static final String STAGE_CONNECT = "建立连接";

    /** 连接成功后汇报的进度百分比。 */
    private static final int PROGRESS_CONNECTED = 10;

    /** 全部探活项完成后的进度百分比。 */
    private static final int PROGRESS_FINISHED = 100;

    /**
     * 固定的只读探活项。
     *
     * @param stage    任务日志与任务进度所属阶段
     * @param command  远程执行的命令
     * @param progress 进入该探活项时汇报的进度百分比（0-100）
     * @param required 是否为必需项：true 表示失败即判定服务器不可用
     * @param hint     失败时追加的可操作提示，如"怎么补上这个依赖"；无提示时为 null
     */
    private record Probe(String stage, String command, int progress, boolean required, String hint) {
    }

    /**
     * 固定探活项，全部为只读命令，不产生副作用。
     *
     * <p>探活项不区分「构建机」与「目标机」：服务器档案的三个目录都有数据库默认值，
     * 无法据此判断用途，而同一台机器也可能既构建又部署。因此统一按「可选依赖清单」检查，
     * 缺什么就提示什么——git / mvn 缺失会让构建阶段直接失败（典型报错是 bash: mvn: command not found），
     * 提前在这里暴露并给出补装或补 PATH 的办法，比等构建任务失败更容易定位。</p>
     */
    private static final List<Probe> PROBES = List.of(
            // 必需项：能执行 uname 说明 SSH 通道与登录 shell 均可用，是连通的基本判据。
            new Probe("系统信息", "uname -a", 30, true, null),
            new Probe("系统信息", "cat /etc/os-release 2>/dev/null | head -2", 35, false, null),
            new Probe("运行时检查", "java -version 2>&1 | head -3", 45, false,
                    "JAR 运行时需要 Java：安装 JDK（yum install -y java-1.8.0-openjdk）或在运行配置的 java_bin 里写绝对路径"),
            // 构建依赖：非交互式 SSH 会话不加载 /etc/profile，靠 profile 导出 PATH 的工具可能找不到。
            new Probe("构建依赖", "git --version 2>&1 | head -1", 55, false,
                    "构建需要 git 克隆代码：安装（yum install -y git）"),
            new Probe("构建依赖", "mvn -v 2>&1 | head -1", 60, false,
                    "构建需要 Maven：安装（yum install -y maven）后若仍找不到，请在服务器档案的「构建环境初始化脚本」里补 PATH，"
                            + "例如 export PATH=/opt/apache-maven-3.8.8/bin:$PATH"),
            new Probe("运行时检查", "docker --version 2>&1 | head -1", 68, false,
                    "Docker 构建模式需要 Docker：安装 Docker 并启动 dockerd"),
            new Probe("磁盘容量", "df -h /", 75, false, null));

    /** 服务器 Mapper，用于读取目标服务器配置（跨模块只依赖 Mapper，不依赖对方 Service）。 */
    private final ServerMapper serverMapper;

    /** SSH 会话工厂，负责解密凭据并建立已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 任务服务，用于汇报执行阶段与进度。 */
    private final TaskService taskService;

    /** 任务日志服务，用于把每一步执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /**
     * 创建连通性测试处理器。
     *
     * <p>部署根目录不再由配置项注入：它是「这台机器上的目录」，由服务器页面按机器维护，
     * 执行时从服务器档案读取，因此不同机器可以有不同挂载点。</p>
     *
     * @param serverMapper       服务器 Mapper
     * @param sshSessionFactory  SSH 会话工厂
     * @param taskService        任务服务
     * @param taskLogService     任务日志服务
     */
    public ServerTestTaskHandler(ServerMapper serverMapper,
                                SshSessionFactory sshSessionFactory,
                                TaskService taskService,
                                TaskLogService taskLogService) {
        this.serverMapper = serverMapper;
        this.sshSessionFactory = sshSessionFactory;
        this.taskService = taskService;
        this.taskLogService = taskLogService;
    }

    /**
     * 该处理器负责的任务类型。
     *
     * @return SERVER_TEST
     */
    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    /**
     * 互斥键：同一台服务器上的连通性测试必须串行执行。
     *
     * @param task 待执行任务，bizId 为目标服务器主键
     * @return 形如 {@code server:服务器ID} 的互斥键
     */
    @Override
    public String lockKey(Task task) {
        return LOCK_KEY_PREFIX + task.getBizId();
    }

    /**
     * 执行连通性测试。
     *
     * <p>流程：校验目标服务器 → 建立 SSH 连接 → 逐项执行只读探活命令并写日志 → 汇总结论。
     * 连接失败或必需探活项失败时抛出异常，由执行器收敛成任务失败；其余情况正常返回即成功。</p>
     *
     * @param task 已进入执行中状态的任务，bizId 为目标服务器主键
     * @throws DeployException 任务缺少目标服务器、服务器不存在/已停用、连接失败或必需探活项失败时抛出
     */
    @Override
    public void execute(Task task) {
        // 目标服务器主键来自 bizId，创建任务时由服务端写入，缺失说明任务数据异常。
        String serverId = task.getBizId();
        if (!StringUtils.hasText(serverId)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "连通性测试任务缺少目标服务器");
        }
        Server server = serverMapper.selectById(serverId);
        if (server == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "目标服务器不存在或已被删除");
        }
        // 停用的服务器不参与任何远程操作，避免对已下线机器发起连接。
        if (server.getStatus() == null || server.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "目标服务器已停用，不允许执行连通性测试");
        }

        String target = server.getUsername() + "@" + server.getHost() + ":" + server.getSshPort();
        ServerAuthType authType = ServerAuthType.fromCode(server.getAuthType() == null
                ? null : server.getAuthType().intValue());
        appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO,
                "开始连接 " + target + "，认证方式：" + (authType == null ? "未知" : authType.getMessage()));
        // 连接前先汇报一次进度，同时确认任务仍在执行中（可能刚被取消）。
        if (!reportProgress(task, STAGE_CONNECT, 5)) {
            return;
        }

        // try-with-resources：无论成功失败都关闭会话，避免连接与 IO 线程泄漏。
        try (SshSession session = sshSessionFactory.open(server)) {
            appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "SSH 连接与认证成功");
            reportProgress(task, STAGE_CONNECT, PROGRESS_CONNECTED);

            // 逐项执行固定探活命令。
            for (Probe probe : PROBES) {
                if (!executeProbe(task, session, probe)) {
                    // 返回 false 表示任务已被取消：停止后续远程操作，交由执行器与状态机处理终态。
                    return;
                }
            }
            // 部署目录检查依赖配置且结论要分等级，因此独立实现而不放进固定的 PROBES 列表。
            if (!checkDeployDirectory(task, session, server)) {
                return;
            }
            // 全部探活项完成，进度收敛到 100，保证页面进度条与任务终态一致。
            taskService.updateProgress(task.getId(), "完成", PROGRESS_FINISHED);
            appendLog(task, STAGE_CONNECT, TaskLogLevel.INFO, "连通性测试完成，服务器可用");
        } catch (DeployException e) {
            // 业务异常原样上抛，保留「已停用」「私钥解析失败」这类可操作的具体原因。
            throw e;
        } catch (Exception e) {
            // SSH 层之外的异常（例如日志写入、连接关闭）统一包装成远程执行失败。
            log.warn("连通性测试执行异常，serverId={}", serverId, e);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "连通性测试执行失败：" + e.getMessage());
        }
    }

    /**
     * 检查目标机的部署根目录是否已就绪。
     *
     * <p>与固定探活项不同，这里的结论不能只看退出码：命令用 {@code ||} 兜底后始终返回 0，
     * 真正的结论在输出里。因此按输出判定——目录不存在或不可写时记 WARN 并给出建目录的提示，
     * 但不判任务失败：连通性正常、只是部署环境还没准备好，属于两类不同的问题。</p>
     *
     * @param task    当前任务
     * @param session 已认证的 SSH 会话
     * @param server  目标服务器，部署根目录从其档案读取
     * @return true 表示可以继续；false 表示任务已被取消，调用方应立即停止
     */
    private boolean checkDeployDirectory(Task task, SshSession session, Server server) {
        String stage = "部署目录";
        if (!reportProgress(task, stage, 85)) {
            return false;
        }
        // 数据库列带默认值，走到这里为空说明被手工改过；连通性本身不受影响，只提示补齐配置。
        if (!StringUtils.hasText(server.getAgentBaseDir())) {
            appendLog(task, stage, TaskLogLevel.INFO, "服务器未配置部署根目录，请在服务器页面补充后再做部署");
            return true;
        }
        String agentBaseDir = server.getAgentBaseDir().strip();
        // 只做只读检查，不在连通性测试阶段创建目录：写操作留给部署流程，测试不产生副作用。
        String command = "test -d " + agentBaseDir + " && test -w " + agentBaseDir
                + " && echo LIGHT_DEPLOY_DIR_READY || echo LIGHT_DEPLOY_DIR_MISSING";
        appendLog(task, stage, TaskLogLevel.INFO, "执行命令：" + command);
        SshCommandResult result = session.exec(command);
        if (result.success() && result.stdout().contains("LIGHT_DEPLOY_DIR_READY")) {
            appendLog(task, stage, TaskLogLevel.INFO, "部署目录存在且可写：" + agentBaseDir);
            return true;
        }
        appendLog(task, stage, TaskLogLevel.INFO,
                "部署目录 " + agentBaseDir + " 不存在或当前用户无写权限，首次发布前需要创建该目录并授权");
        return true;
    }

    /**
     * 执行单个探活项并把结果写入任务日志。
     *
     * @param task   当前任务
     * @param session 已认证的 SSH 会话
     * @param probe  探活项定义
     * @return true 表示可以继续执行后续探活项；false 表示任务已被取消，调用方应立即停止
     * @throws DeployException 必需探活项执行失败时抛出（判定服务器不可用）
     */
    private boolean executeProbe(Task task, SshSession session, Probe probe) {
        // 每个探活项执行前确认任务仍在执行中：任务被取消后不应继续对目标机发起命令。
        if (!reportProgress(task, probe.stage(), probe.progress())) {
            return false;
        }
        appendLog(task, probe.stage(), TaskLogLevel.INFO, "执行命令：" + probe.command());
        SshCommandResult result = session.exec(probe.command());
        if (result.success()) {
            // 命令输出按行写入日志，页面可以直接按行渲染，不需要再解析多行文本。
            writeOutput(task, probe.stage(), TaskLogLevel.INFO, result.stdout());
            return true;
        }
        // 失败的命令：stdout 可能是有效信息（如 test 命令的 echo），stderr 才是错误原因，两者都写。
        writeOutput(task, probe.stage(), TaskLogLevel.INFO, result.stdout());
        if (probe.required()) {
            String detail = StringUtils.hasText(result.stderr()) ? result.stderr().strip() : result.stdout().strip();
            appendLog(task, probe.stage(), TaskLogLevel.ERROR,
                    "必需探活命令执行失败，exit=" + result.exitCode() + "，输出：" + detail);
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "服务器连通性测试未通过：命令 " + probe.command() + " 执行失败（exit=" + result.exitCode() + "）");
        }
        // 可选项失败只提示不判定失败：例如目标机没有安装 Docker，属于环境现状而非连接问题。
        // 带 hint 的探活项（git / mvn 等构建依赖）在失败时追加可操作建议，避免用户只知道缺什么、不知道怎么办。
        String advice = StringUtils.hasText(probe.hint()) ? "；建议：" + probe.hint() : "";
        appendLog(task, probe.stage(), TaskLogLevel.INFO,
                "探活命令返回非零退出码，exit=" + result.exitCode() + "，"
                        + (StringUtils.hasText(result.stderr()) ? "stderr：" + result.stderr().strip()
                        : "该环境可能未安装对应组件") + advice);
        return true;
    }

    /**
     * 汇报任务阶段与进度。
     *
     * <p>任务可能在执行过程中被人工取消或删除，此时状态流转会被拒绝；
     * 这种情况不算执行异常，而是「应该停止后续远程操作」的信号，
     * 因此这里返回 false 并写一条 WARN 日志，由调用方决定停止时机。</p>
     *
     * @param task     当前任务
     * @param stage    要汇报的阶段名
     * @param progress 要汇报的进度百分比
     * @return true 表示任务仍在执行中；false 表示任务已不在执行中
     */
    private boolean reportProgress(Task task, String stage, int progress) {
        try {
            taskService.updateProgress(task.getId(), stage, progress);
            return true;
        } catch (Exception e) {
            appendLog(task, stage, TaskLogLevel.INFO, "任务已不在执行中，停止后续远程操作：" + e.getMessage());
            return false;
        }
    }

    /**
     * 把命令输出按行写入任务日志。
     *
     * <p>空输出写一条「无输出」提示，避免页面出现空白阶段让人误以为命令没执行；
     * 超过行数上限的部分只提示行数，不继续写入日志。</p>
     *
     * @param task    当前任务
     * @param stage   日志所属阶段
     * @param level   日志级别
     * @param output  命令输出文本，允许为空
     */
    private void writeOutput(Task task, String stage, TaskLogLevel level, String output) {
        if (!StringUtils.hasText(output)) {
            appendLog(task, stage, level, "（命令无输出）");
            return;
        }
        String[] lines = output.strip().split("\\R");
        // 逐行写入，保持远端输出原貌；超过上限的行数只记录总量提示。
        for (int i = 0; i < lines.length && i < MAX_OUTPUT_LINES; i++) {
            appendLog(task, stage, level, lines[i]);
        }
        if (lines.length > MAX_OUTPUT_LINES) {
            appendLog(task, stage, TaskLogLevel.INFO,
                    "（输出共 " + lines.length + " 行，仅保留前 " + MAX_OUTPUT_LINES + " 行）");
        }
    }

    /**
     * 写入一条任务日志。
     *
     * <p>日志写入失败不影响测试结论，只记录 WARN，避免因为日志表问题把可达的服务器判成不可达。</p>
     *
     * @param task    当前任务
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
            log.warn("写入连通性测试日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }
}
