package com.lx.deploy.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.lx.deploy.common.constants.ReleaseStatus;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.model.PageQuery;
import com.lx.deploy.common.model.PageResult;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.release.model.dto.ReleaseRecordDto;
import com.lx.deploy.release.model.vo.ReleaseRecordVo;
import com.lx.deploy.server.mapper.ServerMapper;
import com.lx.deploy.server.model.Server;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 发布版本服务（只读）。
 *
 * <p>发布版本由构建链路在构建成功时自动创建（见 BuildService.completeSuccess），
 * 因此本服务当前只提供查询能力，不提供新增、修改与删除接口：
 * 版本的生命周期由「构建成功 → 可发布 → 超出保留数量被废弃」驱动，
 * 开放写接口只会让人手工造出一个没有产物支撑的版本。</p>
 *
 * <p>主要用途是给发布模块提供「这个项目有哪些版本可以发」的下拉数据。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Service
public class ReleaseService extends ServiceImpl<ReleaseRecordMapper, ReleaseRecord> {

    /** 服务器 Mapper，用于回填产物所在构建机名称。 */
    private final ServerMapper serverMapper;

    /**
     * 注入构建机名称回填所需的 Mapper。
     *
     * @param serverMapper 服务器 Mapper
     */
    public ReleaseService(ServerMapper serverMapper) {
        this.serverMapper = serverMapper;
    }

    /**
     * 根据条件分页查询发布版本。
     *
     * @param pageQuery 分页参数
     * @param condition 查询条件（项目、状态）
     * @return 发布版本 VO 分页结果
     */
    public PageResult<ReleaseRecordVo> pageByCondition(PageQuery pageQuery, ReleaseRecordDto condition) {
        Page<ReleaseRecord> page = page(pageQuery.toPage(), buildQueryWrapper(condition));
        List<ReleaseRecordVo> records = toVos(page.getRecords());
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getPages(), records);
    }

    /**
     * 根据条件查询全部匹配发布版本。
     *
     * @param condition 查询条件；为 null 时查询所有未删除记录
     * @return 发布版本 VO 列表，按创建时间倒序
     */
    public List<ReleaseRecordVo> listByCondition(ReleaseRecordDto condition) {
        return toVos(list(buildQueryWrapper(condition)));
    }

    /**
     * 批量转换为 VO，并回填产物所在构建机名称。
     *
     * <p>名称在<b>这一层一次性批量查</b>，不在逐条转换里查服务器：版本列表动辄几十条，
     * 逐条查会把一次列表接口放大成 N 次查询。历史版本可能产自不同的构建机，
     * 因此这里按 ID 去重后一次性取回。</p>
     *
     * @param entities 实体列表
     * @return VO 列表
     */
    private List<ReleaseRecordVo> toVos(List<ReleaseRecord> entities) {
        List<ReleaseRecordVo> vos = entities.stream().map(this::toVo).toList();
        Map<String, String> serverNames = resolveServerNames(vos);
        for (ReleaseRecordVo vo : vos) {
            vo.setBuildServerName(serverNames.get(vo.getBuildServerId()));
        }
        return vos;
    }

    /**
     * 取版本用到的构建机名称映射。
     *
     * @param vos 已转换的 VO 列表
     * @return 构建机 ID 到名称的映射；机器不存在时对应值为 null
     */
    private Map<String, String> resolveServerNames(List<ReleaseRecordVo> vos) {
        Map<String, String> names = new HashMap<>();
        for (ReleaseRecordVo vo : vos) {
            if (StringUtils.hasText(vo.getBuildServerId())) {
                names.put(vo.getBuildServerId(), null);
            }
        }
        if (names.isEmpty()) {
            return names;
        }
        for (Server server : serverMapper.selectBatchIds(names.keySet())) {
            names.put(server.getId(), server.getServerName());
        }
        return names;
    }

    /**
     * 根据主键查询发布版本。
     *
     * @param id 发布版本主键
     * @return 发布版本 VO
     * @throws DeployException 数据不存在时抛出
     */
    public ReleaseRecordVo getRequiredById(String id) {
        ReleaseRecord entity = getById(id);
        if (entity == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "发布版本不存在");
        }
        return toVo(entity);
    }

    /**
     * 将数据库实体转换为响应 VO。
     *
     * @param entity 数据库实体
     * @return 含状态文案的响应对象
     */
    private ReleaseRecordVo toVo(ReleaseRecord entity) {
        ReleaseRecordVo vo = new ReleaseRecordVo();
        BeanUtils.copyProperties(entity, vo);
        vo.setStatusText(ReleaseStatus.describe(entity.getStatus()));
        return vo;
    }

    /**
     * 根据请求 DTO 构建动态查询条件。
     *
     * <p>默认按创建时间倒序，让最新构建出的版本排在最前，符合发布时「发最新的」这一常见动作。</p>
     *
     * @param condition 查询条件；为 null 时返回空条件构造器
     * @return 条件构造器
     */
    private LambdaQueryWrapper<ReleaseRecord> buildQueryWrapper(ReleaseRecordDto condition) {
        LambdaQueryWrapper<ReleaseRecord> wrapper = new LambdaQueryWrapper<>();
        if (condition == null) {
            return wrapper.orderByDesc(ReleaseRecord::getCreateTime);
        }
        wrapper.eq(StringUtils.hasText(condition.getProjectId()),
                ReleaseRecord::getProjectId, condition.getProjectId());
        wrapper.eq(condition.getStatus() != null, ReleaseRecord::getStatus, condition.getStatus());
        wrapper.orderByDesc(ReleaseRecord::getCreateTime);
        return wrapper;
    }
}
