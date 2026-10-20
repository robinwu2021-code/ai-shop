package ai.neargo.shop.promotion.mapper;
import ai.neargo.shop.promotion.migration.PmtPayCouponProjection;
import ai.neargo.shop.promotion.migration.LegacyCouponOriginRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
public interface PmtPayCouponProjectionMapper extends BaseMapper<PmtPayCouponProjection> {
    @Insert("""
        INSERT INTO pmt_pay_coupon_projection(tenant_no,instance_no,user_no,entity_no,source_system,source_template_no,
          target_template_no,template_version_no,migration_batch_no,plan_hash,issue_no,grant_no,actor_no,fact_hash,occurred_at,
          version,deleted,created_at,updated_at,created_by,updated_by)
        VALUES(#{tenantNo},#{instanceNo},#{userNo},#{entityNo},#{sourceSystem},#{sourceTemplateNo},#{targetTemplateNo},
          #{templateVersionNo},#{migrationBatchNo},#{planHash},#{issueNo},#{grantNo},#{actorNo},#{factHash},#{occurredAt},
          0,0,#{createdAt},#{updatedAt},#{createdBy},#{updatedBy}) ON DUPLICATE KEY UPDATE id=id
        """)
    int claim(PmtPayCouponProjection row);
    @Select("SELECT * FROM pmt_pay_coupon_projection WHERE tenant_no=#{tenant} AND instance_no=#{no} FOR UPDATE")
    @Options(flushCache=Options.FlushCachePolicy.TRUE,useCache=false)
    PmtPayCouponProjection lock(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT COUNT(*) FROM pmt_user_coupon WHERE tenant_no=#{tenant} AND user_coupon_no=#{no}")
    long originalPromo(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT COUNT(*) FROM mkt_user_coupon WHERE tenant_no=#{tenant} AND user_coupon_no=#{no}")
    long originalLegacy(@Param("tenant") String tenant,@Param("no") String no);
    @Select("""
        SELECT u.instance_no,u.source_template_no AS template_no,c.entity_no,u.entity_no AS instance_entity_no,
          c.funder,c.migration_state AS root_state,c.migration_batch_no,
          CASE WHEN c.id IS NULL THEN 0 ELSE 1 END AS root_present,u.deleted AS instance_deleted,c.deleted AS root_deleted
        FROM pmt_pay_coupon_projection u LEFT JOIN pmt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.source_template_no
        WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.instance_no=#{no} AND u.source_system='PMT'
        """)
    LegacyCouponOriginRow promoOrigin(@Param("tenant") String tenant,@Param("user") String user,@Param("no") String no);
    @Select("""
        SELECT u.instance_no,u.source_template_no AS template_no,c.entity_no,u.entity_no AS instance_entity_no,
          c.funder,c.migration_state AS root_state,c.migration_batch_no,
          CASE WHEN c.id IS NULL THEN 0 ELSE 1 END AS root_present,u.deleted AS instance_deleted,c.deleted AS root_deleted
        FROM pmt_pay_coupon_projection u LEFT JOIN mkt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.source_template_no
        WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.instance_no=#{no} AND u.source_system='MKT_LEGACY'
        """)
    LegacyCouponOriginRow legacyOrigin(@Param("tenant") String tenant,@Param("user") String user,@Param("no") String no);
}
