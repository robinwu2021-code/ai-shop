package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.storedvalue.entity.*;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pay-local legacy PLAN adapter. Never resolves Core authority or calls payment channels under money locks. */
@Service
public class StoredValueIssuerLedgerBridge {
    private final IssuerRepository db;
    private final StoredValueIssueQuotaService quotas;
    private final boolean enabled;
    private final ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator commands;
    public record Consumption(String voucherNo,long amountMinor) { }
    public StoredValueIssuerLedgerBridge(IssuerRepository db,StoredValueIssueQuotaService quotas,ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator commands,
            @Value("${shop.pay.stored-value.issuer-mode:false}") boolean enabled) {
        this.db=db;this.quotas=quotas;this.commands=commands;this.enabled=enabled;
    }

    /** Issuer precedes an existing plan lock, matching migration issuer -> account/plan ordering. */
    public void beforePlanOriginal(EntitlementContext ctx,String entity,String store) {
        if(!enabled)return;
        transaction();
        var issuer=db.one("SELECT i.write_mode,i.status,v.settlement_entity_no FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_no=i.issuer_no AND v.issuer_version_no=i.current_issuer_version_no WHERE i.tenant_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0 FOR UPDATE",ctx.tenantNo(),store);
        if(issuer==null||!"ISSUER".equals(text(issuer,"write_mode"))||!"ACTIVE".equals(text(issuer,"status"))||!entity.equals(text(issuer,"settlement_entity_no")))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }

    /** Called only for a newly inserted original, within its owning Pay transaction. */
    public void freezePlan(EntitlementContext ctx,SvRechargePlan plan,SvRechargePlanVersion version,SvRecharge original) {
        if(!enabled)return;
        transaction();
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),plan.getStoreNo());
        if(issuer==null||!"ISSUER".equals(text(issuer,"write_mode"))||!"ACTIVE".equals(text(issuer,"status")))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var current=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),text(issuer,"current_issuer_version_no"));
        if(current==null||!plan.getEntityNo().equals(text(current,"settlement_entity_no"))
                ||!plan.getCurrency().equals(text(current,"currency")))throw BizException.of(ErrorCode.CONFLICT);
        var scope=db.decode(version.getScopeSnapshot(),RedemptionScope.class);
        if(!"STORES".equals(scope.mode())||!List.of(plan.getStoreNo()).equals(scope.refs()))throw BizException.of(ErrorCode.CONFLICT);
        var release=db.one("SELECT * FROM sv_release WHERE tenant_no=? AND legacy_plan_version_no=? AND deleted=0",ctx.tenantNo(),version.getVersionNo());
        var at=Timestamp.from(Instant.now());
        if(release==null) {
            release=base(ctx,at);release.put("release_no",BizKey.next(BizKey.STORED_VALUE_RELEASE));
            release.put("issuer_no",text(issuer,"issuer_no"));release.put("issuer_version_no",text(current,"issuer_version_no"));
            release.put("release_index",version.getVersionIndex());release.put("source_kind","LEGACY_PLAN");
            release.put("legacy_plan_version_no",version.getVersionNo());release.put("currency",plan.getCurrency());
            release.put("starts_at",at);release.put("state","ACTIVE");release.put("redemption_scope_mode","STORE_ONLY");
            release.put("scope_snapshot",version.getScopeSnapshot());release.put("policy_versions","[]");release.put("content_hash",version.getSnapshotHash());
            db.insert("sv_release",release);
            var tier=base(ctx,at);tier.put("tier_no",BizKey.next(BizKey.STORED_VALUE_RELEASE_TIER));tier.put("release_no",text(release,"release_no"));
            tier.put("ordinal",1);tier.put("paid_amount_minor",version.getPaidAmountMinor());tier.put("bonus_amount_minor",version.getBonusAmountMinor());db.insert("sv_release_tier",tier);
        }
        var tier=db.one("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND deleted=0",ctx.tenantNo(),text(release,"release_no"));
        var frozen=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),text(release,"issuer_version_no"));
        if(!text(issuer,"issuer_no").equals(text(release,"issuer_no"))||!"ACTIVE".equals(text(release,"state"))
                ||!plan.getCurrency().equals(text(release,"currency"))||tier==null||frozen==null
                ||!plan.getEntityNo().equals(text(frozen,"settlement_entity_no"))
                ||number(tier,"paid_amount_minor")!=original.getPrincipalMinor()||number(tier,"bonus_amount_minor")!=original.getBonusMinor())
            throw BizException.of(ErrorCode.CONFLICT);
        changed(db.update("UPDATE sv_recharge SET issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND recharge_no=? AND issuer_no IS NULL",
                text(issuer,"issuer_no"),text(release,"issuer_version_no"),text(release,"release_no"),text(tier,"tier_no"),plan.getEntityNo(),text(frozen,"settlement_entity_no"),ctx.tenantNo(),original.getRechargeNo()));
        quotas.reserve(ctx,original.getRechargeNo());
    }

    /** Legacy activity creation is not an issuer release. Switched/fenced stores cannot create untracked promises. */
    public void beforeLegacyActivityCreate(EntitlementContext ctx,String entity,String store) {
        if(!enabled)return;
        transaction();
        var issuer=db.one("SELECT i.write_mode,v.settlement_entity_no FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_no=i.issuer_no AND v.issuer_version_no=i.current_issuer_version_no WHERE i.tenant_no=? AND i.scope_type='STORE' AND i.scope_no=? AND i.deleted=0 AND v.deleted=0 FOR UPDATE",ctx.tenantNo(),store);
        if(issuer!=null&&(!entity.equals(text(issuer,"settlement_entity_no"))||!("ISSUER".equals(text(issuer,"write_mode"))||Set.of("LEGACY","LEGACY_MAPPED").contains(text(issuer,"write_mode")))))
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
    }


    /** Freeze a Core-authorized single-store activity version and tier; never infer a current activity or merge identities. */
    public void freezeActivity(EntitlementContext ctx,String entity,ai.neargo.shop.spi.storedvalue.RechargeSourcePort.SourceFact source,SvRecharge original) {
        if(!enabled)return;
        transaction();var permit=source.permit();
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),permit.storeNo());
        if(issuer==null||Set.of("LEGACY","LEGACY_MAPPED").contains(text(issuer,"write_mode")))return;
        if(!"ISSUER".equals(text(issuer,"write_mode"))||!"ACTIVE".equals(text(issuer,"status")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(source.customerNo()==null||!entity.equals(permit.entityNo())||!ctx.tenantNo().equals(source.context().tenantNo())
                ||!"STORES".equals(permit.redemptionScope().mode())||!List.of(permit.storeNo()).equals(permit.redemptionScope().refs()))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var current=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),text(issuer,"issuer_no"),text(issuer,"current_issuer_version_no"));
        if(current==null||!entity.equals(text(current,"settlement_entity_no"))||!permit.currency().equals(text(current,"currency")))throw BizException.of(ErrorCode.CONFLICT);
        var at=Timestamp.from(Instant.now());
        var release=db.one("SELECT * FROM sv_release WHERE tenant_no=? AND legacy_activity_version_no=? AND deleted=0",ctx.tenantNo(),permit.versionNo());
        if(release==null){
            long next=Math.addExact(number(db.one("SELECT COALESCE(MAX(release_index),0) AS n FROM sv_release WHERE tenant_no=? AND issuer_no=? AND activity_no=?",ctx.tenantNo(),text(issuer,"issuer_no"),permit.activityNo()),"n"),1);
            release=base(ctx,at);release.put("release_no",BizKey.next(BizKey.STORED_VALUE_RELEASE));release.put("issuer_no",text(issuer,"issuer_no"));release.put("issuer_version_no",text(current,"issuer_version_no"));
            release.put("source_kind","LEGACY_ACTIVITY");release.put("activity_no",permit.activityNo());release.put("activity_version_no",permit.versionNo());release.put("legacy_activity_version_no",permit.versionNo());release.put("release_index",next);
            release.put("currency",permit.currency());release.put("starts_at",Timestamp.from(source.createdAt()));release.put("state","ACTIVE");release.put("redemption_scope_mode","STORE_ONLY");release.put("scope_snapshot",db.encode(permit.redemptionScope()));release.put("policy_versions","[]");release.put("content_hash",permit.snapshotHash());db.insert("sv_release",release);
        }
        if(!text(issuer,"issuer_no").equals(text(release,"issuer_no"))||!permit.activityNo().equals(text(release,"activity_no"))
                ||!permit.snapshotHash().equals(text(release,"content_hash"))||!"ACTIVE".equals(text(release,"state"))
                ||!permit.currency().equals(text(release,"currency"))||!permit.redemptionScope().equals(db.decode(text(release,"scope_snapshot"),RedemptionScope.class)))throw BizException.of(ErrorCode.CONFLICT);
        String sourceTier=permit.tierNo()==null?"0":"1:"+permit.tierNo();
        var tier=db.one("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND legacy_tier_key=? AND deleted=0",ctx.tenantNo(),text(release,"release_no"),sourceTier);
        if(tier==null){
            long ordinal=Math.addExact(number(db.one("SELECT COALESCE(MAX(ordinal),0) AS n FROM sv_release_tier WHERE tenant_no=? AND release_no=?",ctx.tenantNo(),text(release,"release_no")),"n"),1);
            tier=base(ctx,at);tier.put("tier_no",BizKey.next(BizKey.STORED_VALUE_RELEASE_TIER));tier.put("release_no",text(release,"release_no"));tier.put("ordinal",ordinal);tier.put("legacy_tier_key",sourceTier);tier.put("paid_amount_minor",permit.payAmountMinor());tier.put("bonus_amount_minor",permit.bonusAmountMinor());db.insert("sv_release_tier",tier);
        }
        if(number(tier,"paid_amount_minor")!=original.getPrincipalMinor()||number(tier,"bonus_amount_minor")!=original.getBonusMinor())throw BizException.of(ErrorCode.CONFLICT);
        var frozen=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),text(issuer,"issuer_no"),text(release,"issuer_version_no"));
        if(frozen==null||!entity.equals(text(frozen,"settlement_entity_no")))throw BizException.of(ErrorCode.CONFLICT);
        changed(db.update("UPDATE sv_recharge SET customer_no=?,issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND recharge_no=? AND issuer_no IS NULL",
                source.customerNo(),text(issuer,"issuer_no"),text(release,"issuer_version_no"),text(release,"release_no"),text(tier,"tier_no"),entity,text(frozen,"settlement_entity_no"),ctx.tenantNo(),original.getRechargeNo()));
        quotas.reserve(ctx,original.getRechargeNo());
    }
    /** Always precedes receipt/account row locks, including a replay whose creation closure did not run. */
    public void beforeOriginal(EntitlementContext ctx,String entity,String rechargeNo) {
        if(!enabled)return;
        transaction();
        var row=original(ctx,entity,rechargeNo);
        if(row==null)return;
        if(text(row,"issuer_no")==null) { requireUnswitchedStore(ctx,text(row,"store_no"));return; }
        var version=db.one("SELECT i.write_mode AS issuer_write_mode,v.* FROM sv_issuer i JOIN sv_issuer_version v ON v.tenant_no=i.tenant_no AND v.issuer_no=i.issuer_no WHERE i.tenant_no=? AND i.issuer_no=? AND i.scope_type='STORE' AND i.scope_no=? AND v.issuer_version_no=? AND i.deleted=0 AND v.deleted=0 FOR UPDATE",
                ctx.tenantNo(),text(row,"issuer_no"),text(row,"store_no"),text(row,"issuer_version_no"));
        if(version!=null&&"FENCED".equals(text(version,"issuer_write_mode")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(version==null||!text(row,"currency").equals(text(version,"currency"))
                ||!entity.equals(text(version,"settlement_entity_no"))
                ||!entity.equals(text(row,"collection_entity_no"))||!entity.equals(text(row,"settlement_entity_no")))throw BizException.of(ErrorCode.CONFLICT);
        quotas.lockForOriginal(ctx,rechargeNo);
    }

    public void cancelled(EntitlementContext ctx,String entity,String rechargeNo) {
        if(!enabled)return;
        var row=original(ctx,entity,rechargeNo);
        if(row!=null&&text(row,"quota_reservation_no")!=null)quotas.release(ctx,rechargeNo);
    }

    /** Metadata, immutable source provenance and quota commit join the actual ledger commit. */
    public void credited(EntitlementContext ctx,String entity,String rechargeNo,String accountNo,String lotNo,String voucherNo) {
        if(!enabled)return;
        transaction();var row=original(ctx,entity,rechargeNo);
        if(row==null||text(row,"issuer_no")==null)return;
        var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);
        if(account==null||!text(row,"store_no").equals(text(account,"store_no"))||!entity.equals(text(account,"entity_no"))
                ||text(account,"issuer_no")!=null&&!text(row,"issuer_no").equals(text(account,"issuer_no")))throw BizException.of(ErrorCode.CONFLICT);
        changed(db.update("UPDATE sv_account SET issuer_no=COALESCE(issuer_no,?),issuer_version_no=COALESCE(issuer_version_no,?) WHERE tenant_no=? AND account_no=?",text(row,"issuer_no"),text(row,"issuer_version_no"),ctx.tenantNo(),accountNo));
        changed(db.update("UPDATE sv_lot SET issuer_no=?,issuer_version_no=?,release_no=?,tier_no=?,collection_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND lot_no=? AND recharge_no=?",
                text(row,"issuer_no"),text(row,"issuer_version_no"),text(row,"release_no"),text(row,"tier_no"),text(row,"collection_entity_no"),text(row,"settlement_entity_no"),ctx.tenantNo(),lotNo,rechargeNo));
        changed(db.update("UPDATE sv_voucher SET issuer_no=? WHERE tenant_no=? AND voucher_no=? AND account_no=?",text(row,"issuer_no"),ctx.tenantNo(),voucherNo,accountNo));
        db.update("UPDATE sv_statement SET issuer_no=?,scope_type='STORE',scope_no=?,release_no=?,operating_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND voucher_no=?",
                text(row,"issuer_no"),text(row,"store_no"),text(row,"release_no"),entity,text(row,"settlement_entity_no"),ctx.tenantNo(),voucherNo);
        var at=Timestamp.from(Instant.now());
        var index=db.one("SELECT * FROM sv_customer_issuer_index WHERE tenant_no=? AND account_no=? FOR UPDATE",ctx.tenantNo(),accountNo);
        if(index==null) {
            index=base(ctx,at);index.put("customer_no",text(account,"customer_no")==null?text(account,"user_no"):text(account,"customer_no"));
            index.put("issuer_no",text(row,"issuer_no"));index.put("account_no",accountNo);index.put("currency",text(row,"currency"));index.put("has_recharge",1);
            index.put("last_recharge_at",row.get("credited_at"));index.put("last_activity_at",row.get("credited_at"));index.put("last_occurrence_store_no",text(row,"store_no"));db.insert("sv_customer_issuer_index",index);
        } else {
            if(!text(row,"issuer_no").equals(text(index,"issuer_no")))throw BizException.of(ErrorCode.CONFLICT);
            db.update("UPDATE sv_customer_issuer_index SET has_recharge=1,last_recharge_at=?,last_activity_at=?,last_occurrence_store_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",
                    row.get("credited_at"),row.get("credited_at"),text(row,"store_no"),at,ctx.actorNo(),ctx.tenantNo(),accountNo);
        }
        if(text(row,"quota_reservation_no")!=null)quotas.commit(ctx,rechargeNo);
        else if(text(row,"release_no")!=null)throw BizException.of(ErrorCode.CONFLICT);
    }
    /** Acquired after source/intent fences but strictly before the account row lock. */
    public void beforeAccount(EntitlementContext ctx,String entity,String store,String currency,String accountNo) {
        if(!enabled)return;
        transaction();var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);
        if(account==null||!entity.equals(text(account,"entity_no"))||!store.equals(text(account,"store_no"))
                ||!currency.equals(text(account,"currency")))throw BizException.of(ErrorCode.CONFLICT);
        if(text(account,"issuer_no")==null){requireUnswitchedStore(ctx,store);return;}
        var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),text(account,"issuer_no"),store);
        if(issuer==null)throw BizException.of(ErrorCode.CONFLICT);
        if("FENCED".equals(text(issuer,"write_mode")))throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        quotas.lockForAccount(ctx,accountNo);
    }

    public void held(EntitlementContext ctx,String accountNo,String operationNo) {
        if(!enabled)return;
        var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);
        if(account==null||text(account,"issuer_no")==null)return;
        var parts=db.rows("SELECT a.id,l.issuer_no,l.release_no FROM sv_hold_allocation a JOIN sv_lot l ON l.tenant_no=a.tenant_no AND l.lot_no=a.lot_no WHERE a.tenant_no=? AND a.operation_no=? AND l.account_no=? AND a.deleted=0 AND l.deleted=0",ctx.tenantNo(),operationNo,accountNo);
        if(parts.isEmpty())throw BizException.of(ErrorCode.CONFLICT);
        for(var part:parts) {
            if(!text(account,"issuer_no").equals(text(part,"issuer_no")))throw BizException.of(ErrorCode.CONFLICT);
            changed(db.update("UPDATE sv_hold_allocation SET issuer_no=?,release_no=?,allocation_no=COALESCE(allocation_no,?) WHERE tenant_no=? AND id=?",text(part,"issuer_no"),text(part,"release_no"),BizKey.next(BizKey.STORED_VALUE_HOLD_ALLOCATION),ctx.tenantNo(),part.get("id")));
        }
    }

    /** Original committed voucher is the only debit identity, never a client-provided amount. */
    public void consumed(EntitlementContext ctx,String entity,String accountNo,String voucherNo) {
        if(!enabled)return;
        transaction();var account=db.one("SELECT * FROM sv_account WHERE tenant_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),accountNo);
        if(account==null||text(account,"issuer_no")==null)return;
        if(!entity.equals(text(account,"entity_no")))throw BizException.of(ErrorCode.CONFLICT);
        var voucher=db.one("SELECT * FROM sv_voucher WHERE tenant_no=? AND voucher_no=? AND account_no=? AND deleted=0",ctx.tenantNo(),voucherNo,accountNo);
        if(voucher==null||!"CONSUME".equals(text(voucher,"biz_type"))||number(voucher,"principal_delta")>0||number(voucher,"bonus_delta")>0)throw BizException.of(ErrorCode.CONFLICT);
        long amount;
        try{amount=Math.negateExact(Math.addExact(number(voucher,"principal_delta"),number(voucher,"bonus_delta")));}
        catch(ArithmeticException invalid){throw BizException.of(ErrorCode.CONFLICT);}
        if(amount<=0)throw BizException.of(ErrorCode.CONFLICT);
        commands.execute(new ai.neargo.shop.pay.entitlement.dto.CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),"SV_ISSUER_CONSUME",voucherNo,
                List.of(accountNo,text(account,"issuer_no"),amount),voucherNo,Consumption.class,()->{
            var at=Timestamp.from(Instant.now());
            changed(db.update("UPDATE sv_issue_quota SET outstanding_minor=outstanding_minor-?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND store_no=? AND currency=? AND deleted=0 AND outstanding_minor>=?",
                    amount,at,ctx.actorNo(),ctx.tenantNo(),text(account,"store_no"),text(account,"currency"),amount));
            changed(db.update("UPDATE sv_voucher SET issuer_no=? WHERE tenant_no=? AND voucher_no=?",text(account,"issuer_no"),ctx.tenantNo(),voucherNo));
            db.update("UPDATE sv_statement SET issuer_no=?,scope_type='STORE',scope_no=?,operating_entity_no=?,settlement_entity_no=? WHERE tenant_no=? AND voucher_no=?",
                    text(account,"issuer_no"),text(account,"store_no"),entity,entity,ctx.tenantNo(),voucherNo);
            db.update("UPDATE sv_customer_issuer_index SET last_activity_at=?,last_occurrence_store_no=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND account_no=?",
                    voucher.get("occurred_at"),text(account,"store_no"),at,ctx.actorNo(),ctx.tenantNo(),accountNo);
            return new Consumption(voucherNo,amount);
        });
    }
    private void requireUnswitchedStore(EntitlementContext ctx,String store) {
        var issuer=db.one("SELECT write_mode FROM sv_issuer WHERE tenant_no=? AND scope_type='STORE' AND scope_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),store);
        if(issuer!=null&&Set.of("ISSUER","FENCED").contains(text(issuer,"write_mode")))throw BizException.of(ErrorCode.CONFLICT);
    }
    private Map<String,Object> original(EntitlementContext ctx,String entity,String no) {
        var row=db.one("SELECT * FROM sv_recharge WHERE tenant_no=? AND recharge_no=? AND deleted=0",ctx.tenantNo(),no);
        if(row!=null&&!entity.equals(text(row,"entity_no")))throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Pay transaction required");}
    private static void changed(int count){if(count!=1)throw BizException.of(ErrorCode.CONFLICT);}
    private static Map<String,Object> base(EntitlementContext ctx,Timestamp at){
        var row=new LinkedHashMap<String,Object>();row.put("tenant_no",ctx.tenantNo());row.put("version",0);row.put("deleted",0);
        row.put("created_at",at);row.put("updated_at",at);row.put("created_by",ctx.actorNo());row.put("updated_by",ctx.actorNo());return row;
    }
}
