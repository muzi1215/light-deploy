package com.lx.deploy.deployment.executor;

import com.lx.deploy.common.enums.task.TaskLogLevel;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * STATIC 运行时发布服务：静态站点目录的<b>内容替换</b>。
 *
 * <p><b>形态</b>：nginx 的 root 指向哪个目录是运维既有的事实配置，平台<b>不要求改它</b>，
 * 只负责把产物填到那个目录里。目录结构为：</p>
 *
 * <pre>
 * /usr/share/nginx/html/            ← nginx 已配好的根，平台不动
 * ├── aiops/                        ← static_root，inode 全程恒定
 * └── static-bak/                   ← 同级备份目录
 *     ├── aiops_bak_20260927193000_aiops-v12-3f9a1c2/
 *     └── aiops_bak_20260926120000_aiops-v11-8b21e04/
 * </pre>
 *
 * <p><b>为什么是「替换目录内容」而不是「换目录」（2026-09-28 修正）</b>：早期实现是两次目录级
 * {@code mv}——{@code mv static_root bak/...} 再 {@code mv staged static_root}，换来微秒级原子性，
 * 代价是 <b>static_root 的 inode 每次发布都变</b>。而 Docker 的 bind mount 是容器启动时按宿主机路径
 * <b>解析一次并绑定 inode</b> 的：源目录被 rename 走之后，容器仍握着旧 inode，
 * 于是容器内看到的是空目录（源 inode 被彻底删除时）或与宿主机不同步的内容，
 * nginx 报 {@code directory index of "..." is forbidden}，站点全站 403。
 * 此时 {@code nginx -s reload} 无效——reload 不重新挂载，只能重建容器。
 * 静态站点跑在容器里是常规形态，「变 inode」这条没得商量，因此改为
 * <b>只搬目录内的条目，绝不 rename 或删除 static_root 本身</b>。</p>
 *
 * <p><b>代价（必须知道）</b>：切换不再是单次 rename，而是「旧内容搬去备份 → 新内容搬进来」
 * 两批 rename，中间存在极短的目录为空窗口。可接受的原因是两种失败模式的严重程度不对等：
 * 内容替换的最坏结果是瞬时 404 且自愈；换目录的最坏结果是容器陷入失效挂载、全站 403，
 * 且必须重建容器才能恢复。</p>
 *
 * <p><b>为什么仍然先落到旁路暂存目录</b>：解压与搬运失败时不会污染正在提供服务的目录，
 * 失败现场留在 {@code static-new-{时间戳}} 里可查。就位只发生在产物已完整就绪之后。</p>
 *
 * <p><b>备份名必须带版本号</b>：回滚是按 {@code old_version} 精确回退到某个版本，不是「回到上一次」。
 * 只有时间戳的话，跨天分不清、一天发多次也分不清。版本号是连接
 * {@code release_record}、备份目录、构建机产物目录三者的唯一钥匙。</p>
 *
 * <p><b>回滚快路径</b>：回滚前先探测目标机备份目录里有没有目标版本的备份，
 * 有则直接 {@code cp -a} 回来——不下载、不解压、不过构建机，秒级完成。
 * 这是把备份留在目标机本地的全部意义：出事时最快的路径不应该依赖构建机还活着。</p>
 *
 * @author lx
 * @since 2026-09-27 19:40:00
 */
@Slf4j
@Service
public class StaticDeployService {

    /**
     * 默认备份目录名，位于 static_root 的父目录下。
     *
     * <p><b>不能以点开头</b>：曾经叫 {@code .static-bak}，本意是让 nginx 不容易直接暴露它，
     * 但隐藏目录在终端 {@code ls} 里看不见，回滚时运维要翻备份、清理磁盘时也要找它，
     * 看不见比被访问到更麻烦。安全那条由「把备份目录配到 web 根之外」解决，不靠隐藏。</p>
     */
    private static final String DEFAULT_BACKUP_DIR_NAME = "static-bak";

    /** 目录型产物打包后的文件名前缀。 */
    private static final String PACKAGE_PREFIX = "ld-static-";

    /**
     * 新版本暂存目录名前缀，位于 static_root 的父目录下。
     *
     * <p>同样不以点开头：发布中途失败会留下这个目录，运维要能在终端直接看见并清理。</p>
     */
    private static final String STAGING_PREFIX = "static-new-";

    /** 版本号允许的字符，用于拼进 shell 前的校验，防止版本号被当作命令注入。 */
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * 目标机 shell 函数 {@code _ld_move}：把源目录<b>内的所有条目</b>（含隐藏文件）搬到目标目录，
     * 源与目标两个目录<b>本身都不动</b>。
     *
     * <p><b>这是本服务最重要的一个约束</b>：静态站点目录常被 Docker bind mount 进容器，
     * 而 bind mount 绑定的是 inode，目录一旦被 rename 或删除，容器里的挂载就失效
     * （表现是容器内目录为空、nginx 全站 403，且只能通过重建容器恢复）。
     * 因此发布全过程只操作目录<b>内</b>的条目，目录自身的 inode 保持恒定。</p>
     *
     * <p>四条 shell 细节都是必须的，改任何一条都会在生产上踩坑：</p>
     * <ul>
     *   <li>{@code cd "$s"} 后进子 shell 用相对路径：避免把源路径反复拼进命令，
     *       同时把 {@code set --} 覆盖位置参数的影响限制在子 shell 内；</li>
     *   <li>非隐藏条目用 {@code set -- *} 后<b>整批</b> {@code mv}：逐个 mv 等于逐个 fork，
     *       上千文件的站点会把切换窗口拖到分钟级；</li>
     *   <li>{@code .[!.]* ..?*} 显式覆盖隐藏文件——{@code *} 不匹配点开头的文件，
     *       只写 {@code *} 会漏掉 {@code .env}、{@code .well-known} 这类真实存在的站点文件，
     *       两个模式合起来排除了 {@code .} 与 {@code ..}；隐藏条目数量少，逐个搬不影响耗时；</li>
     *   <li>{@code if [ -e "$f" ]}：glob 无匹配时保留字面量，必须逐个判存在才能安全跳过。
     *       <b>不能写成 {@code [ -e "$f" ] && mv ... || exit 1}</b>——{@code &&} 短路时
     *       整个表达式为假，会去执行 {@code || exit 1}，导致「没有隐藏文件」被判成失败。</li>
     * </ul>
     */
    private static final String MOVE_CONTENTS =
            "_ld_move() { s=\"$1\"; d=\"$2\"; mkdir -p \"$d\" || return 1; "
            + "(cd \"$s\" || exit 1; set -- *; "
            + "if [ -e \"$1\" ]; then mv -- \"$@\" \"$d\"/ || exit 1; fi; "
            + "for f in .[!.]* ..?*; do if [ -e \"$f\" ]; then mv -- \"$f\" \"$d\"/ || exit 1; fi; done)"
            + " || return 1; }; ";

    /** 部署支撑组件：任务日志、产物下载与通用工具。 */
    private final DeploySupport support;

    /** SSH 会话工厂：建立到目标机与构建机的已认证会话。 */
    private final SshSessionFactory sshSessionFactory;

    /** 服务器 Mapper：读取构建机档案以打包目录型产物。 */
    private final ServerMapper serverMapper;

    /**
     * 创建 STATIC 发布服务。
     *
     * @param support           部署支撑组件
     * @param sshSessionFactory SSH 会话工厂
     * @param serverMapper      服务器 Mapper
     */
    public StaticDeployService(DeploySupport support, SshSessionFactory sshSessionFactory,
                               ServerMapper serverMapper) {
        this.support = support;
        this.sshSessionFactory = sshSessionFactory;
        this.serverMapper = serverMapper;
    }

    /**
     * 在目标机上完成一次静态产物替换。
     *
     * <p>发布与回滚共用本方法，差别只有两处：目标版本号不同（由 {@link DeployContext} 换算），
     * 以及回滚优先尝试本地备份快路径。</p>
     *
     * @param ctx 目标级发布上下文
     * @throws DeployException 产物缺失、打包/下载/解压失败或目录切换失败时抛出
     */
    public void deploy(DeployContext ctx) {
        ReleaseRecord release = ctx.release();
        String artifactPath = release.getArtifactPath();
        if (!StringUtils.hasText(artifactPath)) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 没有产物路径，无法按 STATIC 运行时发布");
        }
        String staticRoot = support.normalize(ctx.config().getStaticRoot(),
                "/usr/share/nginx/html/" + ctx.project().getCode());
        String parent = support.parentDir(staticRoot);
        String dirName = support.baseName(staticRoot);
        String backupDir = support.normalize(ctx.config().getStaticBackupDir(),
                parent + "/" + DEFAULT_BACKUP_DIR_NAME);
        // 备份目录落在静态目录内部是配置错误：备份这一步会把备份目录本身也当成条目搬进备份目录，
        // mv 会以「cannot move a directory into itself」失败。提前拦下，比让发布在中途失败好定位。
        if (backupDir.equals(staticRoot) || backupDir.startsWith(staticRoot + "/")) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "备份目录不能配置在静态目录内部（" + backupDir + " 位于 " + staticRoot + " 之内）："
                            + "发布时备份目录会被一起搬进备份目录。默认配置（静态目录同级）即可，"
                            + "若担心被外部访问，请配置到 web 根之外的绝对路径");
        }
        String stamp = support.stamp();
        String stagedDir = parent + "/" + STAGING_PREFIX + stamp;

        Path localFile = null;
        try (SshSession session = sshSessionFactory.open(ctx.server())) {
            ensureLayout(ctx, session, parent, backupDir, staticRoot);
            boolean fromBackup = ctx.rollingBack()
                    && restoreFromBackup(ctx, session, backupDir, dirName, ctx.targetVersion(), stagedDir);
            if (!fromBackup) {
                // 常规路径：产物经「构建机 → 平台 → 目标机」两跳，解压到暂存目录。
                String remotePackage = prepareStaticPackage(ctx, artifactPath);
                String fileName = support.baseName(remotePackage);
                localFile = support.downloadArtifact(ctx.task(), release, remotePackage,
                        fileName, ctx.deployment());
                String remoteTmp = "/tmp/" + stamp + "-" + fileName;
                support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                        "上传静态产物到 " + remoteTmp);
                session.sftpUpload(localFile, remoteTmp);
                prepareStagingDir(ctx, session, stagedDir, remoteTmp);
            }
            switchDirectory(ctx, session, staticRoot, backupDir, dirName, stagedDir, stamp);
            // 静态发布只替换目录内容，不触碰 nginx 配置，因此按设计不执行 reload：
            // ① nginx 每次请求重新解析路径，目录切换后新内容即时生效，reload 不是必需动作；
            // ② nginx 未必由目标机上的这个二进制在跑——它可能是容器里的进程、甚至由另一台机器承担，
            //    用 `command -v nginx` 判别「该不该 reload」这件事本身就不成立；
            // ③ reload 失败会把一次「文件已成功替换」的发布判成失败，结论与磁盘状态相反，
            //    这正是最难排查的组合。真需要 reload 时应在 nginx 侧自己解决，不由发布链路代劳。
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "静态产物已就位于 " + staticRoot + "，按设计不执行 nginx reload（平台不修改 nginx 配置）");
        } finally {
            support.deleteQuietly(localFile);
        }
    }

    // ==================== 目录准备 ====================

    /**
     * 确保父目录与备份目录存在，并在跨分区时给出告警。
     *
     * <p><b>为什么要校验同分区</b>：备份与切换都依赖目录级 {@code mv} 的原子性，
     * 而 {@code mv} 跨分区会退化成「复制 + 删除」，静态目录动辄上百 MB，
     * 切换期间网站处于半新半旧状态。默认备份目录就在父目录下天然同分区，
     * 只有运维把 {@code static_backup_dir} 显式配到别的挂载点时才会触发。</p>
     *
     * <p>跨分区只告警不阻断：备份与切换本身的正确性不受影响，只是切换窗口变长。</p>
     *
     * @param ctx        发布上下文
     * @param session    已连接的 SSH 会话
     * @param parent     static_root 的父目录
     * @param backupDir  备份目录绝对路径
     * @param staticRoot 静态根目录，一并确保存在
     * @throws DeployException 目录创建失败时抛出
     */
    private void ensureLayout(DeployContext ctx, SshSession session, String parent, String backupDir,
                              String staticRoot) {
        // staticRoot 一并建出来：首次发布时它还不存在，而后续一切操作都要求它存在且 inode 不再变化。
        SshCommandResult mkdir = session.exec("mkdir -p " + parent + " " + backupDir + " " + staticRoot);
        if (!mkdir.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "创建静态发布目录失败（父目录 " + parent + "、备份目录 " + backupDir + "）："
                            + support.firstLine(mkdir.stderr()));
        }
        // stat 的 %d 是设备号：设备号相同即同一文件系统，目录内条目的 mv 才是瞬间 rename。
        // 比较对象必须是「搬运的两端」即 staticRoot 与 backupDir——早期拿 parent 比较，
        // 在 staticRoot 本身是挂载点时会得出错误结论。
        SshCommandResult sameFs = session.exec(
                "[ \"$(stat -c %d " + staticRoot + " 2>/dev/null)\" = \"$(stat -c %d " + backupDir
                        + " 2>/dev/null)\" ] && echo same || echo cross");
        if (sameFs.success() && "cross".equalsIgnoreCase(sameFs.stdout().strip())) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_PREPARE, TaskLogLevel.ERROR,
                    "警告：备份目录 " + backupDir + " 与静态目录 " + staticRoot + " 不在同一分区，"
                            + "内容搬迁会退化为跨分区复制，期间网站可能短暂不可访问，建议把备份目录改到同一挂载点");
        }
    }

    /**
     * 尝试用目标机本地备份直接还原出目标版本，用于回滚快路径。
     *
     * <p>命中条件：备份目录中存在名为 {@code {目录名}_bak_{时间戳}_{目标版本}} 的目录。
     * 未命中（备份已被清理、或平台记录的版本从未在这台机器上跑过）时返回 false，
     * 由调用方回落到正常的产物分发路径——回滚因此永远有退路，不会卡死在快路径上。</p>
     *
     * <p>用 {@code cp -a} 而不是 {@code mv}：备份必须保留，否则回滚一次后备份就没了，
     * 再回滚同一个版本就只能重新走构建机。</p>
     *
     * @param ctx        发布上下文
     * @param session    已连接的 SSH 会话
     * @param backupDir  备份目录
     * @param dirName    静态根目录名
     * @param version    目标版本号
     * @param stagedDir  暂存目录
     * @return true 表示已从本地备份还原，调用方无需再下载产物
     * @throws DeployException 还原命令执行失败时抛出
     */
    private boolean restoreFromBackup(DeployContext ctx, SshSession session, String backupDir,
                                      String dirName, String version, String stagedDir) {
        if (!StringUtils.hasText(version) || !SAFE_VERSION.matcher(version).matches()) {
            return false;
        }
        String cmd = "src=$(ls -d " + backupDir + "/" + dirName + "_bak_*_" + version
                + " 2>/dev/null | head -1); "
                + "if [ -n \"$src\" ]; then rm -rf " + stagedDir + "; cp -a \"$src\" " + stagedDir
                + " && echo \"$src\"; else echo none; fi";
        SshCommandResult result = session.exec(cmd);
        if (!result.success()) {
            // 快路径失败不算致命：回落到正常分发即可，但必须留痕说明为什么没走快路径。
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "本地备份探测失败，改用产物分发：" + support.firstLine(result.stderr()));
            return false;
        }
        String source = result.stdout().strip();
        if ("none".equals(source) || !StringUtils.hasText(source)) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "目标机没有版本 " + version + " 的本地备份，从构建机取产物");
            return false;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "命中本地备份，直接还原 " + version + "（" + source + "），不走构建机");
        return true;
    }

    /**
     * 把上传的归档解压到暂存目录，非归档文件则拷进去。
     *
     * <p>解压前先清空暂存目录：上一次失败可能留下残骸，不清会让新旧文件混在一起，
     * 而发布结论看起来是成功的。</p>
     *
     * @param ctx       发布上下文
     * @param session   已连接的 SSH 会话
     * @param stagedDir 暂存目录
     * @param archive   目标机 /tmp 下的归档文件绝对路径
     * @throws DeployException 目标机缺少解压工具、解压失败或产物格式不受支持时抛出
     */
    private void prepareStagingDir(DeployContext ctx, SshSession session, String stagedDir, String archive) {
        // zip 依赖 unzip，最小化安装的机器上可能没有，缺了会在这一步报 command not found，
        // 提前探测并给出可操作的提示，而不是让运维去猜解压输出为什么是空的。
        if (archive.toLowerCase().endsWith(".zip")) {
            SshCommandResult hasUnzip = session.exec("command -v unzip >/dev/null 2>&1 && echo yes || echo no");
            if (!hasUnzip.success() || !"yes".equalsIgnoreCase(hasUnzip.stdout().strip())) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "目标机未安装 unzip，无法解压 zip 格式的静态产物，请先在目标机安装 unzip 或改用 tar.gz 打包");
            }
        }
        String command = "rm -rf " + stagedDir + " && mkdir -p " + stagedDir + " && "
                + support.extractCommand(archive, stagedDir) + " && rm -f " + archive;
        SshCommandResult result = session.exec(command);
        if (!result.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "静态产物释放失败：" + support.firstLine(result.stderr()));
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "静态产物已释放到暂存目录 " + stagedDir);
        stripTopLevelDir(ctx, session, stagedDir);
        warnIfNested(ctx, session, stagedDir);
        ensureStagingNotEmpty(ctx, session, stagedDir);
    }

    /**
     * 校验暂存目录非空，为空则中止发布。
     *
     * <p><b>为什么必须拦</b>：内容替换语义下，空包就位的结果是<b>站点被清空</b>，
     * 而平台报「发布成功」——这是最难接受的一类结果：故障已经发生，日志里却没有一条 ERROR。
     * 走到这里的路径不止一条：上传了空压缩包、构建产物目录本身就是空的、
     * 解压工具返回 0 退出码却什么都没解出来。</p>
     *
     * <p><b>时机很关键</b>：放在切换之前。此时 static_root 还没被碰过，
     * 中止等于「本次什么都没发生」，站点仍完整提供旧内容；放到切换之后再发现就只剩回滚了。</p>
     *
     * <p>解析不出数量时按「非空」处理：这条校验是防御性的，
     * 不能因为一次探测失败就阻断一次本来正确的发布。</p>
     *
     * @param ctx       发布上下文
     * @param session   已连接的 SSH 会话
     * @param stagedDir 已解压完成的暂存目录
     * @throws DeployException 暂存目录为空时抛出
     */
    private void ensureStagingNotEmpty(DeployContext ctx, SshSession session, String stagedDir) {
        SshCommandResult probe = session.exec("ls -A " + stagedDir + " 2>/dev/null | wc -l");
        if (!probe.success()) {
            return;
        }
        int count;
        try {
            count = Integer.parseInt(probe.stdout().strip());
        } catch (NumberFormatException e) {
            return;
        }
        if (count > 0) {
            return;
        }
        throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                "静态产物解压后为空（暂存目录 " + stagedDir + "），已中止发布，站点未被改动："
                        + "请检查压缩包内容或构建产物目录是否为空");
    }

    /**
     * 压缩包多包了一层目录时，把那一层剥离掉（等价于 {@code tar --strip-components=1}）。
     *
     * <p><b>为什么由平台兜而不是只要求运维重打</b>：在产物目录的<b>父目录</b>上打包
     * 是最自然的动作（{@code zip -r aiops.zip aiops}），打出的包解开是 {@code aiops/index.html}。
     * 而 {@code static_root} 指向的就是 {@code aiops} 这一层，不剥壳发布后站点根会凭空多出一级，
     * 首页直接 404，发布日志却显示全部成功——属于最难排查的那类事故。
     * 与其要求所有人改变打包习惯，不如在平台侧把这一层吃掉。</p>
     *
     * <p><b>判据刻意收窄</b>：根下必须<b>恰好一个条目</b>、该条目<b>是目录</b>、
     * 且<b>该目录内存在 index.html</b>，三条全中才剥。只满足「单目录」就剥会误伤
     * 根下只有 {@code assets/} 这类包；要求内层有 index.html 后，
     * 平铺包（根下直接有 index.html）天然不满足第一条，不会被误剥。</p>
     *
     * @param ctx       发布上下文
     * @param session   已连接的 SSH 会话
     * @param stagedDir 已解压完成的暂存目录
     */
    private void stripTopLevelDir(DeployContext ctx, SshSession session, String stagedDir) {
        String tmp = stagedDir + "-strip";
        String command = "n=$(ls -A " + stagedDir + " 2>/dev/null | wc -l); "
                + "if [ \"$n\" != \"1\" ]; then echo skip; exit 0; fi; "
                + "one=$(ls -A " + stagedDir + " 2>/dev/null | head -1); "
                + "if [ ! -d \"" + stagedDir + "/$one\" ] || [ ! -e \"" + stagedDir + "/$one/index.html\" ]; "
                + "then echo skip; exit 0; fi; "
                + "mv \"" + stagedDir + "/$one\" \"" + tmp + "\" && rmdir " + stagedDir
                + " && mv \"" + tmp + "\" " + stagedDir + " && echo \"stripped|$one\"";
        SshCommandResult result = session.exec(command);
        String out = result.success() ? result.stdout().strip() : "";
        if (out.startsWith("stripped|")) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "压缩包外层目录 " + out.substring("stripped|".length()).strip()
                            + " 已自动剥离，站点内容已提升到静态根（等价于 tar --strip-components=1）");
            return;
        }
        if (!result.success()) {
            // 剥离失败不阻断：按原样发布，紧接着的 warnIfNested 会把异常形态告警出来。
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                    "剥离压缩包外层目录失败，按原结构发布：" + support.firstLine(result.stderr()));
        }
    }

    /**
     * 检测产物是否多包了一层目录，命中时只告警不阻断。
     *
     * <p><b>为什么需要这一步</b>：平台只接受压缩包上传（不做文件夹上传），
     * 打包方式由运维自己掌握，而最常见的失误是在产物目录的<b>父目录</b>上打包，
     * 打出的包解压后是 {@code aiops/index.html} 而不是 {@code index.html}。
     * 由于 {@code static_root} 指向的就是 {@code aiops} 这一层，
     * 这种包发布后网站根目录会凭空多出一级，首页直接 404，
     * 而发布日志此时显示的是「全部成功」——属于最难排查的那类事故。</p>
     *
     * <p><b>判定条件刻意收得很窄</b>：根下必须有 index.html 才算正常；
     * 没有 index.html 且<b>只有一个条目且该条目是目录</b>时才告警。
     * 少一个条件都会误报（例如某些框架的根目录确实只有若干子目录），
     * 而误报会让运维逐渐忽略这条日志。只告警不阻断：
     * 少数站点确实以其他入口文件命名，平台不该替运维做这个决定。</p>
     *
     * @param ctx       发布上下文
     * @param session   已连接的 SSH 会话
     * @param stagedDir 已解压完成的暂存目录
     */
    private void warnIfNested(DeployContext ctx, SshSession session, String stagedDir) {
        String command = "if [ -e " + stagedDir + "/index.html ]; then echo ok; else "
                + "n=$(ls -A " + stagedDir + " 2>/dev/null | wc -l); "
                + "if [ \"$n\" = \"1\" ]; then one=$(ls -A " + stagedDir + " 2>/dev/null | head -1); "
                + "if [ -d \"" + stagedDir + "/$one\" ]; then echo \"nested|$one\"; else echo ok; fi; "
                + "else echo ok; fi; fi";
        SshCommandResult probe = session.exec(command);
        String out = probe.success() ? probe.stdout().strip() : "";
        if (!out.startsWith("nested|")) {
            return;
        }
        String nested = out.substring("nested|".length()).strip();
        if (!StringUtils.hasText(nested)) {
            return;
        }
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                "警告：解压后暂存目录下只有一个子目录 " + nested + "，且根下没有 index.html，"
                        + "压缩包多半多包了一层；请进入产物目录后再打包"
                        + "（cd dist && zip -r app.zip .），使解压后直接是 index.html");
    }


    // ==================== 备份与切换 ====================

    /**
     * 备份当前目录下的内容，并把暂存目录的内容就地填充进来。
     *
     * <p><b>全过程绝不 rename / 删除 static_root 本身</b>：两次搬运都由 {@link #MOVE_CONTENTS}
     * 完成，操作的始终是目录<b>内</b>的条目。原因见类注释——Docker bind mount 绑定的是 inode，
     * 目录被换掉一次，容器里的挂载就永久失效，且只能靠重建容器恢复。</p>
     *
     * <p><b>为什么整体搬走旧内容而不做增量覆盖</b>：整体搬走才能保证新版本是<b>精确替换</b>——
     * 旧版本独有的文件（改名过的 js/css）会随备份一起离开，不会残留在站点里。
     * 增量覆盖做不到这一点，残留文件会让「发布成功」与「站点内容正确」脱钩。</p>
     *
     * <p>备份名格式 {@code {目录名}_bak_{时间戳}_{被替换的版本号}}，
     * 与 AGENTS.md §3.1 JAR 的 {@code {jar名}_bak_{时间戳}_{版本号}.jar} 同构。
     * 版本号取 {@link DeployContext#currentVersion()}（磁盘上正被换掉的那个版本），
     * 为空时退化为不带版本段——不猜版本。</p>
     *
     * @param ctx        发布上下文
     * @param session    已连接的 SSH 会话
     * @param staticRoot 静态根目录（nginx root 指向的目录），inode 保持不变
     * @param backupDir  备份目录
     * @param dirName    静态根目录名
     * @param stagedDir  已就绪的暂存目录
     * @param stamp      本次发布的时间戳
     * @throws DeployException 备份或切换失败时抛出
     */
    private void switchDirectory(DeployContext ctx, SshSession session, String staticRoot, String backupDir,
                                 String dirName, String stagedDir, String stamp) {
        String currentVersion = ctx.currentVersion();
        boolean hasVersion = StringUtils.hasText(currentVersion)
                && SAFE_VERSION.matcher(currentVersion).matches();
        String versionPart = hasVersion ? "_" + currentVersion : "";
        // 备份与防撞名在同一条命令里完成：并发发布到同一台机器时不会抢同一个备份名。
        // ls -A 判断目录是否已有内容——首次发布时目录为空，跳过备份而不是建一个空备份，
        // 空备份会让回滚时「命中备份却还原出一个空站点」。
        String backupCmd = MOVE_CONTENTS
                + "mkdir -p " + staticRoot + " || exit 1; "
                + "name=\"" + dirName + "_bak_" + stamp + versionPart + "\"; "
                + "dst=\"" + backupDir + "/${name}\"; n=2; "
                + "while [ -e \"$dst\" ]; do dst=\"" + backupDir + "/${name}_${n}\"; n=$((n+1)); done; "
                + "if [ -n \"$(ls -A " + staticRoot + " 2>/dev/null)\" ]; then "
                + "if _ld_move " + staticRoot + " \"$dst\"; then echo \"$dst\"; else exit 1; fi; "
                + "else echo none; fi";
        // 记录切换前的 inode：bind mount 失效类故障的排查全靠它，写进日志后不用再登机器 stat。
        String inodeBefore = inodeOf(session, staticRoot);
        SshCommandResult backup = session.exec(backupCmd);
        if (!backup.success()) {
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "备份当前静态目录内容失败：" + support.firstLine(backup.stderr()));
        }
        String backupPath = backup.stdout().strip();
        boolean hasBackup = StringUtils.hasText(backupPath) && !"none".equals(backupPath);
        if (hasBackup) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "已备份静态目录现有内容到 " + backupPath + "（目录本身保留，inode 不变）");
        } else {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "静态目录为空，本次为首次发布，无需备份");
        }
        // 就位：把暂存目录的内容搬进 staticRoot。此刻到搬运完成之间站点目录是空的，
        // 窗口极短且之后自愈；若这一步失败，必须立刻把备份内容搬回去，否则站点会长时间空着。
        SshCommandResult move = session.exec(MOVE_CONTENTS + "_ld_move " + stagedDir + " " + staticRoot);
        if (!move.success()) {
            if (hasBackup) {
                session.exec(MOVE_CONTENTS + "_ld_move " + backupPath + " " + staticRoot
                        + " >/dev/null 2>&1 || true");
                support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                        "新内容就位失败，已把备份还原回 " + staticRoot + "，站点应保持发布前状态");
            }
            throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                    "静态目录内容替换失败：" + support.firstLine(move.stderr()));
        }
        // 暂存目录此时应当已空。用 rmdir 而不是 rm -rf：非空则 rmdir 失败，
        // 说明有条目没搬干净，宁可把它留在原地让运维看见，也不要静默删掉可能的残留文件。
        SshCommandResult cleanup = session.exec("rmdir " + stagedDir + " 2>/dev/null && echo ok || echo left");
        if (!"ok".equalsIgnoreCase(cleanup.stdout().strip())) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                    "警告：暂存目录 " + stagedDir + " 仍有残留未被搬入静态根，请手工确认站点内容完整性");
        }
        String inodeAfter = inodeOf(session, staticRoot);
        support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                "静态目录内容已替换为版本 " + ctx.targetVersion() + "（inode " + inodeBefore
                        + " → " + inodeAfter + "，未变则容器内 bind mount 不受影响）");
        if (StringUtils.hasText(inodeBefore) && StringUtils.hasText(inodeAfter)
                && !inodeBefore.equals(inodeAfter)) {
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.ERROR,
                    "警告：静态目录 inode 由 " + inodeBefore + " 变为 " + inodeAfter
                            + "，若该目录被 Docker bind mount 进容器，容器内的挂载已失效"
                            + "（表现为容器内目录为空、全站 403），需要重建容器才能恢复");
        }
    }

    /**
     * 读取目录的 inode 号。
     *
     * <p>只用于日志：<b>内容替换的正确性由 {@link #MOVE_CONTENTS} 的实现保证，不靠这个值校验</b>。
     * 把它写进任务日志，是为了让「容器内看到空目录」这类 bind mount 失效故障
     * 能直接在日志里定性——运维不必再登机器对比宿主机与容器的 inode。</p>
     *
     * @param session 已连接的 SSH 会话
     * @param dir     目录绝对路径
     * @return inode 号字符串，取不到时返回空串（调用方按「未知」处理，不阻断发布）
     */
    private String inodeOf(SshSession session, String dir) {
        SshCommandResult result = session.exec("stat -c %i " + dir + " 2>/dev/null");
        return result.success() ? result.stdout().strip() : "";
    }

    // ==================== 产物打包 ====================

    /**
     * 在构建机上把静态产物准备成单个可传输文件。
     *
     * <p>已经是归档文件（{@code .zip} / {@code .tar.gz} / {@code .tgz}）时原样返回：
     * 上传的静态包通常已是归档，多打一层 tar 只会白白增加一次压缩与传输。
     * 目录型产物才需要在构建机上打包——SFTP 逐文件传输的握手开销在前端 dist
     * 这种成百上千个小文件场景下不可接受。</p>
     *
     * @param ctx          发布上下文
     * @param artifactPath 构建机上的产物路径，可能是目录也可能是文件
     * @return 构建机上待下载的绝对路径
     * @throws DeployException 构建机缺失或打包失败时抛出
     */
    private String prepareStaticPackage(DeployContext ctx, String artifactPath) {
        if (support.isArchive(artifactPath)) {
            return artifactPath;
        }
        ReleaseRecord release = ctx.release();
        if (!StringUtils.hasText(release.getBuildServerId())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR,
                    "版本 " + ctx.targetVersion() + " 未记录构建机，无法打包静态产物");
        }
        Server buildServer = serverMapper.selectById(release.getBuildServerId());
        if (buildServer == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "版本记录的构建机不存在");
        }
        try (SshSession buildSession = sshSessionFactory.open(buildServer)) {
            String packagePath = "/tmp/" + PACKAGE_PREFIX + ctx.deployment().getId() + ".tar.gz";
            String command = "test -d " + artifactPath
                    + " && tar -czf " + packagePath + " -C " + artifactPath + " ."
                    + " || tar -czf " + packagePath + " -C $(dirname " + artifactPath + ") $(basename " + artifactPath + ")";
            SshCommandResult result = buildSession.exec(command);
            if (!result.success()) {
                throw new DeployException(ResponseCode.REMOTE_EXECUTION_ERROR,
                        "静态产物打包失败：" + support.firstLine(result.stderr()));
            }
            support.appendLog(ctx.task(), DeploySupport.STAGE_DELIVER, TaskLogLevel.INFO,
                    "静态产物已打包为 " + packagePath);
            return packagePath;
        }
    }
}
