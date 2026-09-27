package com.lx.deploy.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.util.TimeRangeSupport;
import com.lx.deploy.common.constants.EnableStatus;
import com.lx.deploy.common.enums.task.TaskType;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.crypto.CredentialCrypto;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import com.lx.deploy.server.model.dto.ServerDto;
import com.lx.deploy.server.model.vo.ServerVo;
import com.lx.deploy.task.model.dto.TaskDto;
import com.lx.deploy.task.model.vo.TaskVo;
import com.lx.deploy.task.service.TaskService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 部署服务器服务。
 *
 * <p>当前类直接继承 MyBatis-Plus ServiceImpl，不额外创建 Service 接口。除了基础 CRUD，
 * 还统一提供条件分页、条件列表、按 ID 查询、新增、修改和逻辑删除方法。</p>
 *
 * @author lx
 * @since 2026-08-23 19:56:21
 */
@Service
public class ServerService extends ServiceImpl<ServerMapper, Server> {

    /** 平台统一 AES 凭据加解密组件。 */
    private final CredentialCrypto credentialCrypto;

    /**
     * 任务服务。
     *
     * <p>连通性测试本质是一次异步任务，服务器模块只负责提交任务，不直接执行远程操作，
     * 因此这里依赖任务模块的服务而不是 SSH 执行层（AGENTS.md §12：Controller 不写执行逻辑）。</p>
     */
    private final TaskService taskService;

    /**
     * 注入跨模块依赖。
     *
     * <p>本表 CRUD 用的 Mapper 由 MyBatis-Plus 父类字段注入（{@code ServiceImpl} →
     * {@code CrudRepository.baseMapper}），这里只声明跨模块依赖；
     * 用构造器而不是字段注入，保证实例一出生就依赖齐全、且依赖不可被替换。</p>
     *
     * @param credentialCrypto 凭据加解密组件
     * @param taskService 任务服务
     */
    public ServerService(CredentialCrypto credentialCrypto, TaskService taskService) {
        this.credentialCrypto = credentialCrypto;
        this.taskService = taskService;
    }

    /**
     * 提交一次服务器连通性测试任务。
     *
     * <p>校验规则：服务器必须存在且处于启用状态；同一服务器不允许同时存在多个未完成的任务，
     * 避免页面连点造成重复连接。任务创建后立即返回，真正的连接与探活由任务执行器异步完成，
     * 页面通过 /taskLog/pull 增量拉取执行日志。</p>
     *
     * @param id 目标服务器主键
     * @return 新建的异步任务响应对象，状态为 0（排队中）
     * @throws DeployException 服务器不存在、已停用或已有未完成的连通性测试任务时抛出
     */
    public TaskVo submitConnectivityTest(String id) {
        // 记录不存在或已逻辑删除时直接抛出，避免对不存在的服务器发起连接。
        Server entity = getRequiredEntityById(id);
        // 停用的服务器不参与任何远程操作（AGENTS.md §6.3：status 只表示是否允许被平台连接）。
        if (entity.getStatus() == null || entity.getStatus() != EnableStatus.ENABLED) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "服务器已停用，不允许执行连通性测试");
        }
        // 同一服务器已有排队中或执行中的测试任务时拒绝重复提交。
        if (taskService.hasUnfinishedTask(TaskType.SERVER_TEST.getCode(), id)) {
            throw new DeployException(ResponseCode.DATA_CONFLICT, "该服务器已有未完成的连通性测试任务，请等待其结束");
        }
        // 任务类型与关联业务主键由服务端决定，不接受调用方自定义，避免任务指向错误目标。
        TaskDto dto = new TaskDto();
        dto.setTaskType(TaskType.SERVER_TEST.getCode());
        dto.setBizId(id);
        return taskService.create(dto);
    }

    /**
     * 根据非空条件分页查询部署服务器。
     *
     * <p>查询条件由 buildQueryWrapper 方法统一构建，MyBatis-Plus 会自动追加逻辑删除条件。
     * 分页结果转换为项目统一 PageResult，避免 Controller 暴露 IPage 类型。</p>
     *
     * @param pageQuery 分页参数，包含页码和每页数量
     * @param condition 查询条件对象，仅使用允许查询的非空业务字段
     * @return 当前页的记录和分页元数据
     */
    public PageResult<ServerVo> pageByCondition(PageQuery pageQuery, ServerDto condition) {
        // 创建分页对象并执行带动态条件的分页查询。
        Page<Server> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        // 转换为项目统一分页结果，隔离 MyBatis-Plus 的分页实现。
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(),
                page.getRecords().stream().map(this::toVo).toList());
    }

    /**
     * 根据非空条件查询全部匹配记录。
     *
     * @param condition 查询条件对象；为 null 时查询所有未删除记录
     * @return 符合条件的记录列表
     */
    public List<ServerVo> listByCondition(ServerDto condition) {
        // 复用统一条件构造器，保证分页查询和列表查询的过滤规则一致。
        return list(buildQueryWrapper(condition)).stream().map(this::toVo).toList();
    }

    /**
     * 根据主键查询部署服务器。
     *
     * <p>记录不存在或已被逻辑删除时抛出统一业务异常，Controller 无需重复进行空值判断。</p>
     *
     * @param id 数据主键
     * @return 对应的业务实体
     * @throws DeployException 数据不存在时抛出
     */
    public ServerVo getRequiredById(String id) {
        // MyBatis-Plus 会自动过滤 is_deleted=1 的记录。
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 新增一条部署服务器。
     *
     * <p>主键、创建时间和更新时间由 BaseEntity 与 MyBatis-Plus 自动填充。
     * 保存失败时抛出业务异常，避免接口返回成功但数据库未写入。</p>
     *
     * @param  dto 待新增的业务实体
     * @return 保存完成且包含自动生成主键的业务实体
     * @throws DeployException 数据保存失败时抛出
     */
    public ServerVo create(ServerDto dto) {
        if (!StringUtils.hasText(dto.getCredential())) {
            throw new DeployException(ResponseCode.VALIDATION_ERROR, "SSH 密码或私钥不能为空");
        }
        // 新增数据不接受调用方指定主键，统一由 MyBatis-Plus 生成 UUID。
        Server entity = new Server();
        BeanUtils.copyProperties(dto, entity);
        // 前端提交的是凭据明文，写入数据库前统一转换为 AES-GCM 密文。
        entity.setCiphertext(credentialCrypto.encrypt(dto.getCredential()));
        entity.setId(null);
        // 目录路径去空白；未填写时置空，让数据库默认值生效（代码不再持有默认路径常量）。
        normalizePaths(entity);
        if (!save(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "新增部署服务器失败");
        }
        return toVo(entity);
    }

    /**
     * 根据主键修改部署服务器。
     *
     * <p>先确认目标记录存在，再将路径中的主键写入实体，避免请求体主键与路径主键不一致。</p>
     *
     * @param id 待修改数据的主键
     * @param dto 包含修改后字段的业务实体
     * @return 修改完成后的最新业务实体
     * @throws DeployException 数据不存在或修改失败时抛出
     */
    public ServerVo update(String id, ServerDto dto) {
        // 在执行更新前确认目标记录存在，返回明确的数据不存在错误。
        Server entity = getRequiredEntityById(id);
        BeanUtils.copyProperties(dto, entity);
        if (StringUtils.hasText(dto.getCredential())) {
            // 修改请求传入新凭据时重新加密；未传凭据则继续保留数据库中的旧密文。
            entity.setCiphertext(credentialCrypto.encrypt(dto.getCredential()));
        }
        // 始终使用路径主键，防止请求体篡改其他记录。
        entity.setId(id);
        // 目录路径去空白；未填写时置空，让数据库默认值生效（代码不再持有默认路径常量）。
        normalizePaths(entity);
        if (!updateById(entity)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "修改部署服务器失败");
        }
        return toVo(getRequiredEntityById(id));
    }

    /**
     * 根据主键逻辑删除部署服务器。
     *
     * <p>BaseEntity 已配置 is_deleted 逻辑删除字段，因此 removeById 不会物理删除数据库记录。</p>
     *
     * @param id 待删除数据的主键
     * @throws DeployException 数据不存在或删除失败时抛出
     */
    public void delete(String id) {
        // 删除前先校验记录存在，避免对不存在数据返回成功。
        getRequiredEntityById(id);
        if (!removeById(id)) {
            throw new DeployException(ResponseCode.BUSINESS_ERROR, "删除部署服务器失败");
        }
    }

    /**
     * 根据条件对象构建 MyBatis-Plus 动态查询条件。
     *
     * <p>字符串字段使用等值查询并跳过空字符串；其他简单字段在非空时使用等值查询。
     * 密码、密文、Token、私钥等敏感字段不会被加入查询条件。</p>
     *
     * @param condition 查询条件对象；为 null 时返回空条件构造器
     * @return 可用于分页或列表查询的条件构造器
     */
    private LambdaQueryWrapper<Server> buildQueryWrapper(ServerDto condition) {
        LambdaQueryWrapper<Server> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper;
        }
        // 服务器名称：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getServerName()),
                Server::getServerName, condition.getServerName());
        // 服务器IP地址或域名：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getHost()),
                Server::getHost, condition.getHost());
        // SSH端口：字段非空时使用等值条件查询。
        wrapper.eq(condition.getSshPort() != null,
                Server::getSshPort, condition.getSshPort());
        // SSH登录用户：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getUsername()),
                Server::getUsername, condition.getUsername());
        // SSH认证类型：1密码，2私钥：字段非空时使用等值条件查询。
        wrapper.eq(condition.getAuthType() != null,
                Server::getAuthType, condition.getAuthType());
        // SSH 密文属于敏感字段，禁止加入普通列表和分页查询条件。
        // 环境标签：1开发，2测试，3生产：字段非空时使用等值条件查询。
        wrapper.eq(condition.getEnvTag() != null,
                Server::getEnvTag, condition.getEnvTag());
        // 服务器状态：0停用，1启用：字段非空时使用等值条件查询。
        wrapper.eq(condition.getStatus() != null,
                Server::getStatus, condition.getStatus());
        // 服务器描述：非空字符串使用等值条件查询。
        wrapper.eq(StringUtils.hasText(condition.getDescription()),
                Server::getDescription, condition.getDescription());
        // 默认按创建时间倒序返回，优先展示最近新增的数据。
        // 创建时间区间：闭区间，开始晚于截止由 TimeRangeSupport 直接判参数错误。
        TimeRangeSupport.apply(wrapper, Server::getCreateTime, condition);
        wrapper.orderByDesc(Server::getCreateTime);
        return wrapper;
    }

    /**
     * 查询 Service 内部使用的数据库实体。
     *
     * @param id 数据主键
     * @return 未删除的服务器实体
     * @throws DeployException 数据不存在时抛出
     */
    private Server getRequiredEntityById(String id) {
        Server entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署服务器不存在");
        }
        return entity;
    }

    /**
     * 规范化三个目录路径字段与构建环境脚本。
     *
     * <p>去掉首尾空白；空值或空白统一置为 null，插入时由数据库列的 DEFAULT 填充，
     * 保证「默认路径只有一处定义」且落在数据库而不是代码里。
     * 这里不做路径合法性校验——目录是否存在要到机器上验证（连通性测试、构建执行时才发起连接），
     * 保存阶段只保证值本身不含空白，避免误存成带空格的路径导致远程命令拼接出错。
     * 构建环境脚本同为空白归一为 null（不执行额外脚本），非空时只去首尾空白，内容原样保存。</p>
     *
     * @param entity 待规范化的服务器实体，就地修改
     */
    private void normalizePaths(Server entity) {
        entity.setBuildWorkspaceDir(blankToNull(entity.getBuildWorkspaceDir()));
        entity.setBuildArtifactDir(blankToNull(entity.getBuildArtifactDir()));
        entity.setAgentBaseDir(blankToNull(entity.getAgentBaseDir()));
        entity.setBuildEnvScript(blankToNull(entity.getBuildEnvScript()));
    }

    /**
     * 把空白字符串转换为 null，非空白字符串去掉首尾空白后返回。
     *
     * @param value 原始值，允许为 null
     * @return 规范化后的值，空白输入返回 null
     */
    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /**
     * 将数据库实体转换为接口 VO。
     *
     * @param entity 数据库实体
     * @return 不包含 SSH 密文的响应对象
     */
    private ServerVo toVo(Server entity) {
        ServerVo vo = new ServerVo();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }
}
