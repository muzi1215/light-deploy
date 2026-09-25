#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JAR 更新脚本（light-deploy 平台内置，供运维在目标机手工执行）。

平台自身的发版流程不依赖本脚本（由平台直接执行等价命令），
但在机器上保留一份可手工运行的脚本，便于断网、平台不可用等场景下应急更新。

用法：
    1. 把新 jar 放到工作目录的 exec/ 下；
    2. cd {工作目录}/exec && python3 update.py

行为：
    1. 从 exec/deploy.conf 读取服务名、jar 名与工作目录；
    2. exec/ 下只有一个 jar 时自动选中，多个时必须用 --jar-file 指定；
    3. 把工作目录里的同名 jar 备份到 exec/bak/{jar名}_bak_{时间戳}_{版本号}.jar，
       版本号取自工作目录的 VERSION，使备份名自带版本；
    4. 覆盖工作目录的 jar → 写 VERSION → 刷新 deploy.conf 的 version 行 →
       systemctl restart → 校验 is-active。

版本号来源（按优先级）：
    --version 参数 > exec/VERSION > 空。
    第二项是为「把平台构建机上的产物连同它的 VERSION 一起拷过来」准备的：
    构建机产物目录里就有同名 VERSION，直接拷进 exec/ 即可，不用改名。
    都取不到时记空而不是猜——记错版本比不记版本更危险。
    被替换掉的旧版本始终能从备份文件名里读回。

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
    """读取 deploy.conf，返回 service_name / jar_name / work_dir。"""
    if not os.path.exists(conf_path):
        raise RuntimeError("未找到 %s，请确认工作目录已初始化" % conf_path)
    conf = {}
    with open(conf_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if "=" in line:
                key, value = line.split("=", 1)
                conf[key.strip()] = value.strip()
    for key in ("service_name", "jar_name", "work_dir"):
        if not conf.get(key):
            raise RuntimeError("deploy.conf 缺少 %s 配置" % key)
    return conf


def pick_jar(exec_dir, jar_file):
    """确定本次要更新的 jar 文件名：显式指定优先，否则取 exec/ 下唯一的 jar。"""
    jars = [n for n in os.listdir(exec_dir) if n.endswith(".jar")]
    if jar_file:
        if jar_file not in jars:
            raise RuntimeError("exec 目录下不存在 %s" % jar_file)
        return jar_file
    if not jars:
        raise RuntimeError("exec 目录下没有待更新的 jar 文件")
    if len(jars) > 1:
        raise RuntimeError("exec 目录下有多个 jar，请用 --jar-file 指定：%s" % "、".join(jars))
    return jars[0]


def read_version_file(path):
    """从 VERSION 标识文件里读 version= 行；文件不存在或没有该行时返回空串。"""
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
    """写入 VERSION 标识，格式与平台发布流程写入的一致（key=value 多行）。

    缺 commit 是手工更新场景的常态（运维手上未必有提交号），写 unknown 而不是留空，
    避免读的人分不清「没有这一项」和「这一项为空」。
    """
    content = "version=%s\ncommit=unknown\nruntime=JAR\ndeployed_at=%s\n" % (
        version, time.strftime("%Y-%m-%d %H:%M:%S"))
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def refresh_conf_version(conf_path, version):
    """重写 deploy.conf 的 version 行，其余内容原样保留。

    先剔除已有 version 行再追加，避免重复行让后续读取方拿到旧值。
    文件不存在时直接返回：deploy.conf 由 init 脚本生成，这里不凭空造一份去猜服务名。
    """
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


def safe_version(version):
    """把版本号收敛成可安全用作文件名的形态，与平台侧 shell 的 tr 口径一致。

    版本号由平台生成（{项目编码}-v{序号}-{提交短码}），本不该含特殊字符；
    这里防的是 VERSION 被手工改脏后带出 / 之类字符，把备份写到别的目录去。
    """
    return re.sub(r"[^A-Za-z0-9._-]", "", version or "")


def backup(current_jar, bak_dir, version_file):
    """把现有 jar 备份到 bak 目录，备份名内嵌版本号。

    命名 {jar名}_bak_{时间戳}_{版本号}.jar，例如
    auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar。
    备份名里的时间戳是「执行替换的时刻」，指向的是替换者而不是包里的版本，
    所以版本号必须内嵌进名字：只留时间戳的话，事后 ls 一眼看不出这个包属于哪个
    平台版本，只能靠 SHA256 反查或时间线推断。

    版本段取自工作目录的 VERSION（磁盘事实）；取不到就省略该段而不是猜——
    记错版本比不记版本更危险。同秒同版本重名时追加 _2、_3，纯防撞名，与版本无关。
    """
    if not os.path.exists(current_jar):
        print("工作目录尚无同名 jar，跳过备份（本次视为首次投放）")
        return None
    ensure_dir(bak_dir)
    version = read_version_file(version_file)
    base = os.path.basename(current_jar)
    name, ext = os.path.splitext(base)
    suffix = "_%s" % safe_version(version) if safe_version(version) else ""
    ts = time.strftime("%Y%m%d%H%M%S")
    dst = os.path.join(bak_dir, "%s_bak_%s%s%s" % (name, ts, suffix, ext))
    index = 2
    while os.path.exists(dst):
        dst = os.path.join(bak_dir, "%s_bak_%s%s_%d%s" % (name, ts, suffix, index, ext))
        index += 1
    shutil.copy2(current_jar, dst)
    print("已备份: %s（版本 %s）" % (dst, version or "未登记"))
    return dst


def ensure_dir(path):
    if not os.path.isdir(path):
        os.makedirs(path, exist_ok=True)


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
    parser = argparse.ArgumentParser(description="用 exec 目录下的 jar 更新服务")
    parser.add_argument("--jar-file", default="", help="待更新的 jar 文件名，多个 jar 时必填")
    parser.add_argument("--version", default="",
                        help="本次投放的版本号；缺省时尝试读 exec/VERSION")
    parser.add_argument("--conf", default="", help="deploy.conf 路径，默认取脚本同目录")
    args = parser.parse_args()

    exec_dir = os.path.dirname(os.path.abspath(__file__))
    conf_path = args.conf or os.path.join(exec_dir, DEPLOY_CONF)
    try:
        conf = read_conf(conf_path)
        work_dir = conf["work_dir"].rstrip("/")
        jar_name = pick_jar(exec_dir, args.jar_file)
        # 目标文件名沿用工作目录里已有的 jar 名，避免同名服务出现两个 jar。
        existing = [n for n in os.listdir(work_dir) if n.endswith(".jar")] if os.path.isdir(work_dir) else []
        target_name = existing[0] if existing else jar_name
        target_jar = os.path.join(work_dir, target_name)
        version_file = os.path.join(work_dir, VERSION_FILE)
        # 待投放版本的标识：与构建机产物目录里的 VERSION 同名同格式，
        # 运维把产物连它的 VERSION 一起拷进 exec/ 即可，不必改名。
        incoming = os.path.join(exec_dir, VERSION_FILE)

        # 版本来源：显式参数优先，其次读 exec/VERSION，都取不到就记空。不猜版本——
        # 记错版本比不记版本更危险，而被替换掉的旧版本始终能从备份文件名里读回。
        version = args.version.strip() or read_version_file(incoming)
        previous = read_version_file(version_file) or (conf.get("version") or "").strip()
        backup(target_jar, os.path.join(exec_dir, "bak"), version_file)
        shutil.copy2(os.path.join(exec_dir, jar_name), target_jar)
        print("已更新: %s" % target_jar)
        os.remove(os.path.join(exec_dir, jar_name))
        if os.path.exists(incoming):
            os.remove(incoming)
        # 与平台发布流程保持同一时序：jar 先上盘、再登记版本、最后重启。
        # 重启失败时磁盘已是新版本，版本标识必须与磁盘一致而不是停留在旧值。
        write_version_file(version_file, version)
        refresh_conf_version(conf_path, version)
        print("版本登记: %s -> %s" % (previous or "未登记", version or "未登记"))
        restart(conf["service_name"])
        print("更新完成: %s" % target_name)
        return 0
    except Exception as e:
        print("更新失败: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
