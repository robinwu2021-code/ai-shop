package ai.neargo.shop.paybridge.mapper;
import ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort;


import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.promotion.migration.LegacyCouponInventoryRow;
import java.util.List;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Include archived/deleted roots so an original release cannot bypass an existing source fence. */
@Mapper
public interface LegacyCouponFenceMapper {
    @Select("""
        SELECT c.entity_no,c.coupon_no AS template_no FROM pmt_coupon c JOIN pmt_migration_source s
          ON s.tenant_no=c.tenant_no AND s.source_system='PMT' AND s.source_template_no=c.coupon_no AND s.deleted=0
        JOIN pmt_migration_decision d ON d.tenant_no=s.tenant_no AND d.decision_no=s.decision_no AND d.deleted=0
        WHERE c.tenant_no=#{tenant} AND c.deleted=0 AND c.funder='MERCHANT' AND c.migration_state='COMMIT_DECIDED'
        ORDER BY c.coupon_no
        """)
    List<LegacyCouponIdentityPort.TemplateReference> committedPromoTemplates(@Param("tenant") String tenant);
    @Select("""
        SELECT c.entity_no,c.coupon_no AS template_no FROM mkt_coupon c JOIN pmt_migration_source s
          ON s.tenant_no=c.tenant_no AND s.source_system='MKT_LEGACY' AND s.source_template_no=c.coupon_no AND s.deleted=0
        JOIN pmt_migration_decision d ON d.tenant_no=s.tenant_no AND d.decision_no=s.decision_no AND d.deleted=0
        WHERE c.tenant_no=#{tenant} AND c.deleted=0 AND c.funder='MERCHANT' AND c.migration_state='COMMIT_DECIDED'
        ORDER BY c.coupon_no
        """)
    List<LegacyCouponIdentityPort.TemplateReference> committedLegacyTemplates(@Param("tenant") String tenant);
    @Select("""
        SELECT (SELECT COUNT(*) FROM mkt_fission_campaign f WHERE f.tenant_no=#{tenant} AND f.coupon_no=#{no} AND f.deleted=0)
          +(SELECT COUNT(*) FROM mkt_campaign c WHERE c.tenant_no=#{tenant} AND CONCAT('CU@',c.campaign_no)=#{no} AND c.type='COUPON' AND c.deleted=0)
        """)
    long legacyProgramReferences(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM pmt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no}")
    PmtCoupon readPromo(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM mkt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no}")
    MktCoupon readLegacy(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT * FROM mkt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    MktCoupon lockLegacy(@Param("tenant") String tenant, @Param("no") String no);
    @Select("SELECT * FROM pmt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    PmtCoupon lockPromo(@Param("tenant") String tenant, @Param("no") String no);


    @Select("SELECT COUNT(*) FROM pmt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no}")
    long peerPromoTemplates(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT COUNT(*) FROM mkt_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no}")
    long peerLegacyTemplates(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT COUNT(*) FROM mkt_user_coupon u WHERE u.tenant_no=#{tenant} AND u.coupon_no=#{no} "
            + "AND EXISTS(SELECT 1 FROM pmt_user_coupon p WHERE p.tenant_no=u.tenant_no AND p.user_coupon_no=u.user_coupon_no)")
    long peerPromoInstances(@Param("tenant") String tenant,@Param("no") String no);
    @Select("SELECT COUNT(*) FROM pmt_user_coupon u WHERE u.tenant_no=#{tenant} AND u.coupon_no=#{no} "
            + "AND EXISTS(SELECT 1 FROM mkt_user_coupon p WHERE p.tenant_no=u.tenant_no AND p.user_coupon_no=u.user_coupon_no)")
    long peerLegacyInstances(@Param("tenant") String tenant,@Param("no") String no);


    @Select("""
            SELECT u.user_coupon_no AS instance_no,u.coupon_no AS template_no,c.entity_no,u.entity_no AS instance_entity_no,
            c.funder,c.migration_state AS root_state,c.migration_batch_no,
            CASE WHEN c.id IS NULL THEN 0 ELSE 1 END AS root_present,u.deleted AS instance_deleted,c.deleted AS root_deleted
            FROM pmt_user_coupon u LEFT JOIN pmt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no
            WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.user_coupon_no=#{no}
            """)
    ai.neargo.shop.promotion.migration.LegacyCouponOriginRow promoOrigin(@Param("tenant") String tenant,
            @Param("user") String user,@Param("no") String no);
    @Select("""
            SELECT u.user_coupon_no AS instance_no,u.coupon_no AS template_no,c.entity_no,NULL AS instance_entity_no,
            c.funder,c.migration_state AS root_state,c.migration_batch_no,
            CASE WHEN c.id IS NULL THEN 0 ELSE 1 END AS root_present,u.deleted AS instance_deleted,c.deleted AS root_deleted
            FROM mkt_user_coupon u LEFT JOIN mkt_coupon c ON c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no
            WHERE u.tenant_no=#{tenant} AND u.user_no=#{user} AND u.user_coupon_no=#{no}
            """)
    ai.neargo.shop.promotion.migration.LegacyCouponOriginRow legacyOrigin(@Param("tenant") String tenant,
            @Param("user") String user,@Param("no") String no);

    // Inventory deliberately includes deleted/archived facts. Original write barriers stay above.
    @Select("""
            SELECT c.id,
            c.coupon_no AS template_no,
            c.entity_no,
            c.deleted,
            c.archived_at,
            c.migration_state,
            (SELECT COUNT(*) FROM mkt_user_coupon u WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no) AS instance_count,
            (SELECT COUNT(*) FROM mkt_user_coupon u WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no AND u.deleted=1) AS deleted_instance_count,
            (SELECT COUNT(*) FROM mkt_coupon_issue i WHERE i.tenant_no=c.tenant_no AND i.coupon_no=c.coupon_no) AS issue_count,
            (SELECT COUNT(*) FROM mkt_coupon_issue i WHERE i.tenant_no=c.tenant_no AND i.coupon_no=c.coupon_no AND i.deleted=1) AS deleted_issue_count,
            NULL AS history_count,
            NULL AS deleted_history_count,
            (SELECT COUNT(*) FROM pmt_coupon p WHERE p.tenant_no=c.tenant_no AND p.coupon_no=c.coupon_no) AS peer_template_count
            FROM mkt_coupon c WHERE c.tenant_no=#{tenant} AND c.id>#{afterId} ORDER BY c.id LIMIT #{limit}
            """)
    List<LegacyCouponInventoryRow> inventoryLegacy(@Param("tenant") String tenant,@Param("afterId") long afterId,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM mkt_user_coupon u WHERE u.tenant_no=#{tenant} AND NOT EXISTS(SELECT 1 FROM mkt_coupon c WHERE c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no)")
    long orphanLegacyInstances(@Param("tenant") String tenant);
    @Select("SELECT COUNT(*) FROM mkt_coupon_issue u WHERE u.tenant_no=#{tenant} AND NOT EXISTS(SELECT 1 FROM mkt_coupon c WHERE c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no)")
    long orphanLegacyIssues(@Param("tenant") String tenant);
    @Select("""
            SELECT c.id,
            c.coupon_no AS template_no,
            c.entity_no,
            c.deleted,
            c.archived_at,
            c.migration_state,
            (SELECT COUNT(*) FROM pmt_user_coupon u WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no) AS instance_count,
            (SELECT COUNT(*) FROM pmt_user_coupon u WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no AND u.deleted=1) AS deleted_instance_count,
            (SELECT COUNT(*) FROM pmt_coupon_issue i WHERE i.tenant_no=c.tenant_no AND i.coupon_no=c.coupon_no) AS issue_count,
            (SELECT COUNT(*) FROM pmt_coupon_issue i WHERE i.tenant_no=c.tenant_no AND i.coupon_no=c.coupon_no AND i.deleted=1) AS deleted_issue_count,
            (SELECT COUNT(*) FROM pmt_apply a JOIN pmt_user_coupon u ON u.tenant_no=a.tenant_no AND u.user_coupon_no=a.promo_no WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no AND a.promo_type='COUPON') AS history_count,
            (SELECT COUNT(*) FROM pmt_apply a JOIN pmt_user_coupon u ON u.tenant_no=a.tenant_no AND u.user_coupon_no=a.promo_no WHERE u.tenant_no=c.tenant_no AND u.coupon_no=c.coupon_no AND a.promo_type='COUPON' AND a.deleted=1) AS deleted_history_count,
            (SELECT COUNT(*) FROM mkt_coupon p WHERE p.tenant_no=c.tenant_no AND p.coupon_no=c.coupon_no) AS peer_template_count
            FROM pmt_coupon c WHERE c.tenant_no=#{tenant} AND c.id>#{afterId} ORDER BY c.id LIMIT #{limit}
            """)
    List<LegacyCouponInventoryRow> inventoryPromo(@Param("tenant") String tenant,@Param("afterId") long afterId,@Param("limit") int limit);
    @Select("SELECT COUNT(*) FROM pmt_user_coupon u WHERE u.tenant_no=#{tenant} AND NOT EXISTS(SELECT 1 FROM pmt_coupon c WHERE c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no)")
    long orphanPromoInstances(@Param("tenant") String tenant);
    @Select("SELECT COUNT(*) FROM pmt_coupon_issue u WHERE u.tenant_no=#{tenant} AND NOT EXISTS(SELECT 1 FROM pmt_coupon c WHERE c.tenant_no=u.tenant_no AND c.coupon_no=u.coupon_no)")
    long orphanPromoIssues(@Param("tenant") String tenant);
    @Select("SELECT COUNT(*) FROM pmt_apply a WHERE a.tenant_no=#{tenant} AND a.promo_type='COUPON' "
            + "AND NOT EXISTS(SELECT 1 FROM pmt_user_coupon u JOIN pmt_coupon c ON c.tenant_no=u.tenant_no "
            + "AND c.coupon_no=u.coupon_no WHERE u.tenant_no=a.tenant_no AND u.user_coupon_no=a.promo_no)")
    long orphanPromoHistory(@Param("tenant") String tenant);

    // Full family export preserves deletion markers rather than relying on BaseMapper filtering.
    @Select("SELECT * FROM mkt_user_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no} ORDER BY id LIMIT #{limit}")
    List<ai.neargo.shop.marketing.coupon.entity.MktUserCoupon> manifestLegacyInstances(@Param("tenant") String tenant,@Param("no") String no,@Param("limit") int limit);

    @Select("SELECT * FROM pmt_user_coupon WHERE tenant_no=#{tenant} AND coupon_no=#{no} ORDER BY id LIMIT #{limit}")
    List<ai.neargo.shop.promotion.entity.PmtUserCoupon> manifestPromoInstances(@Param("tenant") String tenant,@Param("no") String no,@Param("limit") int limit);

    @Select("SELECT * FROM mkt_coupon_issue WHERE tenant_no=#{tenant} AND coupon_no=#{no} ORDER BY id LIMIT #{limit}")
    List<ai.neargo.shop.marketing.coupon.entity.MktCouponIssue> manifestLegacyIssues(@Param("tenant") String tenant,@Param("no") String no,@Param("limit") int limit);

    @Select("SELECT * FROM pmt_coupon_issue WHERE tenant_no=#{tenant} AND coupon_no=#{no} ORDER BY id LIMIT #{limit}")
    List<ai.neargo.shop.promotion.entity.PmtCouponIssue> manifestPromoIssues(@Param("tenant") String tenant,@Param("no") String no,@Param("limit") int limit);

    @Select("SELECT * FROM pmt_coupon_scope WHERE tenant_no=#{tenant} AND coupon_no=#{no} ORDER BY id LIMIT #{limit}")
    List<ai.neargo.shop.promotion.entity.PmtCouponScope> manifestPromoScopes(@Param("tenant") String tenant,@Param("no") String no,@Param("limit") int limit);

    @Select("SELECT a.* FROM pmt_apply a JOIN pmt_user_coupon u ON u.tenant_no=a.tenant_no AND u.user_coupon_no=a.promo_no "
            + "WHERE a.tenant_no=#{tenant} AND u.coupon_no=#{no} AND a.promo_type='COUPON' ORDER BY a.id LIMIT #{limit}")
    List<ai.neargo.shop.promotion.entity.PmtApply> manifestPromoHistory(@Param("tenant") String tenant,
            @Param("no") String no,@Param("limit") int limit);
}
