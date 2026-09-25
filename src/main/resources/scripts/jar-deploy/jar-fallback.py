#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JAR 回滚脚本（light-deploy 平台内置，供运维在目标机手工执行）。

平台自身的回滚走「重新发布 old_version」路径，不调用本脚本；
这里保留一份可手工运行的版本，应急时可以从 exec/bak 里挑一个历史包恢复。

用法：
    cd {工作目录}/exec && python3 fallback.py              # 回滚到最近一次备份
    cd {工作目录}/exec && python3 fallback.py --steps 2    # 回滚到倒数第二次备份

行为：
    1. 从 exec/deploy.conf 读取服务名与工作目录；
    2. 按备份文件名里的时间戳倒序列出 exec/bak 下的备份，取第 steps 个；
    3. 打印「当前版本 → 回退到版本」：当前版本取自工作目录的 VERSION，
       目标版本直接从备份文件名里读出；
    4. 把备份文件名还原成正式 jar 名（截掉 _bak_时间戳_版本号 段）后覆盖；
    5. 刷新 VERSION 与 deploy.conf 的 version 行 → systemctl restart → 校验 is-active。

版本号为什么在文件名里：
    备份名里的时间戳是「执行替换的时刻」，指向的是替换者而不是包里的版本——
    auth-tool_bak_20260925203000v1.jar 装的是「20:30 那次发布换下去的那个包」，
    只看时间戳无法知道它是 v4 还是 v3。因此平台与 update.py 备份时会把被替换包的
    版本号直接内嵌进名字：auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar。
    ls 一眼就能看出每个备份是哪个版本，不必再 cat 伴随文件或算 SHA256。

    历史上出现过两种形态，必须都能识别：本次改动前产生的备份名形如
    auth-tool_bak_20260925203000v1.jar（末尾 v1 是防撞名序号，不是版本号），
    名字里没有版本信息，此时只报「版本未登记」，不猜。

退出码：0 成功；1 失败，原因写入 stderr。

兼容性：目标机只要求 Python 3.5 及以上，脚本内禁止使用 3.7 才有的参数
（例如 subprocess 的 capture_output / text），否则会在老机器上抛 TypeError。
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

# 脚本使用的最低 Python 版本：subprocess.run 从 3.5 开始提供。
MIN_PYTHON = (3, 5)

# 工作目录下的版本标识文件名，与平台发布流程写入的同名文件格式一致（key=value 多行）。
VERSION_FILE = "VERSION"

# 服务信息文件名，位于 exec 目录下。
DEPLOY_CONF = "deploy.conf"


def check_python_version():
    """版本过低时给出可执行的提示，而不是抛出晦涩的 TypeError。"""
    version = sys.version_info
    if version < MIN_PYTHON:
        print("需要 Python %d.%d 及以上，当前为 %d.%d.%d，请先升级目标机的 python3"
              % (MIN_PYTHON[0], MIN_PYTHON[1], version[0], version[1], version[2]),
              file=sys.stderr)
        return False
    return True


def run(cmd):
    """执行 shell 命令并返回 (退出码, stdout, stderr)。

    显式使用 stdout/stderr + universal_newlines 而不是 capture_output + text：
    后者是 Python 3.7 才加入 Popen 的关键字，3.6 上会直接抛
    "unexpected keyword argument 'capture_output'"。
    """
    result = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True)
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def read_conf(conf_path):
    if not os.path.exists(conf_path):
        raise RuntimeError("未找到 %s，请确认工作目录已初始化" % conf_path)
    conf = {}
    with open(conf_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if "=" in line:
                key, value = line.split("=", 1)
                conf[key.strip()] = value.strip()
    for key in ("service_name", "work_dir"):
        if not conf.get(key):
            raise RuntimeError("deploy.conf 缺少 %s 配置" % key)
    return conf


def read_version_file(path):
    """从 VERSION 标识文件里读 version= 行；读不到返回空串。

    读不到时不抛异常：工作目录可能还没被平台登记过版本（改造前发布的工作目录），
    这不影响回退本身，只是无法报出「此刻跑着的」是哪个版本。
    """
    if not path or not os.path.exists(path):
        return ""
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("version="):
                    return line.split("=", 1)[1].strip()
    except (IOError, OSError):
        pass
    return ""


def write_version_file(path, version):
    """回退完成后重写 VERSION，使标识与磁盘上的 jar 保持一致。"""
    content = "version=%s\ncommit=unknown\nruntime=JAR\nrolled_back_at=%s\n" % (
        version, time.strftime("%Y-%m-%d %H:%M:%S"))
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def refresh_conf_version(conf_path, version):
    """重写 deploy.conf 的 version 行，其余内容原样保留。"""
    if not os.path.exists(conf_path):
        return
    kept = []
    with open(conf_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line.strip().startswith("version="):
                kept.append(line)
    if kept and not kept[-1].endswith("\n"):
        kept[-1] = kept[-1] + "\n"
    kept.append("version=%s\n" % version)
    with open(conf_path, "w", encoding="utf-8") as f:
        f.write("".join(kept))


# 备份名的固定段：{jar名}_bak_ + 14 位时间戳。时间戳定长，因此字典序即时间序。
BACKUP_TS_RE = re.compile(r"_bak_(\d{14})")


def version_from_name(backup_name):
    """从备份文件名里读出内嵌的版本号，读不到返回空串。

    auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar    -> auth-7888-v6-c9bb25d3
    auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3_2.jar  -> auth-7888-v6-c9bb25d3
    auth-tool_bak_20260925203000v1.jar                        -> ""（改造前的旧格式）

    只认「_bak_ + 14 位时间戳 + _」之后、末尾 .jar 之前那一段，并剥掉可能的防撞名
    序号；旧格式结尾那个 v1 是防撞名序号而不是版本号，因此不会被误读成版本。
    """
    matched = re.match(r"^.*?_bak_\d{14}_(.+?)(?:_\d+)?\.jar$", backup_name)
    return matched.group(1) if matched else ""


def backup_order(path):
    """备份排序键：优先取文件名里的时间戳，取不到再退回 mtime。

    按文件名而不按 mtime 排序，是因为 cp -p 会把源 jar 的 mtime 一并带过来，
    ls -l 显示的其实是「这个包当初被发布的时刻」而非备份时刻。文件名里的时间戳
    才是备份动作发生的时刻，与 --steps「第几个备份」的语义严格一致。
    """
    matched = BACKUP_TS_RE.search(os.path.basename(path))
    if matched:
        return matched.group(1)
    return time.strftime("%Y%m%d%H%M%S", time.localtime(os.path.getmtime(path)))


def restore_name(backup_name):
    """把备份名还原成正式 jar 名。

    auth-tool_bak_20260104120000v1.jar                     -> auth-tool.jar
    auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar -> auth-tool.jar

    从「_bak_ + 14 位时间戳」处截断到结尾 .jar，新旧两种命名一并覆盖，
    因此改造前产生的备份仍然可以被 --steps 退到。
    """
    return re.sub(r"_bak_\d{14}.*\.jar$", ".jar", backup_name)


def restart(service_name):
    code, _, err = run("systemctl restart %s" % service_name)
    if code != 0:
        raise RuntimeError("systemctl restart 失败: %s" % err)
    code, out, _ = run("systemctl is-active %s" % service_name)
    if code != 0 or out.strip() != "active":
        raise RuntimeError("服务未处于 active 状态，请检查 journalctl -u %s" % service_name)
    print("服务已重启: %s" % service_name)


def main():
    if not check_python_version():
        return 1
    parser = argparse.ArgumentParser(description="从 exec/bak 恢复历史 jar")
    parser.add_argument("--steps", type=int, default=1, help="回滚到第几个备份，1 为最近一次")
    parser.add_argument("--conf", default="", help="deploy.conf 路径，默认取脚本同目录")
    args = parser.parse_args()

    exec_dir = os.path.dirname(os.path.abspath(__file__))
    conf_path = args.conf or os.path.join(exec_dir, DEPLOY_CONF)
    try:
        conf = read_conf(conf_path)
        work_dir = conf["work_dir"].rstrip("/")
        bak_dir = os.path.join(exec_dir, "bak")
        if not os.path.isdir(bak_dir):
            raise RuntimeError("备份目录不存在: %s" % bak_dir)
        backups = [os.path.join(bak_dir, n) for n in os.listdir(bak_dir) if n.endswith(".jar")]
        if not backups:
            raise RuntimeError("备份目录中没有可用的历史 jar")
        backups.sort(key=backup_order, reverse=True)
        if args.steps > len(backups):
            raise RuntimeError("只有 %d 个备份，无法回滚 %d 步" % (len(backups), args.steps))
        chosen = backups[args.steps - 1]
        target = os.path.join(work_dir, restore_name(os.path.basename(chosen)))
        version_file = os.path.join(work_dir, VERSION_FILE)

        # 版本对照：当前版本读工作目录的 VERSION，目标版本直接从备份文件名里取。
        # 名字里没有版本段的是改造前产生的备份，此时只报未登记，绝不猜。
        current_version = read_version_file(version_file) or (conf.get("version") or "").strip()
        target_version = version_from_name(os.path.basename(chosen))
        print("将执行回滚: %s -> %s" % (current_version or "未登记", target_version or "未登记"))
        if not target_version:
            print("提示: 该备份名里没有版本段（改造前产生的备份），无法确认平台版本；"
                  "可用 sha256sum 与平台 release_record.sha256 比对")

        shutil.copy2(chosen, target)
        print("已恢复: %s -> %s" % (chosen, target))
        # 与平台发布流程保持同一时序：jar 先上盘、再登记版本、最后重启。
        # 重启失败时磁盘已是回退后的版本，标识必须与磁盘一致而不是停留在旧值。
        write_version_file(version_file, target_version)
        refresh_conf_version(conf_path, target_version)
        restart(conf["service_name"])
        print("回滚完成: %s（版本 %s）" % (os.path.basename(target), target_version or "未登记"))
        return 0
    except Exception as e:
        print("回滚失败: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
