package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Explicit CAS also persists null fields when a draft removes an optional end date. */
@Mapper
public interface UnifiedActivityManagementMapper {
    @Update("UPDATE pmt_activity SET name=#{row.name},scene=#{row.scene},schedule_type=#{row.scheduleType},"
            + "start_at=#{row.startAt},end_at=#{row.endAt},draft_snapshot=#{row.draftSnapshot},"
            + "status=#{row.status},current_version_no=#{row.currentVersionNo},ended_reason=#{row.endedReason},"
            + "updated_at=#{row.updatedAt},updated_by=#{row.updatedBy},version=version+1 "
            + "WHERE tenant_no=#{row.tenantNo} AND entity_no=#{row.entityNo} AND activity_no=#{row.activityNo} "
            + "AND version=#{row.version} AND deleted=0 AND archived_at IS NULL AND draft_snapshot IS NOT NULL")
    int update(@Param("row") PmtActivity row);
}
