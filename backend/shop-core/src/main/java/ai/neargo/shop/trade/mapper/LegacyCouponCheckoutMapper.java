package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entitlement.entity.OrdLegacyCouponCheckout;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface LegacyCouponCheckoutMapper extends BaseMapper<OrdLegacyCouponCheckout> {
    @Select("SELECT * FROM ord_legacy_coupon_checkout WHERE tenant_no=#{tenant} AND checkout_no=#{no} AND deleted=0")
    OrdLegacyCouponCheckout find(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM ord_legacy_coupon_checkout WHERE tenant_no=#{tenant} AND checkout_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    OrdLegacyCouponCheckout lock(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM ord_legacy_coupon_checkout WHERE tenant_no=#{tenant} AND order_no=#{no} AND deleted=0 FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    OrdLegacyCouponCheckout lockByOrder(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM ord_legacy_coupon_checkout WHERE tenant_no=#{tenant} AND order_no=#{no} AND deleted=0")
    @Options(useCache=false)
    OrdLegacyCouponCheckout findByOrder(@Param("tenant") String tenant,@Param("no") String no);
    @Select("""
        SELECT c.title FROM pmt_user_coupon u JOIN pmt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no
          WHERE #{system}='PMT' AND u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.user_coupon_no=#{no}
        UNION ALL
        SELECT c.title FROM mkt_user_coupon u JOIN mkt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no
          WHERE #{system}='MKT_LEGACY' AND u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.user_coupon_no=#{no}
        LIMIT 1
        """)
    String originalTitle(@Param("tenant") String tenant,@Param("system") String system,@Param("user") String user,@Param("no") String no);
    @Insert("""
        INSERT INTO ord_legacy_coupon_checkout(tenant_no,checkout_no,operation_no,user_no,entity_no,command_hash,
            stage_hash,economic_hash,fact_snapshot,status,pay_state,recovery_due_at,version,deleted,created_at,updated_at,created_by,updated_by)
        VALUES(#{tenantNo},#{checkoutNo},#{operationNo},#{userNo},#{entityNo},#{commandHash},#{stageHash},#{economicHash},
            #{factSnapshot},'OPEN',#{payState},#{recoveryDueAt},0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy})
        ON DUPLICATE KEY UPDATE id=id
        """)
    int claim(OrdLegacyCouponCheckout row);
    /** Scalar change DTO deliberately avoids generic BaseEntity audit fill and never rewrites immutable source facts. */
    record StateChange(String tenant,String checkoutNo,long revision,String status,String heldSnapshot,String decisionSnapshot,
                       String reversalSnapshot,String orderNo,String payState,String payDecisionNo,LocalDateTime payAckedAt,
                       LocalDateTime recoveryDueAt,LocalDateTime updatedAt,String updatedBy) { }
    @Update("""
        UPDATE ord_legacy_coupon_checkout SET status=#{change.status},held_snapshot=#{change.heldSnapshot},
            decision_snapshot=#{change.decisionSnapshot},reversal_snapshot=#{change.reversalSnapshot},order_no=#{change.orderNo},
            pay_state=#{change.payState},pay_decision_no=#{change.payDecisionNo},pay_acked_at=#{change.payAckedAt},
            recovery_due_at=#{change.recoveryDueAt},updated_at=#{change.updatedAt},updated_by=#{change.updatedBy},version=version+1
        WHERE tenant_no=#{change.tenant} AND checkout_no=#{change.checkoutNo} AND version=#{change.revision} AND deleted=0
        """)
    int updateState(@Param("change") StateChange change);

}
