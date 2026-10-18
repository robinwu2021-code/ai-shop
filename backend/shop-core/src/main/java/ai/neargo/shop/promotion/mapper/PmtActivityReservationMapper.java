package ai.neargo.shop.promotion.mapper;

import ai.neargo.shop.promotion.entity.PmtActivityReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** The reservation state transition, budget and participant deltas share the same core transaction. */
public interface PmtActivityReservationMapper extends BaseMapper<PmtActivityReservation> {
    @Select("SELECT * FROM pmt_activity_reservation WHERE tenant_no=#{tenant} AND source_key=#{source} "
            + "AND deleted=0 FOR UPDATE")
    PmtActivityReservation lockSource(@Param("tenant") String tenant, @Param("source") String source);

    @Select("SELECT * FROM pmt_activity_reservation WHERE tenant_no=#{tenant} AND reservation_no=#{no} "
            + "AND deleted=0 FOR UPDATE")
    PmtActivityReservation lock(@Param("tenant") String tenant, @Param("no") String no);

    @Update("UPDATE pmt_activity_reservation SET status=#{status},pay_fact_no=#{fact},version=version+1,"
            + "updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_no=#{tenant} AND reservation_no=#{no} "
            + "AND deleted=0 AND status='RESERVED' AND #{status} IN ('COMMITTED','RELEASED')")
    int settle(@Param("tenant") String tenant, @Param("no") String no,
               @Param("status") String status, @Param("fact") String fact);
}
