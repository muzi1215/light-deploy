#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""JAR 工作目录初始化脚本（light-deploy 平台内置，禁止手工修改参数默认值）。

由平台在「首次部署」时上传到目标机工作目录的 exec/ 下并执行，
参数全部来自项目管理页的运行配置，脚本本身不接受任何交互式输入，
因此可以被平台通过 SSH 无人值守调用。

用法示例：
    python3 jar-init.py --work-dir /lx/jarapps/auth-tool-7888 \
        --jar-name auth-tool.jar --service-name auth-tool-7888 \
        --java-bin /usr/lib/jdk1.8.0_121/bin/java \
        --jvm-opts "-Xmx512m" --profiles prod \
        --config-dir config --config-file application.yml

行为：
    1. 幂等：工作目录已存在时只补齐子目录与服务文件，不重建、不覆盖已有 jar 与配置，
       这样存量服务（例如用 jar_linux.py 手工建过的）不会被平台初始化破坏；
    2. 创建工作目录下的 logs / exec / exec/bak / config 四级目录；
    3. 生成 /etc/systemd/system/{service_name}.service，jar 与配置分离：
       ExecStart 用 --spring.config.location 显式指向 config 目录，
       日常发版只换 jar，配置不动；
    4. 在 exec/ 下写入 deploy.conf，供 update.py / fallback.py 读取服务信息与当前版本
       （version 行首次初始化为空，之后由平台发布流程或这两个脚本刷新）；
    5. 执行 systemctl daemon-reload 与 enable（不 start，启动交给平台发版流程）。

兼容性：目标机只要求 Python 3.5 及以上（CentOS 7 一类机器的 python3 多为 3.6）。
脚本内禁止使用 3.7 才有的参数（例如 subprocess 的 capture_output / text），
否则会在老机器上抛 TypeError，整个初始化半途中断。

退出码：0 成功；1 失败，失败原因写入 stderr，平台据此判定目标失败。
"""
import argparse
import os
import subprocess
import sys

# 脚本使用的最低 Python 版本：subprocess.run 从 3.5 开始提供。
MIN_PYTHON = (3, 5)


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
    "unexpected keyword argument 'capture_output'"，把初始化打断在第一步。
    """
    result = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, universal_newlines=True)
    return result.returncode, result.stdout.strip(), result.stderr.strip()


def ensure_dir(path):
    """目录不存在则创建，已存在则不动。"""
    if not os.path.isdir(path):
        os.makedirs(path, exist_ok=True)
        print("创建目录: %s" % path)


def read_current_version(conf_path):
    """读取 deploy.conf 里已登记的版本号；文件不存在或没有该行时返回空串。"""
    if not os.path.exists(conf_path):
        return ""
    try:
        with open(conf_path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("version="):
                    return line.split("=", 1)[1].strip()
    except (IOError, OSError):
        pass
    return ""


def write_conf(work_dir, args):
    """把服务信息写到 exec/deploy.conf，供 update.py / fallback.py 复用。

    version 行表示本机当前运行的版本：平台发布后由平台刷新，运维手工更新时由
    update.py 刷新，回退时由 fallback.py 刷新。首次初始化为空——此刻确实还没有
    发布过任何版本，写成 unknown 会被误读成一个真实版本号。已有值原样保留：
    init 可能被运维重复执行，重置它会让「这台机器在跑什么」这个信息凭空丢失。
    """
    conf_path = os.path.join(work_dir, "exec", "deploy.conf")
    version = read_current_version(conf_path)
    content = "service_name=%s\njar_name=%s\nwork_dir=%s\nversion=%s\n" % (
        args.service_name, args.jar_name, work_dir, version)
    with open(conf_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("已写入服务信息: %s（当前版本 %s）" % (conf_path, version or "未登记"))


def write_unit(work_dir, args):
    """生成 systemd 单元；单元已存在时不覆盖，避免破坏机器上既有的启动参数。"""
    unit_path = "/etc/systemd/system/%s.service" % args.service_name
    if os.path.exists(unit_path):
        print("服务文件已存在，跳过覆盖: %s" % unit_path)
        return
    config_location = "%s/%s/%s" % (work_dir, args.config_dir, args.config_file)
    content = """[Unit]
Description={service} Service
After=network.target
Wants=network.target

[Service]
Type=simple
User=root
WorkingDirectory={work_dir}
ExecStart={java_bin} \\
  -Djava.awt.headless=true \\
  -Dfile.encoding=UTF-8 \\
  -Dspring.profiles.active={profiles} \\
  -Dlogging.file.path={work_dir}/logs \\
  {jvm_opts} \\
  -jar {work_dir}/{jar_name} \\
  --spring.config.location={config_location}
ExecStop=/bin/kill -TERM $MAINPID
TimeoutStopSec=30
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=file:{work_dir}/logs/journal_app.log
StandardError=file:{work_dir}/logs/journal_error.log
SyslogIdentifier={service}
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
""".format(service=args.service_name,
           work_dir=work_dir,
           java_bin=args.java_bin,
           profiles=args.profiles,
           jvm_opts=args.jvm_opts,
           jar_name=args.jar_name,
           config_location=config_location)
    with open(unit_path, "w", encoding="utf-8") as f:
        f.write(content)
    print("已生成服务文件: %s" % unit_path)


def main():
    if not check_python_version():
        return 1
    parser = argparse.ArgumentParser(description="初始化 JAR 工作目录并注册 systemd 服务")
    parser.add_argument("--work-dir", required=True, help="目标机上的固定工作目录")
    parser.add_argument("--jar-name", required=True, help="jar 文件名，不含路径")
    parser.add_argument("--service-name", required=True, help="systemd 服务名，不含 .service")
    parser.add_argument("--java-bin", required=True, help="java 可执行文件绝对路径")
    parser.add_argument("--jvm-opts", default="", help="附加 JVM 参数，如 -Xmx512m")
    parser.add_argument("--profiles", default="prod", help="spring.profiles.active")
    parser.add_argument("--config-dir", default="config", help="工作目录下的配置目录名")
    parser.add_argument("--config-file", default="application.yml", help="配置文件名")
    args = parser.parse_args()

    work_dir = args.work_dir.rstrip("/")
    existed = os.path.isdir(work_dir)
    try:
        ensure_dir(work_dir)
        ensure_dir(os.path.join(work_dir, "logs"))
        ensure_dir(os.path.join(work_dir, "exec"))
        ensure_dir(os.path.join(work_dir, "exec", "bak"))
        ensure_dir(os.path.join(work_dir, args.config_dir))
        write_conf(work_dir, args)
        write_unit(work_dir, args)
        code, _, err = run("systemctl daemon-reload && systemctl enable %s" % args.service_name)
        if code != 0:
            print("systemctl daemon-reload/enable 失败: %s" % err, file=sys.stderr)
            return 1
        print("初始化完成: %s（%s）" % (work_dir, "目录已存在，仅补齐" if existed else "新建"))
        return 0
    except Exception as e:
        print("初始化失败: %s" % e, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
