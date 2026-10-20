package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityParticipant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** Every path locks the activity budget first, keeping personal capacity and global capacity in one order. */
public interface PmtActivityParticipantMapper extends BaseMapper<PmtActivityParticipant> {
    @Insert("INSERT INTO pmt_activity_participant (tenant_no,activity_no,user_no,held_count,used_count,"
            + "version,deleted,created_at,updated_at,created_by,updated_by) VALUES "
            + "(#{tenant},#{activity},#{user},0,0,0,0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'SYSTEM','SYSTEM') "
            + "ON DUPLICATE KEY UPDATE id=id")
    int ensure(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user);

    @Update("UPDATE pmt_activity_participant SET held_count=held_count+#{quantity},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_no=#{tenant} AND activity_no=#{activity} "
            + "AND user_no=#{user} AND deleted=0 AND #{quantity}>0 "
            + "AND #{limit}-held_count-used_count>=#{quantity}")
    int reserve(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user,
                @Param("quantity") int quantity, @Param("limit") int limit);

    @Update("UPDATE pmt_activity_participant SET held_count=held_count-#{quantity},"
            + "used_count=used_count+#{usedQuantity},version=version+1,updated_at=CURRENT_TIMESTAMP(3) "
            + "WHERE tenant_no=#{tenant} AND activity_no=#{activity} AND user_no=#{user} AND deleted=0 "
            + "AND #{quantity}>0 AND held_count>=#{quantity} "
            + "AND (#{usedQuantity}=0 OR #{usedQuantity}=#{quantity})")
    int settle(@Param("tenant") String tenant, @Param("activity") String activity, @Param("user") String user,
               @Param("quantity") int quantity, @Param("usedQuantity") int usedQuantity);
}
