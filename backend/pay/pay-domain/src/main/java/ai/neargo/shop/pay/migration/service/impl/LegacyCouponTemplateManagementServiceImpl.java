package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.*;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.*;
import ai.neargo.shop.pay.mapper.entitlement.LegacyCouponTemplateStatsMapper;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
import ai.neargo.shop.pay.migration.service.*;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class LegacyCouponTemplateManagementServiceImpl implements LegacyCouponTemplateManagementService {
    private final EntitlementOwnerRegistryService owners;
    private final EntitlementCatalogService catalog;
    private final EntitlementIssuePolicyService policies;
    private final EntitlementCommandDeduplicator commands;
    private final LegacyCouponTemplateStatsMapper stats;
    private final ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper heads;
    private final ai.neargo.shop.pay.mapper.entitlement.EntIssueRecipientMapper recipients;
    private final ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator rules=new ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator();
    public LegacyCouponTemplateManagementServiceImpl(EntitlementOwnerRegistryService owners,EntitlementCatalogService catalog,
            EntitlementIssuePolicyService policies,EntitlementCommandDeduplicator commands,LegacyCouponTemplateStatsMapper stats,ai.neargo.shop.pay.mapper.entitlement.EntIssueRecipientMapper recipients,ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper heads) {
        this.owners=owners;this.catalog=catalog;this.policies=policies;this.commands=commands;this.stats=stats;this.recipients=recipients;this.heads=heads;
    }
    @Transactional(value="payTxManager",readOnly=true)
    public Snapshot get(CatalogScope scope,Reference ref) {verify(scope,ref);return snapshot(scope,ref.targetNo());}
    @Transactional("payTxManager")
    public Snapshot status(CatalogScope scope,Reference ref,String status,String key) {
        verify(scope,ref);
        if(!java.util.Set.of("ACTIVE","PAUSED","ENDED").contains(status)) throw BizException.of(ErrorCode.BAD_REQUEST);
        return commands.execute(scope,"LEGACY_TEMPLATE_STATUS",key,java.util.List.of(ref,status),ref.targetNo(),Snapshot.class,()->{
            var head=catalog.get(scope,ref.targetNo());var policy=policies.get(scope,ref.targetNo());
            if(!status.equals(head.status())) catalog.changeStatus(scope,ref.targetNo(),head.revision(),status,child(key,"HEAD"));
            if(policy.enabled()!=status.equals("ACTIVE")) policies.save(scope,ref.targetNo(),new SavePolicy(policy.revision(),policy.limitCount(),
                    policy.maxExposureMinor(),policy.perUserLimit(),status.equals("ACTIVE")),child(key,"POLICY"));
            return snapshot(scope,ref.targetNo());
        });
    }
    @Transactional("payTxManager")
    public Snapshot save(CatalogScope scope,Reference ref,DefinitionChange change,String key) {
        verify(scope,ref);var d=change.definition();rules.validate(d);
        if(!"ORDER_COUPON".equals(d.kind()) || !"MERCHANT".equals(d.funder()) || !"CNY".equals(d.currency())
                || !"COUNT".equals(d.usageModel()) || !"ALL".equals(d.redemptionScope().mode())
                || !java.util.Set.of("CASH","PERCENT").contains(d.benefitMode()) || (d.minQty()!=null && d.minQty()>1))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(d.totalUnits()==null || d.totalUnits()<=0 || ("PERCENT".equals(d.benefitMode()) && d.benefitCapMinor()==null)
                || (d.allowedIssueModes().contains("CENTER") && change.limitCount()==null)) throw BizException.of(ErrorCode.BAD_REQUEST);
        long unit="CASH".equals(d.benefitMode())?java.util.Objects.requireNonNull(d.benefitValue()):d.benefitCapMinor();
        if(change.budgetMinor()!=null && change.limitCount()!=null && java.math.BigInteger.valueOf(unit)
                .multiply(java.math.BigInteger.valueOf(d.totalUnits())).multiply(java.math.BigInteger.valueOf(change.limitCount()))
                .compareTo(java.math.BigInteger.valueOf(change.budgetMinor()))>0) throw BizException.of(ErrorCode.COUPON_BUDGET_EXCEEDED);
        return commands.execute(scope,"LEGACY_TEMPLATE_SAVE",key,java.util.List.of(ref,change),ref.targetNo(),Snapshot.class,()->{
            var head=catalog.get(scope,ref.targetNo());var policy=policies.get(scope,ref.targetNo());
            if(!d.equals(head.draft()) || !d.equals(catalog.getVersion(scope,head.currentVersionNo()).definition())) {
                var draft=catalog.saveDraft(scope,ref.targetNo(),head.revision(),d,child(key,"DRAFT"));
                catalog.publish(scope,ref.targetNo(),draft.revision(),child(key,"PUBLISH"));
                if("PAUSED".equals(head.status())) {
                    var published=catalog.get(scope,ref.targetNo());
                    catalog.changeStatus(scope,ref.targetNo(),published.revision(),"PAUSED",child(key,"PRESERVE_PAUSE"));
                }
            }
            policies.save(scope,ref.targetNo(),new SavePolicy(policy.revision(),change.limitCount(),change.budgetMinor(),
                    change.perUserLimit(),policy.enabled()),child(key,"POLICY"));
            return snapshot(scope,ref.targetNo());
        });
    }
    @Transactional("payTxManager")
    public Snapshot archive(CatalogScope scope,Reference ref,boolean archived,String key) {
        if(scope==null || !scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
        verify(scope,ref);
        return commands.execute(scope,"LEGACY_TEMPLATE_ARCHIVE",key,java.util.List.of(ref,archived),ref.targetNo(),Snapshot.class,()->{
            var head=heads.lockOwned(scope.tenantNo(),scope.entityNo(),ref.targetNo());if(head==null) throw BizException.of(ErrorCode.NOT_FOUND);
            if(archived!=(head.getArchivedAt()!=null)) {
                var now=EntitlementRecords.now();
                EntitlementRecords.changed(heads.archive(scope.tenantNo(),scope.entityNo(),ref.targetNo(),head.getVersion(),archived?now:null,scope.actorNo(),now));
            }
            return snapshot(scope,ref.targetNo());
        });
    }
    private Snapshot snapshot(CatalogScope scope,String no) {
        var head=catalog.get(scope,no);var policy=policies.get(scope,no);var usage=stats.usage(scope.tenantNo(),scope.entityNo(),no);
        var definition=catalog.getVersion(scope,head.currentVersionNo()).definition();
        var recipient=recipients.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<ai.neargo.shop.pay.entitlement.entity.EntIssueRecipient>lambdaQuery()
                .eq(ai.neargo.shop.pay.entitlement.entity.EntIssueRecipient::getTenantNo,scope.tenantNo())
                .eq(ai.neargo.shop.pay.entitlement.entity.EntIssueRecipient::getTemplateNo,no)
                .eq(ai.neargo.shop.pay.entitlement.entity.EntIssueRecipient::getUserNo,scope.actorNo()));
        return new Snapshot(new ManagedTemplateView(no,head.status(),head.revision(),head.currentVersionNo(),head.draft(),policy),definition,usage.usedTimes(),usage.spentMinor(),recipient==null?0:recipient.getHeldCount(),recipient==null?0:recipient.getIssuedCount(),EntitlementRecords.instant(heads.findOwned(scope.tenantNo(),scope.entityNo(),no).getArchivedAt()),usage.usedCouponCount());
    }
    private void verify(CatalogScope scope,Reference ref) {
        if(scope==null || ref==null) throw BizException.of(ErrorCode.BAD_REQUEST);
        if(!"MAIN".equals(scope.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var owner=owners.resolve(new ai.neargo.shop.spi.entitlement.EntitlementContext(scope.tenantNo(),scope.actorNo(),"LEGACY_TEMPLATE","0".repeat(64)),ref.source());
        if(owner.ownerSystem()!=OwnerSystem.PAY || owner.status()!=MigrationState.CUTOVER || !ref.targetNo().equals(owner.targetNo())
                || !ref.batchNo().equals(owner.migrationBatchNo()) || !ref.planHash().equals(owner.cutoverWatermark())) throw BizException.of(ErrorCode.CONFLICT);
        catalog.get(scope,ref.targetNo());
    }
    private static String child(String key,String suffix) {return EntitlementRecords.digest(key+":"+suffix);}
}
