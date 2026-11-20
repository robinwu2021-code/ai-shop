package ai.neargo.shop.paybridge.impl;
import ai.neargo.shop.spi.entitlement.ContractChecks;


import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.entity.MktCoupon;
import ai.neargo.shop.marketing.coupon.entity.MktUserCoupon;
import ai.neargo.shop.marketing.coupon.mapper.CouponMappers;
import ai.neargo.shop.promotion.entity.PmtApply;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.entity.PmtUserCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers;
import ai.neargo.shop.promotion.migration.LegacyCouponMigrationPreflightService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Source snapshots are read in one core transaction; no pay call, source freeze or owner switch occurs here. */
@Service
public class LegacyCouponMigrationPreflightServiceImpl implements LegacyCouponMigrationPreflightService {
    private final CouponMappers.CouponMapper legacyTemplates;
    private final CouponMappers.UserCouponMapper legacyInstances;
    private final PromotionMappers.CouponMapper promoTemplates;
    private final PromotionMappers.UserCouponMapper promoInstances;
    private final PromotionMappers.ApplyMapper applications;
    private final ai.neargo.shop.paybridge.mapper.LegacyCouponFenceMapper inventory;

    public LegacyCouponMigrationPreflightServiceImpl(CouponMappers.CouponMapper legacyTemplates,
            CouponMappers.UserCouponMapper legacyInstances, PromotionMappers.CouponMapper promoTemplates,
            PromotionMappers.UserCouponMapper promoInstances, PromotionMappers.ApplyMapper applications,
            ai.neargo.shop.paybridge.mapper.LegacyCouponFenceMapper inventory) {
        this.legacyTemplates = legacyTemplates; this.legacyInstances = legacyInstances;
        this.promoTemplates = promoTemplates; this.promoInstances = promoInstances; this.applications = applications; this.inventory=inventory;
    }
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report scan(EntitlementContext context, SourceSystem source, long afterId, int limit) {
        if (!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (source == null || source == SourceSystem.PAY || afterId < 0 || limit < 1 || limit > 100) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return DataScopeContext.executeWithoutScope(() -> source == SourceSystem.MKT_LEGACY
                ? legacy(context.tenantNo(), afterId, limit) : promo(context.tenantNo(), afterId, limit));
    }
    @Override
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public InventoryReport inventory(EntitlementContext context,SourceSystem source,long afterId,int limit) {
        ContractChecks.present(context);
        if(!"MAIN".equals(context.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(source==null || source==SourceSystem.PAY || afterId<0 || limit<1 || limit>100)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        return DataScopeContext.executeWithoutScope(() -> {
            String tenant=context.tenantNo();
            boolean old=source==SourceSystem.MKT_LEGACY;
            var rows=old?inventory.inventoryLegacy(tenant,afterId,limit+1):inventory.inventoryPromo(tenant,afterId,limit+1);
            var page=rows.stream().limit(limit).toList();
            var lines=page.stream().map(row -> {
                var blockers=baseline();
                if("FROZEN".equals(row.getMigrationState())) {
                    blockers.remove("SOURCE_FENCE_REQUIRED"); blockers.add("SOURCE_FENCE_REVALIDATION_REQUIRED");
                }
                if(Integer.valueOf(1).equals(row.getDeleted())) blockers.add("TEMPLATE_TOMBSTONE_REQUIRES_PRESERVATION");
                if(row.getArchivedAt()!=null) blockers.add("TEMPLATE_ARCHIVE_REQUIRES_PRESERVATION");
                if(row.getDeletedInstanceCount()>0) blockers.add("INSTANCE_TOMBSTONES_REQUIRE_PRESERVATION");
                if(row.getDeletedIssueCount()>0) blockers.add("ISSUE_TOMBSTONES_REQUIRE_PRESERVATION");
                if(row.getDeletedHistoryCount()!=null && row.getDeletedHistoryCount()>0)
                    blockers.add("HISTORY_TOMBSTONES_REQUIRE_PRESERVATION");
                if(row.getPeerTemplateCount()>0) blockers.add("SOURCE_OVERLAP_REQUIRES_CANONICAL_PLAN");
                if(old && row.getIssueCount()>0) blockers.add("INDIVIDUAL_ISSUE_LINK_UNAVAILABLE");
                return new TemplateInventory(source,row.getTemplateNo(),row.getEntityNo(),
                        Integer.valueOf(1).equals(row.getDeleted()),row.getArchivedAt()!=null,row.getMigrationState(),
                        row.getInstanceCount(),row.getDeletedInstanceCount(),row.getIssueCount(),row.getDeletedIssueCount(),
                        row.getHistoryCount(),row.getDeletedHistoryCount(),row.getPeerTemplateCount()>0,blockers);
            }).toList();
            long orphanInstances=old?inventory.orphanLegacyInstances(tenant):inventory.orphanPromoInstances(tenant);
            long orphanIssues=old?inventory.orphanLegacyIssues(tenant):inventory.orphanPromoIssues(tenant);
            Long orphanHistory=old?null:inventory.orphanPromoHistory(tenant);
            return new InventoryReport(Instant.now(),lines,rows.size()>limit?page.getLast().getId():0,
                    orphanInstances,orphanIssues,orphanHistory);
        });
    }
    private Report legacy(String tenant, long afterId, int limit) {
        var rows = legacyInstances.selectList(Wrappers.<MktUserCoupon>lambdaQuery()
                .eq(MktUserCoupon::getTenantNo, tenant).gt(MktUserCoupon::getId, afterId)
                .orderByAsc(MktUserCoupon::getId).last("LIMIT " + (limit + 1)));
        var page = rows.stream().limit(limit).toList();
        var lines = new ArrayList<Line>();
        for (var row : page) {
            var head = legacyTemplates.selectOne(Wrappers.<MktCoupon>lambdaQuery()
                    .eq(MktCoupon::getTenantNo, tenant).eq(MktCoupon::getCouponNo, row.getCouponNo()));
            var blockers = baseline();
            boolean peer = promoInstances.selectCount(Wrappers.<PmtUserCoupon>lambdaQuery()
                    .eq(PmtUserCoupon::getTenantNo, tenant).eq(PmtUserCoupon::getUserCouponNo, row.getUserCouponNo())) > 0;
            if (peer) blockers.add("CROSS_SOURCE_NUMBER");
            if (head == null) blockers.add("TEMPLATE_MISSING");
            else if (!"MERCHANT".equals(head.getFunder()) || head.getEntityNo() == null) {
                blockers.add("PLATFORM_OR_MISSING_FUNDER_COMPATIBILITY_REQUIRED");
            }
            if (!Set.of("UNUSED", "USED", "EXPIRED").contains(row.getStatus())) blockers.add("SOURCE_STATUS_UNSUPPORTED");
            int used = "USED".equals(row.getStatus()) ? 1 : 0;
            if (row.getOrderNo() != null) blockers.add("ORDER_COMPATIBILITY_REQUIRED");
            if (used > 0) blockers.add("LEGACY_ORDER_HISTORY_REQUIRED");
            var until = exclusive(head == null ? null : head.getEndAt(), blockers);
            lines.add(new Line(SourceSystem.MKT_LEGACY, row.getUserCouponNo(), row.getCouponNo(), row.getUserNo(),
                    head == null ? null : head.getEntityNo(), revision(row.getVersion()),
                    head == null ? null : head.getVersion(), row.getStatus(), 1, used, 1L - used, until,
                    0, false, blockers));
        }
        return new Report(Instant.now(), lines, rows.size() > limit ? page.getLast().getId() : 0);
    }
    private Report promo(String tenant, long afterId, int limit) {
        var rows = promoInstances.selectList(Wrappers.<PmtUserCoupon>lambdaQuery()
                .eq(PmtUserCoupon::getTenantNo, tenant).gt(PmtUserCoupon::getId, afterId)
                .orderByAsc(PmtUserCoupon::getId).last("LIMIT " + (limit + 1)));
        var page = rows.stream().limit(limit).toList();
        var lines = new ArrayList<Line>();
        for (var row : page) {
            var head = promoTemplates.selectOne(Wrappers.<PmtCoupon>lambdaQuery()
                    .eq(PmtCoupon::getTenantNo, tenant).eq(PmtCoupon::getCouponNo, row.getCouponNo()));
            var blockers = baseline();
            if (legacyInstances.selectCount(Wrappers.<MktUserCoupon>lambdaQuery()
                    .eq(MktUserCoupon::getTenantNo, tenant).eq(MktUserCoupon::getUserCouponNo, row.getUserCouponNo())) > 0) {
                blockers.add("CROSS_SOURCE_NUMBER");
            }
            if (head == null) blockers.add("TEMPLATE_MISSING");
            else if (!"MERCHANT".equals(head.getFunder()) || head.getEntityNo() == null) {
                blockers.add("PLATFORM_OR_MISSING_FUNDER_COMPATIBILITY_REQUIRED");
            }
            int total = head == null ? 1 : head.timesTotalOrOne();
            int used = row.getTimesUsed() == null ? 0 : row.getTimesUsed();
            if (used < 0 || used > total) blockers.add("INVALID_COUNTERS");
            if (head != null && !java.util.Objects.equals(head.getEntityNo(), row.getEntityNo())) {
                blockers.add("ENTITY_MISMATCH");
            }
            if (!Set.of("UNUSED", "USED", "EXPIRED", "REVOKED").contains(row.getStatus())) {
                blockers.add("SOURCE_STATUS_UNSUPPORTED");
            }
            if (("USED".equals(row.getStatus()) && used != total)
                    || ("UNUSED".equals(row.getStatus()) && used >= total)) blockers.add("STATUS_COUNTER_MISMATCH");
            long history = applications.selectCount(Wrappers.<PmtApply>lambdaQuery()
                    .eq(PmtApply::getTenantNo, tenant).eq(PmtApply::getPromoType, "COUPON")
                    .eq(PmtApply::getPromoNo, row.getUserCouponNo()).isNull(PmtApply::getRevertedAt));
            if (history != used) blockers.add("HISTORY_COUNTER_MISMATCH");
            boolean code = row.getRedeemCode() != null && !row.getRedeemCode().isBlank();
            if (code) blockers.add("LEGACY_CODE_COMPATIBILITY_REQUIRED");
            if (row.getOrderNo() != null) blockers.add("ORDER_COMPATIBILITY_REQUIRED");
            var until = exclusive(row.getExpireAt(), blockers);
            lines.add(new Line(SourceSystem.PMT, row.getUserCouponNo(), row.getCouponNo(), row.getUserNo(),
                    row.getEntityNo(), revision(row.getVersion()), head == null ? null : head.getVersion(),
                    row.getStatus(), total, used, (long) total - used, until, history, code, blockers));
        }
        return new Report(Instant.now(), lines, rows.size() > limit ? page.getLast().getId() : 0);
    }
    private static long revision(Long value) { return value == null ? 0 : value; }
    private static List<String> baseline() {
        return new ArrayList<>(List.of("SOURCE_FENCE_REQUIRED", "LEGACY_WRITE_ADAPTER_REQUIRED"));
    }
    private static Instant exclusive(Long inclusiveMillis, List<String> blockers) {
        if (inclusiveMillis == null) { blockers.add("MISSING_EFFECTIVE_UNTIL"); return null; }
        return Instant.ofEpochMilli(inclusiveMillis).plusMillis(1);
    }
}
