# syntax=docker/dockerfile:1.7
# ================================================================
# Light Deploy 平台镜像（Spring Boot 4.1 / Java 21 / 端口 2027）
#
# 定位：**只装 jar，不编译**。
#   jar 在本地（或构建机）用 `mvn -DskipTests package` 构建，
#   改名为 light-deploy.jar 后与本目录的 Dockerfile 平铺上传到目标机，
#   compose build 只做「COPY jar + 配 JVM 参数」这一件事。
#
# 为什么不在镜像里编译：
#   1) 平台本身要被「手工部署」兜底（见 docs/平台不可用应急手册.md），
#      镜像里留 Maven + 源码只会让镜像大几百 MB、构建慢，且没有任何收益；
#   2) 编译需要 JDK + 私服依赖，目标机通常只有 Docker；
#   3) 出问题时运维能直接把同一个 jar 用 `java -jar` 裸跑起来比对，镜像与 jar 一一对应。
#
# 构建前置（compose build 会自动做，手工构建时同）：
#   cp target/light-deploy-0.0.1-SNAPSHOT.jar Docker/light-deploy.jar
#   cd Docker && docker build -t light-deploy:local .
# ================================================================
FROM eclipse-temurin:21-jre-jammy

# 拉不动 Docker Hub 时，先给宿主机配 registry-mirrors，或用带镜像前缀的等价地址，
# 不建议在 Dockerfile 里写死某家第三方加速域名（地址会失效，且引入供应链风险）。

LABEL description="Light Deploy 轻量部署发布平台" \
      owner="lx"

# 容器内工作目录。/app/application.yaml 会被 compose 挂载进来覆盖打进的 classpath 配置。
WORKDIR /app

# TZ + LANG：容器默认 UTC，会让日志时间与 Java 时间比北京时间早 8 小时，
# 排障时对不上目标机的操作日志；compose 里还挂了 /etc/localtime 做双保险。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    LOG_PATH=/app/logs \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai -Dfile.encoding=UTF-8"

# 非 root 运行（UID 1000 与 AIops 侧 appuser 保持一致）。
# 注意：宿主机 bind mount 的 ./logs 目录必须 chown 1000:1000，否则容器写不进日志。
RUN groupadd -g 1000 appuser \
    && useradd -u 1000 -g appuser -m -s /bin/bash appuser \
    && mkdir -p /app/logs \
    && chown -R appuser:appuser /app

# 唯一被复制进来的东西：一个 fat jar。Dockerfile 与 jar 平铺在同一目录（build context）。
# 用 ARG 留出改名/换版本的口子：docker build --build-arg JAR_FILE=xxx.jar .
# 平铺目录里不要放第二个 jar（COPY 指定文件名不受影响，但容易搞混用的是哪个包）。
ARG JAR_FILE=light-deploy.jar
COPY ${JAR_FILE} /app/light-deploy.jar
RUN chown appuser:appuser /app/light-deploy.jar

USER appuser

# 仅作说明用，实际端口由 server.port 决定（application.yaml 里 2027）
EXPOSE 2027

# exec 形态 + sh -c：让 java 成为 PID 1，docker stop 的 SIGTERM 能直达 JVM 触发优雅停机；
# JAVA_OPTS 用环境变量展开，改参数不用重建镜像（compose 里覆盖 environment 即可）。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/light-deploy.jar"]
