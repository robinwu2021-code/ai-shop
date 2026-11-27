package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityParticipant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Every path locks the activity budget first, keeping personal capacity and global capacity in one order. */
public interface PmtActivityParticipantMapper extends BaseMapper<PmtActivityParticipant> {
    @Insert("INSERT INTO pmt_quota (tenant_no,quota_no,target_type,target_no,quota_dimension,activity_no,user_no,quota_held,quota_used,"
            + "version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenant},UUID(),'ACTIVITY',#{activity},'PER_USER',#{activity},#{user},0,0,0,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'SYSTEM','SYSTEM') "
            + "ON DUPLICATE KEY UPDATE id=id")
    int ensure(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user);

    @Update("UPDATE pmt_quota SET quota_held=quota_held+#{quantity},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_no=#{tenant} AND target_type='ACTIVITY' AND target_no=#{activity} AND quota_dimension='PER_USER' "
            + "AND user_no=#{user} AND deleted=0 AND #{quantity}>0 "
            + "AND #{limit}-quota_held-quota_used>=#{quantity}")
    int reserve(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user,
                @Param("quantity") int quantity, @Param("limit") int limit);

    @Update("UPDATE pmt_quota SET quota_held=quota_held-#{quantity},"
            + "quota_used=quota_used+#{usedQuantity},version=version+1,updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE tenant_no=#{tenant} AND target_type='ACTIVITY' AND target_no=#{activity} AND quota_dimension='PER_USER' AND user_no=#{user} AND deleted=0 "
            + "AND #{quantity}>0 AND quota_held>=#{quantity} "
            + "AND (#{usedQuantity}=0 OR #{usedQuantity}=#{quantity})")
    int settle(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user,
               @Param("quantity") int quantity, @Param("usedQuantity") int usedQuantity);
}
