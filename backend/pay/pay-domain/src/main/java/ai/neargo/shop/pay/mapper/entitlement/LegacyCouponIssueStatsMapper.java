package ai.neargo.shop.pay.mapper.entitlement;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** Statistics read canonical committed operations/allocations, independent of repairable display history. */
public interface LegacyCouponIssueStatsMapper {
    record Stats(int usedCount,long usedAmountMinor) { }
    @Select("""
        <script>
        SELECT COUNT(DISTINCT f.instance_no) AS usedCount,COALESCE(SUM(f.amount_minor),0) AS usedAmountMinor FROM (
          SELECT i.instance_no,o.discount_minor AS amount_minor
          FROM ent_legacy_coupon_operation o JOIN ent_instance i ON i.tenant_no=o.tenant_no AND i.instance_no=o.instance_no
          WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.status='COMMITTED' AND o.deleted=0
            AND i.entity_no=#{entity} AND i.grant_no IN
            <foreach collection="grants" item="g" open="(" close=")" separator=",">#{g}</foreach>
          UNION ALL
          SELECT i.instance_no,a.discount_minor-COALESCE(a.reversed_discount_minor,0) AS amount_minor
          FROM ent_use_allocation a JOIN ent_use_intent o ON o.tenant_no=a.tenant_no AND o.operation_no=a.operation_no
            JOIN ent_instance i ON i.tenant_no=a.tenant_no AND i.instance_no=a.instance_no
          WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.status='COMMITTED' AND o.deleted=0 AND a.deleted=0
            AND i.entity_no=#{entity} AND i.kind='ORDER_COUPON' AND a.units>COALESCE(a.reversed_units,0) AND i.grant_no IN
            <foreach collection="grants" item="g" open="(" close=")" separator=",">#{g}</foreach>
        ) f
        </script>
        """)
    Stats usage(@Param("tenant") String tenant,@Param("entity") String entity,@Param("grants") List<String> grants);
}
