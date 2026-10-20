package ai.neargo.shop.paybridge.impl;

import ai.neargo.shop.archive.ArchiveService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.coupon.CouponService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.Reference;
import ai.neargo.shop.payclient.LegacyCouponTemplateAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponSourceCommitPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Financial template archives follow the same permanent owner as issuance. */
@Primary @Service
public class LegacyCouponArchiveCompatibilityServiceImpl implements ArchiveService {
    private final ArchiveService legacy;
    private final CouponService coupons;
    private final LegacyCouponSourceCommitPort decisions;
    private final EntitlementOwnerRegistryPort owners;
    private final LegacyCouponTemplateAppService templates;
    public LegacyCouponArchiveCompatibilityServiceImpl(@Qualifier("archiveServiceImpl") ArchiveService legacy,
            @Qualifier("couponServiceImpl") CouponService coupons,LegacyCouponSourceCommitPort decisions,
            EntitlementOwnerRegistryPort owners,LegacyCouponTemplateAppService templates) {
        this.legacy=legacy;this.coupons=coupons;this.decisions=decisions;this.owners=owners;this.templates=templates;
    }
    private java.time.Instant change(Kind kind,String no,String operator,boolean archived) {
        if(kind!=Kind.COUPON) return null;
        var ctx=new EntitlementContext("MAIN",operator,"LEGACY_ARCHIVE","0".repeat(64));
        var source=new SourceIdentity(SourceSystem.MKT_LEGACY,SourceKind.TEMPLATE,no);var decided=decisions.decided(ctx,source);
        if(decided==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(decided.isEmpty()) return null;
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw BizException.of(ErrorCode.CONFLICT);
        var old=coupons.opsCoupons(null,true).stream().filter(v->no.equals(v.couponNo())).findFirst().orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
        var owner=owners.resolve(ctx,source);var fact=decided.get();
        if(!source.equals(owner.source()) || !source.equals(fact.template()) || owner.ownerSystem()!=OwnerSystem.PAY || owner.status()!=MigrationState.CUTOVER
                || !fact.migrationBatchNo().equals(owner.migrationBatchNo()) || !fact.planHash().equals(owner.cutoverWatermark())) throw BizException.of(ErrorCode.CONFLICT);
        var s=templates.archive(new CatalogScope("MAIN",old.merchantNo(),operator,true),new Reference(source,owner.targetNo(),owner.migrationBatchNo(),owner.cutoverWatermark()),archived,"LA"+UUID.randomUUID());
        return archived?s.archivedAt():java.time.Instant.EPOCH;
    }
    public long archive(Kind kind,String no,String operator) {var at=change(kind,no,operator,true);return at==null?legacy.archive(kind,no,operator):at.toEpochMilli();}
    public void unarchive(Kind kind,String no,String operator) {if(change(kind,no,operator,false)==null) legacy.unarchive(kind,no,operator);}
}
