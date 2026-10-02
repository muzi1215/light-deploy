package com.lx.deploy.common.enums.project;

/**
 * Docker 部署形态：容器怎么被拉起来。
 *
 * <p>只用于 DOCKER 运行时，取值来自运行配置 {@code runtime_config_json.deploy_form}，
 * 可被部署目标的同名配置覆盖（同一项目的不同机器允许用不同形态）。</p>
 *
 * <ul>
 *   <li>{@link #RUN}：平台拼 {@code docker run}，端口映射、容器名、附加参数都来自运行配置；</li>
 *   <li>{@link #COMPOSE}：平台把版本里快照的 docker-compose.yml 下发到目标机，
 *       用 {@code docker compose up -d} 启动，服务编排完全由项目自己的文件决定。</li>
 * </ul>
 *
 * <p>为什么默认值是 RUN 而不是 COMPOSE：形态切换会改变发布链路（后者要下发文件、要走 compose 子命令），
 * 存量项目升级后若默认变成 COMPOSE，会因为版本里没有 compose 快照而发布失败。
 * 默认 RUN 保证「不动配置 = 行为不变」。</p>
 *
 * @author lx
 * @since 2026-09-29 00:40:00
 */
public enum DeployForm {

    /** 平台拼 docker run 命令，端口映射与容器名来自运行配置。 */
    RUN("RUN", "docker run"),

    /** 用项目自带的 docker-compose.yml 启动，版本号以变量注入。 */
    COMPOSE("COMPOSE", "docker compose");

    /** 枚举编码，落库与配置传递使用。 */
    private final String code;

    /** 中文名，用于日志与前端展示。 */
    private final String label;

    DeployForm(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 按编码取枚举；未知或空值一律返回 {@link #RUN}，保证存量数据与手填错误都不至于让发布链路崩掉。
     *
     * @param code 运行配置里的形态编码
     * @return 对应枚举，无法识别时为 RUN
     */
    public static DeployForm fromCode(String code) {
        if (code == null) {
            return RUN;
        }
        for (DeployForm form : values()) {
            if (form.code.equalsIgnoreCase(code.strip())) {
                return form;
            }
        }
        return RUN;
    }
}
