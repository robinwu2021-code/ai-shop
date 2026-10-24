package ai.neargo.shop.pay.storedvalue.issuer;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.*;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.*;
import ai.neargo.shop.common.*;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.policy.*;
import ai.neargo.shop.pay.storedvalue.service.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuerRecharges.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePolicies.*;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Invoked only inside the facade's Pay transaction, after number ranges were reserved. */
@Service
public class StoredValueBrandRechargeWriter {
    private final IssuerRepository db;
    private final SvRechargeMapper recharges;
    private final EntitlementCommandDeduplicator commands;
    private final StoredValueBrandRechargeOptions options;
    private final StoredValueBrandRuntimeReadiness readiness;
    private final StoredValueIssuePolicyService policies;
    private final StoredValueIssuePolicyEvaluator evaluator;
    private final StoredValueIssueQuotaService quotas;
    private final StoredValueBrandLedgerService ledger;
    private final StoredValueRechargeWriter receipts;
    private final StoredValueCreditService credits;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;
    public StoredValueBrandRechargeWriter(IssuerRepository db,SvRechargeMapper recharges,EntitlementCommandDeduplicator commands,
            StoredValueBrandRechargeOptions options,StoredValueBrandRuntimeReadiness readiness,StoredValueIssuePolicyService policies,
            StoredValueIssuePolicyEvaluator evaluator,StoredValueIssueQuotaService quotas,StoredValueBrandLedgerService ledger,
            StoredValueRechargeWriter receipts,StoredValueCreditService credits,PayEntitlementFeatureGate features,ObjectMapper json) {
        this.db=db;this.recharges=recharges;this.commands=commands;this.options=options;this.readiness=readiness;this.policies=policies;
        this.evaluator=evaluator;this.quotas=quotas;this.ledger=ledger;this.receipts=receipts;this.credits=credits;this.features=features;this.json=json;
    }
    public String originalNo(EntitlementContext c,String entity){return no("IR",List.of(c.tenantNo(),entity,c.actorNo(),c.requestId()),json);}
    public RechargeView confirm(EntitlementContext ctx,String entity,Create c) {
        transaction();
        var current=create(ctx,entity,c);
        if(current.status()==RechargeState.CREDITED||current.status()==RechargeState.CANCELLED)return current;
        ledger.original(ctx,entity,current.rechargeNo());
        if(current.status()==RechargeState.RECEIVED)return credit(ctx,owned(ctx,entity,current.rechargeNo()));
        String receipt=no("OC",List.of(ctx.tenantNo(),entity,current.rechargeNo()),json);
        String evidence=digest(List.of("MERCHANT_OFFLINE",ctx.tenantNo(),entity,current.rechargeNo(),receipt),json);
        if(current.status()==RechargeState.WAIT_COLLECTION)current=receipts.submit(step(ctx,"IS"),entity,
            new SubmitRecharge(current.rechargeNo(),current.revision(),current.paidAmountMinor(),CollectionMethod.MERCHANT_OFFLINE,receipt,evidence));
        if(current.status()!=RechargeState.SUBMITTED)conflict();
        receipts.receive(step(ctx,"IV"),entity,new ReceiveRecharge(current.rechargeNo(),current.revision(),evidence,"Merchant confirmed offline collection"));
        return credit(ctx,owned(ctx,entity,current.rechargeNo()));
    }
    public RechargeView create(EntitlementContext ctx,String entity,Create c) {
        transaction();
        var s=c.query().scope();
        if(!ctx.tenantNo().equals(s.tenantNo())||!ctx.actorNo().equals(s.actorNo())||!entity.equals(s.entityNo()))forbidden();
        String rechargeNo=originalNo(ctx,entity);
        var replay=new Replay(s.storeNo(),digest(c.optionRef(),json),c.customerReferenceHash());
        var result=commands.execute(catalog(ctx,entity),"SV_ISSUER_RECHARGE",ctx.requestId(),identity(replay),rechargeNo,RechargeView.class,()->{
            features.requireStoredValue();readiness.require();
            if(s.brandNo()==null)unavailable();
            var selected=options.resolve(c.query(),c.optionRef());
            // Read identity first, then lock in the global policy -> issuer -> release/access -> quota order.
            var head=db.one("SELECT issuer_no FROM sv_release WHERE tenant_no=? AND release_no=? AND source_kind='V2_BRAND_ACTIVITY' AND deleted=0",ctx.tenantNo(),selected.releaseNo());
            if(head==null)unavailable();
            var scope=new BrandScope(ctx.tenantNo(),s.managementNo(),s.brandNo(),text(head,"issuer_no"),s.currency(),s.brandRevision());
            var effective=policies.evaluateLocked(scope);
            var issuer=db.one("SELECT * FROM sv_issuer WHERE tenant_no=? AND issuer_no=? AND scope_type='BRAND' AND scope_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),scope.issuerNo(),s.brandNo());
            if(issuer==null||!"ACTIVE".equals(text(issuer,"status"))||!"ISSUER".equals(text(issuer,"write_mode")))unavailable();
            var release=db.one("SELECT * FROM sv_release WHERE tenant_no=? AND release_no=? AND source_kind='V2_BRAND_ACTIVITY' AND deleted=0 FOR UPDATE",ctx.tenantNo(),selected.releaseNo());
            var tier=db.one("SELECT * FROM sv_release_tier WHERE tenant_no=? AND release_no=? AND tier_no=? AND deleted=0",ctx.tenantNo(),selected.releaseNo(),selected.tierNo());
            if(release==null||tier==null||!"ACTIVE".equals(text(release,"state"))||!scope.issuerNo().equals(text(release,"issuer_no"))
                    ||!text(issuer,"current_issuer_version_no").equals(text(release,"issuer_version_no"))||!s.currency().equals(text(release,"currency"))
                    ||text(release,"activity_version_no")==null)unavailable();
            Instant now=Instant.now();
            if(now.isBefore(instant(release.get("starts_at")))||!now.isBefore(instant(release.get("ends_at"))))unavailable();
            var evidence=db.decode(text(release,"policy_versions"),StoredValueReleaseService.Evidence.class);
            if(!ai.neargo.shop.spi.storedvalue.StoredValuePolicies.sameIssueIdentity(scope,evidence.scope()))unavailable();
            var access=db.one("SELECT * FROM sv_issuer_store_access WHERE tenant_no=? AND issuer_no=? AND store_no=? AND deleted=0 FOR UPDATE",ctx.tenantNo(),scope.issuerNo(),s.storeNo());
            if(access==null||!"ACTIVE".equals(text(access,"status"))||number(access,"can_issue")!=1||!s.entityNo().equals(text(access,"operating_entity_no"))
                    ||!s.brandBindingNo().equals(text(access,"source_binding_no"))||s.brandBindingRevision()!=number(access,"source_revision")
                    ||now.isBefore(instant(access.get("effective_from")))||access.get("effective_to")!=null&&!now.isBefore(instant(access.get("effective_to"))))unavailable();
            var version=db.one("SELECT * FROM sv_issuer_version WHERE tenant_no=? AND issuer_no=? AND issuer_version_no=? AND deleted=0",ctx.tenantNo(),scope.issuerNo(),text(release,"issuer_version_no"));
            // P5 deliberately admits same-entity execution only; P6 owns inter-entity collection/clearing.
            if(version==null||!entity.equals(text(version,"settlement_entity_no"))||!s.currency().equals(text(version,"currency"))
                    ||!"CENTRALIZED".equals(text(version,"collection_mode"))||!"PRO_RATA".equals(text(version,"debit_allocation_mode")))unavailable();
            var routes=db.routes(text(version,"collection_config")).stream().filter(r->entity.equals(r.entityNo())&&"MERCHANT_OFFLINE".equals(r.channel())&&"OFFLINE".equals(r.scene())&&(r.storeNo()==null||s.storeNo().equals(r.storeNo()))).toList();
            if(routes.size()!=1)unavailable();
            var quota=quotas.lockIssuerQuota(ctx,scope.issuerNo(),s.currency());
            var schedule=evidence.original();
            int days=Math.toIntExact(ChronoUnit.DAYS.between(schedule.startsAt().atZone(ZoneId.of(schedule.zone())).toLocalDate(),schedule.endsAt().minusNanos(1).atZone(ZoneId.of(schedule.zone())).toLocalDate())+1);
            var check=evaluator.check(effective,new IssueRequest(number(tier,"paid_amount_minor"),number(tier,"bonus_amount_minor"),days,number(quota,"outstanding_minor"),number(quota,"reserved_minor")));
            if(!check.allowed()||check.approvalRequired()&&(!evidence.approvalRequired()||!effective.hash().equals(evidence.policyHash())))unavailable();
            var person=c.query().customer();
            var funding=new Funding(rechargeNo,person.userNo(),person.customerNo(),entity,s.storeNo(),s.currency(),scope.issuerNo(),text(release,"issuer_version_no"),
                    selected.releaseNo(),selected.tierNo(),text(release,"activity_no"),text(release,"activity_version_no"),schedule.name(),
                    number(tier,"paid_amount_minor"),number(tier,"bonus_amount_minor"),routes.getFirst().entityNo(),text(version,"settlement_entity_no"),routes.getFirst(),s.brandBindingNo(),s.brandBindingRevision());
            var at=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
            var r=new SvRecharge();audit(r,ctx,at);r.setRechargeNo(rechargeNo);r.setRequestId(ctx.requestId());r.setPayloadHash(digest(identity(replay),json));
            r.setSourceKind("ISSUER_RELEASE");r.setActivityNo(funding.activityNo());r.setActivityVersionNo(funding.activityVersionNo());r.setUserNo(person.userNo());r.setCustomerNo(person.customerNo());r.setEntityNo(entity);r.setStoreNo(s.storeNo());r.setStoreName(c.storeName());
            r.setCurrency(s.currency());r.setPrincipalMinor(funding.paidAmountMinor());r.setBonusMinor(funding.bonusAmountMinor());r.setStatus("WAIT_COLLECTION");r.setOperatorNo(ctx.actorNo());r.setExpiresAt(at.plusMinutes(30));
            r.setPermitSnapshot(json.writeValueAsString(funding));r.setSourceSnapshot(json.writeValueAsString(new SourceRef("PAY","ISSUER_RECHARGE",rechargeNo,null,null,null,0)));
            insertOriginal(ctx,r,funding);
            quotas.reserve(ctx,rechargeNo);
            db.update("UPDATE sv_issue_quota_reservation SET policy_versions=?,updated_at=?,updated_by=?,version=version+1 WHERE tenant_no=? AND recharge_no=?",db.encode(effective.sources().stream().map(PolicyVersion::versionNo).toList()),Timestamp.from(now),ctx.actorNo(),ctx.tenantNo(),rechargeNo);
            return view(r);
        });
        return recharges.find(ctx.tenantNo(),rechargeNo)==null?result:query(ctx,entity,rechargeNo);
    }
    public ReplayResult replay(EntitlementContext ctx,String entity,Replay c){
        var prior=commands.replay(catalog(ctx,entity),"SV_ISSUER_RECHARGE",ctx.requestId(),identity(c),RechargeView.class);
        if(prior.isEmpty())return new ReplayResult(false,null);
        var row=recharges.find(ctx.tenantNo(),prior.get().rechargeNo());
        return new ReplayResult(true,row==null?prior.get():query(ctx,entity,row.getRechargeNo()));
    }
    public RechargeView cancelCreation(EntitlementContext ctx,String entity,Replay c){
        transaction();String n=originalNo(ctx,entity);
        var tombstone=new RechargeView(n,RechargeState.CANCELLED,null,entity,c.storeNo(),null,null,null,null,null,0);
        var reply=commands.execute(catalog(ctx,entity),"SV_ISSUER_RECHARGE",ctx.requestId(),identity(c),n,RechargeView.class,()->tombstone);
        var row=recharges.find(ctx.tenantNo(),n);if(row==null)return reply;
        var original=owned(ctx,entity,n);if(!c.storeNo().equals(original.getStoreNo()))conflict();
        if("CANCELLED".equals(original.getStatus()))return view(original);
        return receipts.cancel(step(ctx,"IC"),entity,new CancelRecharge(n,original.getVersion(),"Merchant cancelled original collection"),null);
    }
    public RechargeView query(EntitlementContext ctx,String entity,String n){return view(owned(ctx,entity,n));}
    public boolean recover(EntitlementContext ctx,String entity,String n){
        transaction();var row=recharges.find(ctx.tenantNo(),n);if(row==null||!"ISSUER_RELEASE".equals(row.getSourceKind()))return false;
        row=owned(ctx,entity,n);if("RECEIVED".equals(row.getStatus()))credit(ctx,row);return true;
    }
    private void insertOriginal(EntitlementContext ctx,SvRecharge r,Funding f){
        var row=new LinkedHashMap<String,Object>();row.put("tenant_no",ctx.tenantNo());row.put("recharge_no",r.getRechargeNo());row.put("request_id",r.getRequestId());row.put("payload_hash",r.getPayloadHash());
        row.put("source_kind",r.getSourceKind());row.put("activity_no",f.activityNo());row.put("activity_version_no",f.activityVersionNo());row.put("user_no",f.userNo());row.put("customer_no",f.customerNo());row.put("entity_no",f.operatingEntityNo());row.put("store_no",f.storeNo());row.put("store_name",r.getStoreName());row.put("currency",f.currency());row.put("principal_minor",f.paidAmountMinor());row.put("bonus_minor",f.bonusAmountMinor());row.put("status","WAIT_COLLECTION");row.put("operator_no",ctx.actorNo());row.put("expires_at",r.getExpiresAt());row.put("permit_snapshot",r.getPermitSnapshot());row.put("source_snapshot",r.getSourceSnapshot());
        row.put("issuer_no",f.issuerNo());row.put("issuer_version_no",f.issuerVersionNo());row.put("release_no",f.releaseNo());row.put("tier_no",f.tierNo());row.put("collection_entity_no",f.collectionEntityNo());row.put("settlement_entity_no",f.settlementEntityNo());
        row.put("version",0);row.put("deleted",0);row.put("created_at",r.getCreatedAt());row.put("updated_at",r.getUpdatedAt());row.put("created_by",ctx.actorNo());row.put("updated_by",ctx.actorNo());db.insert("sv_recharge",row);
    }
    private RechargeView credit(EntitlementContext c,SvRecharge r){return credits.credit(c,new CreditRecharge(r.getRechargeNo(),new SourceRef("PAY","RECHARGE",r.getRechargeNo(),null,null,null,0),json.readValue(r.getReceivedDecisionSnapshot(),DecisionRef.class)));}
    private SvRecharge owned(EntitlementContext c,String entity,String n){var r=recharges.find(c.tenantNo(),n);if(r==null||!"ISSUER_RELEASE".equals(r.getSourceKind())||!entity.equals(r.getEntityNo()))throw BizException.of(ErrorCode.NOT_FOUND);return r;}
    private List<String> identity(Replay r){return List.of(r.storeNo(),r.optionHash(),r.customerReferenceHash());}
    private CatalogScope catalog(EntitlementContext c,String e){return new CatalogScope(c.tenantNo(),e,c.actorNo(),false);}
    private EntitlementContext step(EntitlementContext c,String prefix){return new EntitlementContext(c.tenantNo(),c.actorNo(),no(prefix,List.of(c.requestId()),json),"0".repeat(64));}
    private static Instant instant(Object value){if(value instanceof Timestamp t)return t.toInstant();if(value instanceof LocalDateTime t)return t.toInstant(ZoneOffset.UTC);if(value instanceof Instant t)return t;throw BizException.of(ErrorCode.CONFLICT);}
    private void transaction(){if(!db.payTransactionActive())throw new IllegalStateException("Pay transaction required");}
    private static void forbidden(){throw BizException.of(ErrorCode.FORBIDDEN);}
    private static void unavailable(){throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);}
}
