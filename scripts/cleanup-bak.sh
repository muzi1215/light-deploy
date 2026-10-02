#!/bin/sh
#
# cleanup-bak.sh —— 清理目标机上的本机部署备份
#
# 适用两类备份（二者命名规则同构，都是「名字_bak_时间戳_版本号」）：
#   JAR     → {工作目录}/exec/bak/{jar名}_bak_{时间戳}_{版本号}.jar   （文件）
#   STATIC  → {备份目录}/{目录名}_bak_{时间戳}_{版本号}/              （目录）
#
# 设计前提（务必先读，否则可能误删）：
#   1. 本机备份是「应急副本」，不是版本仓库。平台回滚 JAR 时 100% 从构建机重新取产物，
#      STATIC 虽会先查本机备份（回滚快路径），但查不到也只是回落到构建机。
#      因此清理本机备份不会让回滚失败，只会让 STATIC 回滚从「秒级」退化到「分钟级」。
#   2. 它的保留策略刻意与 project.retain_release_count 解耦：那个字段管的是版本号维度，
#      本机备份是「某次发布时被换下去的包」，同一版本可能重复出现，套上去会错位；
#      更重要的是两者若联动清理，会同时断掉「构建机产物」与「本机兜底」两条恢复路径。
#   3. 因此这里用两个独立维度封顶：份数上限 + 天数上限，任一超限即清最旧的。
#
# 安全约束：
#   - 默认 dry-run，必须显式 --apply 才会真的删。
#   - 只处理 -d 指定目录下、名字匹配 '*_bak_[0-9]*' 的一级条目，绝不递归进子目录。
#   - 最近 KEEP_RECENT 份永不删除（默认 1），它是发布失败时手工回滚的唯一救命稻草。
#   - 拒绝在受保护的系统目录上运行。
#
# 用法：
#   ./cleanup-bak.sh -d /lx/jarapps/apps/auth-7888/exec/bak            # 预演，不删
#   ./cleanup-bak.sh -d /lx/jarapps/apps/auth-7888/exec/bak --apply    # 真删
#   ./cleanup-bak.sh -d /usr/share/nginx/html/static-bak -n 20 -t 60 --apply
#
# 由平台远程调用（脚本不在目标机留文件）：
#   ssh target 'bash -s -- -d /path/exec/bak -n 10 -t 30 --apply' < cleanup-bak.sh
#
# 输出：人类可读摘要 + 一行机器可读结果
#   REMOVED=<n> KEPT=<n> FREED_KB=<kb>
#
# @author lx
# @since 2026-09-28
#

set -u

# ---------- 默认策略 ----------
DRY_RUN=1
DIR=""
KEEP_COUNT=10      # 份数上限：超过则清最旧的
KEEP_DAYS=30       # 天数上限：mtime 早于该天数的清理
KEEP_RECENT=1      # 硬保护：最近 N 份永不删除

# 受保护目录：绝不接受这些作为清理根，防止参数写错时扫到系统目录
PROTECTED="/ /bin /boot /dev /etc /home /lib /lib64 /opt /proc /root /run /sbin /srv /sys /tmp /usr /var /var/tmp"

usage() {
    cat <<'EOF'
cleanup-bak.sh —— 清理目标机本机部署备份（exec/bak、static-bak）

必填：
  -d, --dir <路径>        备份目录绝对路径（必须已存在）

策略：
  -n, --keep-count <n>    份数上限，默认 10
  -t, --keep-days <n>     天数上限（按 mtime），默认 30
  -k, --keep-recent <n>   硬保护最近 n 份不被删除，默认 1（建议不要设 0）

动作：
      --apply             真正执行删除；不带此参数则为 dry-run
  -h, --help              显示本帮助

示例：
  ./cleanup-bak.sh -d /lx/jarapps/apps/auth-7888/exec/bak
  ./cleanup-bak.sh -d /usr/share/nginx/html/static-bak -n 20 -t 60 --apply
EOF
}

die() {
    echo "错误：$1" >&2
    exit 2
}

# 正整数校验，挡住 "-1"（会让 head -n -1 语义翻转）与空值
require_uint() {
    case "$1" in
        ''|*[!0-9]*) die "参数 $2 需要非负整数，收到：$1" ;;
    esac
}

while [ $# -gt 0 ]; do
    case "$1" in
        -d|--dir)         [ $# -ge 2 ] || die "-d 缺少取值"; DIR="$2"; shift 2 ;;
        -n|--keep-count)  [ $# -ge 2 ] || die "-n 缺少取值"; KEEP_COUNT="$2"; shift 2 ;;
        -t|--keep-days)   [ $# -ge 2 ] || die "-t 缺少取值"; KEEP_DAYS="$2"; shift 2 ;;
        -k|--keep-recent) [ $# -ge 2 ] || die "-k 缺少取值"; KEEP_RECENT="$2"; shift 2 ;;
        --apply)          DRY_RUN=0; shift ;;
        -h|--help)        usage; exit 0 ;;
        *)                die "未知参数：$1（用 -h 查看用法）" ;;
    esac
done

[ -n "$DIR" ] || { usage; exit 2; }
require_uint "$KEEP_COUNT" "-n"
require_uint "$KEEP_DAYS" "-t"
require_uint "$KEEP_RECENT" "-k"

# ---------- 路径安全校验 ----------
case "$DIR" in
    /*) : ;;
    *)  die "备份目录必须是绝对路径：$DIR" ;;
esac
NORM_DIR=$(cd "$DIR" 2>/dev/null && pwd -P) || die "备份目录不可访问：$DIR"
# 同时比较原始入参与规范化结果：macOS 的 /tmp 会被 pwd -P 解析成 /private/tmp，
# 只比规范化值会让保护清单形同虚设；带上尾斜杠场景一起比，堵住 "-d /tmp/" 这类写法。
case "$DIR" in
    */) DIR_TRIM="${DIR%/}" ;;
    *)  DIR_TRIM="$DIR" ;;
esac
for p in $PROTECTED; do
    NORM_P=$(cd "$p" 2>/dev/null && pwd -P) || NORM_P="$p"
    if [ "$DIR_TRIM" = "$p" ] || [ "$NORM_DIR" = "$p" ] || [ "$NORM_DIR" = "$NORM_P" ]; then
        die "拒绝在受保护目录 $p 上执行清理（疑似参数写错）：$DIR"
    fi
done
[ -d "$NORM_DIR" ] || die "备份目录不存在或不是目录：$NORM_DIR"

TMP_DIR=$(mktemp -d 2>/dev/null) || die "无法创建临时目录"
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

ALL="$TMP_DIR/all"
OLD="$TMP_DIR/old"
EXCESS="$TMP_DIR/excess"
CANDS="$TMP_DIR/cands"
PROTECT="$TMP_DIR/protect"

# ---------- 收集候选 ----------
# 命名借记 '*_bak_[0-9]*'：只认平台自己产出的备份，同目录下的其他文件一律不碰。
# -maxdepth 1 -mindepth 1 保证不递归：备份目录里不会嵌套需要判断的子结构。
# LC_ALL=C sort 让「名字字典序」等于「时间序」——备份名里的 14 位时间戳是零填充的，
# 这是排序依据。不用 mtime 排序是因为 cp -p 保留了源文件的 mtime，跨次复制后不可靠。
find "$NORM_DIR" -maxdepth 1 -mindepth 1 -name '*_bak_[0-9]*' -print 2>/dev/null | LC_ALL=C sort >"$ALL"

TOTAL=$(wc -l <"$ALL" | tr -d ' ')
[ "$TOTAL" -gt 0 ] || {
    echo "备份目录没有可清理的条目：$NORM_DIR"
    echo "REMOVED=0 KEPT=0 FREED_KB=0"
    exit 0
}

# 维度一：超过天数上限的（find -mtime +N 表示严格早于 (N+1)*24 小时）
find "$NORM_DIR" -maxdepth 1 -mindepth 1 -name '*_bak_[0-9]*' -mtime "+$KEEP_DAYS" \
    -print 2>/dev/null | LC_ALL=C sort >"$OLD"

# 维度二：超出份数上限的。排序后最旧的在头部，取前 (TOTAL-KEEP_COUNT) 行。
: >"$EXCESS"
if [ "$TOTAL" -gt "$KEEP_COUNT" ]; then
    head -n $((TOTAL - KEEP_COUNT)) "$ALL" >"$EXCESS"
fi

# 取并集去重
cat "$OLD" "$EXCESS" | LC_ALL=C sort -u >"$CANDS"

# ---------- 硬保护最近 N 份 ----------
tail -n "$KEEP_RECENT" "$ALL" >"$PROTECT" 2>/dev/null || : >"$PROTECT"

# ---------- 逐个判定 ----------
# du -sk 对文件与目录都返回占用 KB，避免区分 JAR 单文件与 STATIC 目录备份。
size_kb() {
    du -sk "$1" 2>/dev/null | awk '{print $1}'
}

is_protected() {
    grep -qxF "$1" "$PROTECT" 2>/dev/null
}

REMOVED=0
SKIPPED_PROTECT=0
FREED_KB=0

while IFS= read -r item; do
    [ -n "$item" ] || continue
    [ -e "$item" ] || continue
    if is_protected "$item"; then
        SKIPPED_PROTECT=$((SKIPPED_PROTECT + 1))
        continue
    fi
    kb=$(size_kb "$item")
    case "$kb" in
        ''|*[!0-9]*) kb=0 ;;
    esac
    if [ "$DRY_RUN" -eq 1 ]; then
        echo "[DRY-RUN] 将删除 ${item}（${kb} KB）"
    else
        if rm -rf -- "$item"; then
            echo "[CLEAN]   已删除 ${item}（${kb} KB）"
        else
            echo "[WARN]    删除失败，跳过：${item}" >&2
            continue
        fi
    fi
    REMOVED=$((REMOVED + 1))
    FREED_KB=$((FREED_KB + kb))
done <"$CANDS"

KEPT=$((TOTAL - REMOVED))

if [ "$DRY_RUN" -eq 1 ]; then
    echo "---- dry-run 结束，未做任何改动；加 --apply 才会真正删除 ----"
fi
if [ "$SKIPPED_PROTECT" -gt 0 ]; then
    echo "保留最近 $KEEP_RECENT 份中的 $SKIPPED_PROTECT 个未参与清理（发布失败时的手工回滚备份）"
fi
echo "备份目录：${NORM_DIR}（原有 $TOTAL 个，清理后保留 $KEPT 个）"
echo "策略：份数上限 $KEEP_COUNT / 天数上限 $KEEP_DAYS / 硬保护最近 $KEEP_RECENT 份"
echo "REMOVED=$REMOVED KEPT=$KEPT FREED_KB=$FREED_KB"
