package ai.neargo.shop.pay.mapper.entitlement;
import org.apache.ibatis.annotations.*;
public interface LegacyCouponTemplateStatsMapper {
    record Stats(long usedTimes,long spentMinor,long usedCouponCount) { }
    @Select("""
        SELECT COALESCE(SUM(f.units),0) AS usedTimes,COALESCE(SUM(f.amount_minor),0) AS spentMinor,COUNT(DISTINCT f.instance_key) AS usedCouponCount FROM (
          SELECT i.instance_no AS instance_key,1 AS units,o.discount_minor AS amount_minor
          FROM ent_legacy_coupon_operation o JOIN ent_instance i ON i.tenant_no=o.tenant_no AND i.instance_no=o.instance_no
            JOIN ent_template_version v ON v.tenant_no=i.tenant_no AND v.version_no=i.template_version_no
          WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.status='COMMITTED' AND o.deleted=0 AND v.template_no=#{template}
          UNION ALL
          SELECT COALESCE(u.legacy_instance_no,CONCAT(h.import_no,CONCAT(':',h.source_instance_no))) AS instance_key,u.units,u.amount_minor
          FROM ent_usage u JOIN ent_migration_usage h ON h.tenant_no=u.tenant_no AND h.usage_no=u.usage_no
          WHERE u.tenant_no=#{tenant} AND u.entity_no=#{entity} AND u.protocol='MIGRATED' AND u.status='COMMITTED' AND u.deleted=0
            AND EXISTS (SELECT 1 FROM ent_migration_import m JOIN ent_migration_plan p ON p.tenant_no=m.tenant_no AND p.plan_no=m.plan_no
              JOIN ent_owner_registry r ON r.tenant_no=p.tenant_no AND r.source_system=p.source_system AND r.source_kind='TEMPLATE'
                AND r.source_no=p.source_template_no AND r.migration_batch_no=p.migration_batch_no AND r.cutover_watermark=p.plan_hash
              WHERE m.tenant_no=u.tenant_no AND m.import_no=u.migration_import_no AND r.owner_system='PAY' AND r.status='CUTOVER' AND r.target_no=#{template})
          UNION ALL
          SELECT i.instance_no AS instance_key,a.units-COALESCE(a.reversed_units,0) AS units,a.discount_minor-COALESCE(a.reversed_discount_minor,0) AS amount_minor
          FROM ent_use_allocation a JOIN ent_use_intent o ON o.tenant_no=a.tenant_no AND o.operation_no=a.operation_no
            JOIN ent_instance i ON i.tenant_no=a.tenant_no AND i.instance_no=a.instance_no
            JOIN ent_template_version v ON v.tenant_no=i.tenant_no AND v.version_no=i.template_version_no
          WHERE o.tenant_no=#{tenant} AND o.entity_no=#{entity} AND o.status='COMMITTED' AND o.deleted=0 AND a.deleted=0
            AND i.kind='ORDER_COUPON' AND a.units>COALESCE(a.reversed_units,0) AND v.template_no=#{template}
        ) f
        """)
    Stats usage(@Param("tenant") String tenant,@Param("entity") String entity,@Param("template") String template);
}
