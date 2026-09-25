package com.lx.deploy.release.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 发布版本 Mapper。
 *
 * <p>常规按项目、状态过滤由 MyBatis-Plus 条件构造器完成；这里只保留一条自定义 SQL——
 * 版本序号取号。它必须绕开逻辑删除过滤，因此不能用条件构造器表达。</p>
 *
 * @author lx
 * @since 2026-09-24 10:00:00
 */
@Mapper
public interface ReleaseRecordMapper extends BaseMapper<ReleaseRecord> {

    /**
     * 查询某项目已使用的最大版本序号。
     *
     * <p>取号 = 本查询结果 + 1。<b>刻意不过滤 is_deleted</b>：若把软删除的版本排除在外，
     * 序号会被重用（删除 v3 后新建又叫 v3），版本库里出现两个 v3，排序与追溯都失去意义。
     * 口径与本表唯一键 {@code uk_release_project_version(project_id, version, is_deleted)}
     * 不同是刻意的——唯一键允许软删后同名重建，而序号追求全局单调递增。</p>
     *
     * @param projectId 项目主键
     * @return 该项目已有的最大版本序号；一条都没有时返回 0
     */
    @Select("SELECT COALESCE(MAX(version_seq), 0) FROM release_record WHERE project_id = #{projectId}")
    Integer selectMaxVersionSeq(@Param("projectId") String projectId);
}
