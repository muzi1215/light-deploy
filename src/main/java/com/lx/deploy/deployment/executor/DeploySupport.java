package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import com.lx.deploy.task.model.Task;
import com.lx.deploy.task.model.dto.TaskLogDto;
import com.lx.deploy.task.service.TaskLogService;
import com.lx.deploy.common.util.ShellQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 部署执行的共用支撑：任务日志、产物中转、路径与字符串工具、阶段名常量。
 *
 * <p>按运行时拆出 {@code JarDeployService} / {@code StaticDeployService} / {@code DockerDeployService}
 * 之后，这三个 Service 都要写任务日志、都要从构建机下载产物、都要拼 shell 命令与规范化路径。
 * 这些能力与「发的是什么」无关，属于发布链路的公共设施，集中放在这里供三者复用，
 * 而不是各抄一份——抄三份的下场是日志格式与路径处理规则慢慢分化。</p>
 *
 * <p>本类不持有任何运行时分支判断，也不感知批次编排：它只提供「怎么做」的零件，
 * 什么时候做由 Service 决定。</p>
 *
 * <p><b>日志必须允许失败</b>：任务日志写入失败不能反过来让发布失败，
 * 否则一次日志库抖动会把整个发布打成失败，而机器上的状态其实已经变更完成。
 * 因此 {@link #appendLog} 内部捕获异常只告警。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Component
public class DeploySupport {

    /** 阶段名：准备。 */
    public static final String STAGE_PREPARE = "准备";
    /** 阶段名：分发。 */
    public static final String STAGE_DELIVER = "分发";
    /** 阶段名：启动。 */
    public static final String STAGE_START = "启动";
    /** 阶段名：健康检查。 */
    public static final String STAGE_HEALTH = "健康检查";
    /** 阶段名：收尾。 */
    public static final String STAGE_FINISH = "收尾";

    /** 应用在目标机部署根目录下的子目录名，平台约定，不单独提供配置项。 */
    private static final String APPS_SUBDIR = "apps";

    /** 单条命令输出最多写入日志的行数，避免镜像层输出刷屏。 */
    private static final int MAX_OUTPUT_LINES = 30;

    /** 健康检查通过时 systemd 状态保留的行数：只需证明「服务在跑」，够用即可。 */
    private static final int STATUS_LINES_ON_SUCCESS = 8;

    /**
     * 日志与备份名里的时间格式。
     *
     * <p>时间必须写进日志正文与文件名：页面按分钟展示时看不出先后，
     * 而发布/回滚的排查首先要回答「什么时候发生」。</p>
     */
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 备份名与临时目录名里的时间戳格式，字典序即时间序。 */
    private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 服务器 Mapper：按 ID 读取构建机档案。 */
    private final ServerMapper serverMapper;

    /** SSH 会话工厂：建立到构建机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 任务日志服务：把执行过程写入 lds_task_log。 */
    private final TaskLogService taskLogService;

    /**
     * 创建部署支撑组件。
     *
     * @param serverMapper      服务器 Mapper
     * @param sshSessionFactory SSH 会话工厂
     * @param taskLogService    任务日志服务
     */
    public DeploySupport(ServerMapper serverMapper, SshSessionFactory sshSessionFactory,
                         TaskLogService taskLogService) {
        this.serverMapper = serverMapper;
        this.sshSessionFactory = sshSessionFactory;
        this.taskLogService = taskLogService;
    }

    // ==================== 任务日志 ====================

    /**
     * 写入一条任务日志。
     *
     * <p>日志写入失败只告警不抛出：发布动作本身可能已经改变了机器状态，
     * 让一次日志库抖动把发布判成失败，会造成「记录说失败、磁盘已变更」的错位。</p>
     *
     * @param task    当前任务
     * @param stage   日志所属阶段
     * @param level   日志级别
     * @param content 日志内容（调用方须保证已脱敏，不含凭据）
     */
    public void appendLog(Task task, String stage, TaskLogLevel level, String content) {
        TaskLogDto dto = new TaskLogDto();
        dto.setTaskId(task.getId());
        dto.setStage(stage);
        dto.setLogLevel(level.getCode());
        dto.setContent(content);
        try {
            taskLogService.create(dto);
        } catch (Exception e) {
            log.warn("写入部署日志失败，taskId={}，原因：{}", task.getId(), e.getMessage());
        }
    }

    /**
     * 把命令输出按行写入任务日志。
     *
     * <p>只取前 {@code MAX_OUTPUT_LINES} 行：docker pull、tar 这类命令输出可达数百行，
     * 全量落库会把真正关键的结论行淹没。</p>
     *
     * @param task   当前任务
     * @param stage  阶段名
     * @param output 命令标准输出
     */
    public void writeOutput(Task task, String stage, String output) {
        if (!StringUtils.hasText(output)) {
            return;
        }
        String[] lines = output.strip().split("\\R");
        for (int i = 0; i < lines.length && i < MAX_OUTPUT_LINES; i++) {
            appendLog(task, stage, TaskLogLevel.INFO, lines[i]);
        }
    }

    /**
     * 健康检查失败时补一段 systemd 诊断，写入任务日志。
     *
     * <p><b>为什么需要</b>：{@code systemctl is-active} 返回 active 只说明进程还活着，
     * 不代表端口已经在监听——应用可能启动失败后由 systemd 反复重启，也可能卡在初始化。
     * 这种情况下发布日志写着「服务启动成功」，紧接着健康检查失败，
     * 运维只能登机器翻 journalctl 才知道原因。诊断直接贴到任务日志里，
     * 让「启动成功却不健康」这种矛盾现场在同一屏内可解释。</p>
     *
     * <p>只在 JAR 运行时调用：只有它走 systemd，DOCKER 与 STATIC 没有对应单元。
     * 诊断本身失败不影响发布结论，只记录原因。</p>
     *
     * <p><b>健康检查通过时同样输出</b>：发布日志是唯一一份同时记录了「平台视角」与
     * 「目标机视角」的材料。只在失败时输出，等于成功时没有任何证据表明服务真的起来了——
     * 事后要回溯「当时那台机器上跑的到底是不是这个版本」只能登机器翻 journalctl。</p>
     *
     * @param task    当前任务
     * @param server  目标服务器
     * @param config  部署配置，提供 systemd 服务名
     * @param project 项目，服务名未配置时回落到项目编码
     * @param healthy 健康检查是否通过：决定日志级别、标题措辞与保留行数
     */
    public void appendServiceDiagnosis(Task task, Server server, DeployConfig config, Project project,
                                       boolean healthy) {
        String unit = StringUtils.hasText(config.getServiceName())
                ? config.getServiceName() : project.getCode();
        if (!StringUtils.hasText(unit)) {
            return;
        }
        // 通过时是「留证」：INFO 级、只保留能证明服务在跑的头部几行；
        // 失败时是「取证」：ERROR 级、放宽行数，因为要找的原因往往在尾部。
        TaskLogLevel level = healthy ? TaskLogLevel.INFO : TaskLogLevel.ERROR;
        int maxLines = healthy ? STATUS_LINES_ON_SUCCESS : MAX_OUTPUT_LINES;
        try (SshSession session = sshSessionFactory.open(server)) {
            SshCommandResult status = session.exec(
                    "systemctl status " + unit + " --no-pager -n 15 2>&1 | tail -20");
            appendLog(task, STAGE_HEALTH, level, healthy
                    ? "systemd 状态（systemctl status " + unit + "），留证服务处于运行态："
                    : "systemd 诊断（systemctl status " + unit + "），用于确认服务是否真的在监听端口：");
            String output = StringUtils.hasText(status.stdout()) ? status.stdout() : status.stderr();
            if (!StringUtils.hasText(output)) {
                appendLog(task, STAGE_HEALTH, level, "（无输出，目标机可能没有 systemd）");
                return;
            }
            String[] lines = output.strip().split("\\R");
            for (int i = 0; i < lines.length && i < maxLines; i++) {
                appendLog(task, STAGE_HEALTH, level, lines[i]);
            }
        } catch (Exception e) {
            appendLog(task, STAGE_HEALTH, level,
                    (healthy ? "systemd 状态获取失败：" : "systemd 诊断执行失败：") + e.getMessage());
        }
    }

    // ==================== 产物中转 ====================

    /**
     * 从构建机下载产物到平台本地临时文件。
     *
     * <p>采用「构建机 → 平台 → 目标机」中转而不是服务器间直传：
     * 目标机不需要持有构建机的免密通道，凭据与网络策略都收敛在平台侧。
     * JAR 与 STATIC 两个文件类运行时共用这一段。</p>
     *
     * @param task        当前任务
     * @param release     版本记录（提供构建机 ID）
     * @param remotePath  构建机上的产物绝对路径
     * @param fileName    产物文件名，用于临时文件命名
     * @param deployment  当前部署（用于临时目录命名）
     * @return 平台本地的临时文件，调用方须在 finally 中 {@link #deleteQuietly} 清理
     * @throws DeployException 版本缺少构建机、构建机不存在或下载失败时抛出
     */
    public Path downloadArtifact(Task task, ReleaseRecord release, String remotePath,
                                 String fileName, Deployment deployment) {
        if (!StringUtils.hasText(release.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + release.getVersion() + " 未记录构建机（多为历史版本），请重新构建后再发布");
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "版本记录的构建机不存在，无法下载产物");
        }
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            Path localFile = Files.createTempFile("ld-deploy-" + deployment.getId() + "-", "-" + fileName);
            long size = buildSession.sftpDownload(remotePath, localFile);
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "已从构建机下载产物 " + remotePath + "（" + readableSize(size) + "）");
            return localFile;
        } catch (IOException e) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR, "创建平台本地临时文件失败：" + e.getMessage());
        }
    }

    /**
     * 把字节数格式化为易读的大小文本，保留原始字节数以便精确核对。
     *
     * <p>任务日志是运维第一手排查材料：只写裸字节数（如 77607226）时，
     * 判断「这是 74 MB 还是 740 MB」需要心算，而产物大小的量级本身往往是线索
     * （几十 MB 的 jar、几百 MB 的静态包）。因此主显示用 MB/GB，
     * 括号里保留字节数——进度、SHA256 这种精确值在日志里一律不丢。</p>
     *
     * <p>使用 {@link java.util.Locale#ROOT}：默认 Locale 下某些语言环境会把
     * 小数分隔符输出成逗号，同一份日志在不同机器上格式不一致会增加检索难度。</p>
     *
     * @param bytes 字节数，负数时原样输出
     * @return 形如 {@code 74.02 MB（77607226 字节）} 的文本
     */
    public String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.2f KB（%d 字节）", kb, bytes);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.2f MB（%d 字节）", mb, bytes);
        }
        return String.format(Locale.ROOT, "%.2f GB（%d 字节）", mb / 1024.0, bytes);
    }

    /**
     * 计算传输速率文本，与 {@link #readableSize} 一样使用 {@link java.util.Locale#ROOT}。
     *
     * <p>只报速率不报大小是不够的：判断「5 分钟传 74 MB」是慢还是正常，
     * 需要同时看到体量和耗时，因此调用方会把两者写在相邻位置。</p>
     *
     * @param bytes   已传输字节数
     * @param seconds 耗时秒数，非正数时认为瞬时完成
     * @return 形如 {@code 0.23 MB/s} 的文本
     */
    public String transferRate(long bytes, long seconds) {
        if (seconds <= 0) {
            return "瞬时完成";
        }
        double mbPerSec = bytes / 1024.0 / 1024.0 / seconds;
        return String.format(Locale.ROOT, "%.2f MB/s", mbPerSec);
    }

    // ==================== 路径与字符串工具 ====================

    /**
     * 推导应用在目标机上的部署根目录。
     *
     * <p>默认落在「目标机部署根目录」的 apps 子目录下：部署根目录由服务器页面按机器配置，
     * apps 是平台约定的子目录名，两者拼出的绝对路径不再有写死在代码里的部分。
     * 服务器档案未配置部署根目录时直接报错——代码不持有兜底路径，
     * 与其用一个大概对的目录把应用发到意外位置，不如让运维显式补齐配置。</p>
     *
     * @param server 目标服务器
     * @return 应用部署根目录绝对路径
     * @throws DeployException 目标服务器未配置部署根目录时抛出
     */
    public String deployBase(Server server) {
        String base = server.getAgentBaseDir();
        if (!StringUtils.hasText(base)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "目标服务器未配置部署根目录，请在服务器页面补充该目录后重新发布");
        }
        return base.strip() + "/" + APPS_SUBDIR;
    }

    /**
     * 规范化目录路径：去掉首尾空白与结尾斜杠，避免拼出 {@code //} 形式的目录。
     *
     * <p>空值时回落到调用方给出的默认路径，自己不定义任何绝对路径。</p>
     *
     * @param path     原始路径，允许为空
     * @param fallback 路径为空时使用的默认路径
     * @return 规范化后的路径
     */
    public String normalize(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        String trimmed = path.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 取路径的最后一段目录/文件名。
     *
     * @param path 绝对路径
     * @return 末段名称；路径不含分隔符时返回原值
     */
    public String baseName(String path) {
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }

    /**
     * 取路径的父目录。
     *
     * @param path 绝对路径
     * @return 父目录；路径不含分隔符时返回 {@code /}
     */
    public String parentDir(String path) {
        return path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "/";
    }

    /**
     * 把参数转成 shell 单引号字面量，避免空格与引号破坏命令行。
     *
     * @param value 原始值，允许为空
     * @return 可直接拼进 shell 命令的引号包裹值
     */
    public String shellQuote(String value) {
        return ShellQuote.quote(value);
    }

    /**
     * 取命令输出第一行（用于错误摘要）。
     *
     * @param text 原始输出
     * @return 第一行；为空时返回默认文案
     */
    public String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String trimmed = text.strip();
        int index = trimmed.indexOf('\n');
        return index < 0 ? trimmed : trimmed.substring(0, index);
    }

    /**
     * 取命令失败摘要：优先 stderr，为空回退 stdout，保留<b>末尾若干行</b>。
     *
     * <p><b>为什么不能直接用 {@link #firstLine}</b>：{@code docker compose} / {@code docker pull}
     * 这类命令把「Network xxx Creating」「Pulling from xxx」的进度行打到 stderr，
     * 真正的失败原因排在后面。只取第一行等于把真正的报错丢掉，日志里只剩一句无害的进度，
     * 表现为「看着像失败原因其实是噪音」——曾出现过只显示
     * “Network auth-tool_default Creating” 而真实原因是桥接子网冲突的情况。</p>
     *
     * <p>因此保留末尾行（错误通常在最后），同时限制行数与总长度，避免把几百行无关输出刷进任务日志。</p>
     *
     * @param stdout 标准输出
     * @param stderr 标准错误
     * @return 摘要文本；两者皆空时返回默认文案
     */
    public String outputSummary(String stdout, String stderr) {
        String text = StringUtils.hasText(stderr) ? stderr : stdout;
        if (!StringUtils.hasText(text)) {
            return "无详细信息";
        }
        String[] lines = text.strip().split("\\R");
        int maxLines = 40;
        int from = Math.max(0, lines.length - maxLines);
        StringBuilder sb = new StringBuilder();
        if (from > 0) {
            sb.append("…（前面省略 ").append(from).append(" 行）\n");
        }
        for (int i = from; i < lines.length; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        String summary = sb.toString();
        int maxChars = 4000;
        return summary.length() > maxChars ? "…" + summary.substring(summary.length() - maxChars) : summary;
    }

    /**
     * 当前时间文本，用于把时间写进日志正文。
     *
     * @return 形如 {@code 2026-09-27 19:40:00} 的时间文本
     */
    public String nowText() {
        return LocalDateTime.now().format(LOG_TIME_FORMAT);
    }

    /**
     * 当前时间戳，用于备份名与临时目录名。
     *
     * <p>格式为 {@code yyyyMMddHHmmss}：字典序即时间序，{@code ls} 与 {@code ls -d ... | head -1}
     * 都能直接按时间排序取用，不必解析。</p>
     *
     * @return 形如 {@code 20260927194000} 的时间戳
     */
    public String stamp() {
        return LocalDateTime.now().format(STAMP_FORMAT);
    }

    /**
     * 判断路径是否为受支持的归档文件。
     *
     * <p>支持 {@code .tar.gz} / {@code .tgz} / {@code .zip}。zip 是前端本地构建产物最常见的交付形式
     * （{@code npm run build} 后直接打包 dist），不认识它会把上传的静态包当成单文件拷进 nginx 目录，
     * 页面看到一个 zip 而不是 index.html。</p>
     *
     * @param path 文件或目录路径，允许为空
     * @return true 表示是受支持的归档文件
     */
    public boolean isArchive(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".zip");
    }

    /**
     * 按产物类型生成解压命令片段。
     *
     * <p>zip 依赖目标机的 unzip，tar.gz 依赖 tar：前者在最小化安装的机器上可能没有，
     * 缺了会在解压这一步报「command not found」，因此解压前由调用方探测并给出明确提示。</p>
     *
     * @param archivePath 归档文件绝对路径
     * @param targetDir   解压目标目录
     * @return 解压命令片段
     * @throws DeployException 归档格式不受支持时抛出
     */
    public String extractCommand(String archivePath, String targetDir) {
        String lower = archivePath.toLowerCase();
        if (lower.endsWith(".zip")) {
            return "unzip -oq " + archivePath + " -d " + targetDir;
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            return "tar -xzf " + archivePath + " -C " + targetDir;
        }
        throw new DeployException(ResponseCode.VALIDATION_ERROR,
                "不受支持的产物格式（仅支持 .zip / .tar.gz / .tgz）：" + archivePath);
    }

    /**
     * 删除平台本地临时文件，失败只记录不抛出。
     *
     * <p>临时文件残留不会让发布结论出错，但会持续占用磁盘；
     * 清理失败属于可容忍问题，不能因此覆盖真正的发布结果。</p>
     *
     * @param file 待删除文件，允许为 null
     */
    public void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除部署临时文件失败，file={}", file, e);
        }
    }

    /**
     * 判断部署主记录是否处于回滚中状态。
     *
     * @param deployment 部署主记录
     * @return true 表示本次执行是回滚
     */
    public boolean isRollingBack(Deployment deployment) {
        return deployment.getStatus() != null
                && deployment.getStatus() == DeploymentStatus.ROLLING_BACK;
    }

    // ==================== 配置分发（JAR / DOCKER 共用） ====================

    /**
     * 版本产物目录下存放随行配置文件的子目录名。
     *
     * <p>必须与构建阶段的 {@code BuildTaskHandler.CONFIG_SUBDIR} 保持一致：构建把
     * {@code build_config_json.config_paths} 指到的文件转存到
     * {@code {build_artifact_dir}/{项目编码}/{版本}/config}，发布端从这个目录取。</p>
     */
    public static final String CONFIG_SUBDIR = "config";

    /**
     * 计算「构建机上的版本配置目录」。
     *
     * <p>用约定拼路径而不读 {@code release_record.artifact_path}：DOCKER 管道的产物是镜像，
     * 该字段为 null，但构建阶段仍按同一约定把配置转存到了版本目录下的 config 子目录。
     * 两个运行时共用同一份约定，发布端就不必为「有没有 artifact_path」分叉。</p>
     *
     * @param buildServer 构建机档案，可为 null（此时返回 null）
     * @param projectCode 项目编码
     * @param version     发布版本号
     * @return 形如 {@code /lx/light-deploy/artifacts/auth-tool/auth-tool-v9-536fd1c6/config} 的绝对路径；
     *         构建机或产物根目录未配置时返回 null
     */
    public String versionConfigDir(Server buildServer, String projectCode, String version) {
        if (buildServer == null || !StringUtils.hasText(buildServer.getBuildArtifactDir())
                || !StringUtils.hasText(projectCode) || !StringUtils.hasText(version)) {
            return null;
        }
        String base = buildServer.getBuildArtifactDir().strip();
        while (base.endsWith("/") && base.length() > 1) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + projectCode + "/" + version + "/" + CONFIG_SUBDIR;
    }

    /**
     * 从构建机下载某目录下的全部配置文件到平台本地临时文件。
     *
     * <p><b>配置是增强项，不是发版的必要条件</b>：构建机不可达、目录不存在、目录为空，
     * 一律返回空 Map 并继续发布——是否真的起得来由服务启动与健康检查判定。
     * 唯一会抛出的是「本地临时文件创建失败」，那是平台自己的磁盘问题，必须暴露。</p>
     *
     * @param task        当前任务，用于写日志
     * @param buildServer 构建机档案，为 null 时直接返回空 Map
     * @param configDir   构建机上的配置目录绝对路径，为 null 或空时直接返回空 Map
     * @return 文件名 → 本地临时文件路径（保持目录列举顺序）
     * @throws DeployException 平台本地临时文件创建失败时抛出
     */
    public Map<String, Path> downloadConfigFiles(Task task, Server buildServer, String configDir) {
        Map<String, Path> result = new LinkedHashMap<>();
        if (buildServer == null || !StringUtils.hasText(configDir)) {
            return result;
        }
        // 只有确实可能有配置时才建立 SSH 会话：绝大多数项目没有这个目录，
        // 让「无配置」这个最常见分支不必多付一次握手。
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            SshCommandResult ls = buildSession.exec("ls -1 " + ShellQuote.quote(configDir) + " 2>/dev/null");
            if (!ls.success() || !StringUtils.hasText(ls.stdout())) {
                return result;
            }
            for (String line : ls.stdout().split("\\R")) {
                String fileName = line.strip();
                if (fileName.isEmpty()) {
                    continue;
                }
                Path local;
                try {
                    local = Files.createTempFile("ld-deploy-" + task.getId() + "-", "-" + fileName);
                } catch (IOException e) {
                    throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                            "创建平台本地临时文件失败：" + e.getMessage());
                }
                buildSession.sftpDownload(configDir + "/" + fileName, local);
                result.put(fileName, local);
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "已从构建机下载配置文件 " + fileName);
            }
        }
        return result;
    }

    /**
     * 把配置文件投放到目标机的配置目录（保守模式：存在即跳过）。
     *
     * <p>等价 {@link #uploadConfigFiles(Task, SshSession, String, Map, boolean)} 传 false，
     * 保留历史行为：目标机已存在同名文件时跳过、不覆盖，保护运维手动改过的现场配置。
     * JAR 运行时走的就是这一条。</p>
     *
     * @see #uploadConfigFiles(Task, SshSession, String, Map, boolean)
     */
    public int uploadConfigFiles(Task task, SshSession session, String targetDir, Map<String, Path> files) {
        return uploadConfigFiles(task, session, targetDir, files, false);
    }

    /**
     * 把配置文件投放到目标机的配置目录。
     *
     * <p><b>配置是增强项，不是发版的必要条件</b>：构建机不可达、目录不存在、目录为空，
     * 一律返回 0 并继续发布——是否真的起得来由服务启动与健康检查判定。</p>
     *
     * <p><b>两种写入策略</b>：</p>
     * <ul>
     *   <li>{@code overwrite = false}（默认，保守）：目标机已存在同名文件则跳过、不覆盖，
     *       保护运维手动改过的现场配置（改库地址 / 账号等）。JAR 与 DOCKER 共用此口径。</li>
     *   <li>{@code overwrite = true}（强制）：先比对内容——与本次版本一致则跳过；不一致则把
     *       目标机旧文件改名备份为 {@code <文件名>_bak_<时间戳>}（单文件、不建目录，
     *       与 JAR 备份约定同源）再写入新配置。备份留退路，符合容灾底线；用于「配置由仓库管理、
     *       改了仓库配置想下发」的场景，绕开默认的「存在即跳过」导致新配置传不下去的问题。</li>
     * </ul>
     *
     * @param task      当前任务，用于写日志
     * @param session   已连接的目标机会话
     * @param targetDir 目标机配置目录绝对路径
     * @param files     文件名 → 本地临时文件路径
     * @param overwrite 是否强制覆盖（内容不一致时备份后覆盖）
     * @return 本次实际写入（新增或覆盖）的文件数量；跳过的文件不计入
     * @throws DeployException 创建配置目录、备份旧配置或上传新配置失败时抛出
     */
    public int uploadConfigFiles(Task task, SshSession session, String targetDir, Map<String, Path> files,
                                 boolean overwrite) {
        if (files == null || files.isEmpty()) {
            return 0;
        }
        SshCommandResult mkdir = session.exec("mkdir -p " + ShellQuote.quote(targetDir));
        if (!mkdir.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建配置目录失败：" + firstLine(mkdir.stderr()));
        }
        // 备份时间戳整次调用共用一个：保证同一次部署覆盖的多个文件时间戳一致，便于一眼归类。
        String backupTs = null;
        int delivered = 0;
        for (Map.Entry<String, Path> entry : files.entrySet()) {
            String fileName = entry.getKey();
            String target = targetDir + "/" + fileName;
            SshCommandResult exists = session.exec("test -f " + ShellQuote.quote(target) + " && echo yes || echo no");
            boolean targetExists = exists.success() && "yes".equals(exists.stdout().strip());
            if (!targetExists) {
                session.sftpUpload(entry.getValue(), target);
                delivered++;
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO, "已投放配置文件 " + fileName);
                continue;
            }
            // 目标机已存在同名文件。
            if (!overwrite) {
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                        "目标机已存在 " + fileName + "，本次不覆盖（如需用仓库配置覆盖请勾选「强制覆盖配置文件」）");
                continue;
            }
            // 强制覆盖：先比对内容，一致则不动作，避免无谓备份。
            if (contentEquals(entry.getValue(), target, session)) {
                appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                        "目标机 " + fileName + " 与本次版本内容一致，跳过");
                continue;
            }
            // 内容不一致：把目标机旧文件改名备份为 <文件名>_bak_<时间戳>（单文件、不建目录，
            // 与 JAR 备份约定 {文件名}_bak_{时间戳} 同源），再写入本次版本的新配置。
            if (backupTs == null) {
                backupTs = LocalDateTime.now().format(BACKUP_TS_FMT);
            }
            String backupPath = target + "_bak_" + backupTs;
            SshCommandResult mv = session.exec("mv " + ShellQuote.quote(target) + " " + ShellQuote.quote(backupPath));
            if (!mv.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "备份目标机旧配置失败：" + firstLine(mv.stderr()));
            }
            session.sftpUpload(entry.getValue(), target);
            delivered++;
            appendLog(task, STAGE_DELIVER, TaskLogLevel.INFO,
                    "目标机 " + fileName + " 与本次版本不一致，已备份旧配置为 " + backupPath + " 并写入新配置");
        }
        return delivered;
    }

    /** 备份文件名时间戳格式：整次调用共用，保证同一次部署覆盖的多个文件时间戳一致。 */
    private static final DateTimeFormatter BACKUP_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 比对「平台本地临时文件」与「目标机现有文件」的内容是否一致（按 MD5）。
     *
     * <p>本地文件是刚从构建机下载的本次版本配置；目标机文件是现场正在用的配置。
     * 两者 MD5 相等即视为同一份，无需备份覆盖；不等才需要备份后覆盖。
     * 任意一端取不到摘要都按「不一致」处理——宁可多一次备份，也不要漏覆盖。</p>
     *
     * @param local        本地临时文件路径（本次版本配置）
     * @param remoteTarget 目标机现有文件绝对路径
     * @param session      已连接的目标机会话
     * @return true 表示内容一致
     */
    private boolean contentEquals(Path local, String remoteTarget, SshSession session) {
        String localMd5 = md5Hex(local);
        if (localMd5 == null) {
            return false;
        }
        SshCommandResult r = session.exec("md5sum " + ShellQuote.quote(remoteTarget));
        if (!r.success() || !StringUtils.hasText(r.stdout())) {
            return false;
        }
        String remoteMd5 = r.stdout().trim().split("\\s+")[0];
        return localMd5.equalsIgnoreCase(remoteMd5);
    }

    /** 计算本地文件 MD5 的十六进制串；读取或计算失败时返回 null。 */
    private String md5Hex(Path local) {
        try {
            byte[] data = Files.readAllBytes(local);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
