package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.conflict;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.view;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargeMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargePlanMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargePlanVersionMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRecharge;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlan;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlanVersion;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAccountScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanRechargeService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Funding;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.Replay;
import ai.neargo.shop.spi.storedvalue.StoredValuePlanRecharges.ReplayResult;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CancelRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeState;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeWriter;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.CreditRecharge;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StoredValuePlanRechargeServiceImpl implements StoredValuePlanRechargeService {
    private final ai.neargo.shop.pay.mapper.storedvalue.SvCustomerIdentityMapper identities;
    private final SvRechargeMapper recharges;
    private final SvRechargePlanMapper plans;
    private final SvRechargePlanVersionMapper versions;
    private final SvAccountMapper accounts;
    private final StoredValueCreditService credits;
    private final EntitlementCommandDeduplicator commands;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;
    private final StoredValueRechargeWriter writer;

    public StoredValuePlanRechargeServiceImpl(ai.neargo.shop.pay.mapper.storedvalue.SvCustomerIdentityMapper identities,SvRechargeMapper recharges, SvRechargePlanMapper plans,
            SvRechargePlanVersionMapper versions, SvAccountMapper accounts, StoredValueCreditService credits,
            EntitlementCommandDeduplicator commands, PayEntitlementFeatureGate features, ObjectMapper json, StoredValueRechargeWriter writer) {
        this.identities=identities;this.recharges=recharges; this.plans=plans; this.versions=versions; this.accounts=accounts;
        this.credits=credits; this.commands=commands; this.features=features; this.json=json; this.writer=writer;
    }

    @Override @Transactional("payTxManager")
    public void bindCustomer(EntitlementContext ctx,String customer,String user){
        ai.neargo.shop.spi.entitlement.ContractChecks.id(customer);ai.neargo.shop.spi.entitlement.ContractChecks.id(user);
        identities.ensure(ctx.tenantNo(),customer,user);
        var rows=identities.lock(ctx.tenantNo(),customer,user);
        if(rows.size()!=1||!customer.equals(rows.getFirst().customerNo())||!user.equals(rows.getFirst().userNo())
                ||identities.conflicts(ctx.tenantNo(),customer,user)>0)conflict();
        identities.bindAccounts(ctx.tenantNo(),customer,user,ctx.actorNo(),LocalDateTime.now(ZoneOffset.UTC));
        identities.bindDirectory(ctx.tenantNo(),customer,user,ctx.actorNo(),LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override @Transactional("payTxManager")
    public RechargeView create(EntitlementContext ctx, String entity, Create c) {
        String no=no("PR", List.of(ctx.tenantNo(), entity, ctx.actorNo(), ctx.requestId()), json);
        var scope=new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false);
        var reply=commands.execute(scope,"SV_PLAN_RECHARGE",ctx.requestId(),identity(c.storeNo(),c.planNo(),c.versionNo(),c.referenceHash()),no,RechargeView.class,()->{
            features.requireStoredValue();
            var p=plans.selectOne(Wrappers.<SvRechargePlan>lambdaQuery()
                    .eq(SvRechargePlan::getTenantNo,ctx.tenantNo()).eq(SvRechargePlan::getEntityNo,entity)
                    .eq(SvRechargePlan::getStoreNo,c.storeNo()).eq(SvRechargePlan::getPlanNo,c.planNo()).last("FOR UPDATE"));
            if(p==null)throw BizException.of(ErrorCode.NOT_FOUND);
            if(!"ACTIVE".equals(p.getStatus())||!c.versionNo().equals(p.getCurrentVersionNo()))conflict();
            var v=versions.selectOne(Wrappers.<SvRechargePlanVersion>lambdaQuery()
                    .eq(SvRechargePlanVersion::getTenantNo,ctx.tenantNo()).eq(SvRechargePlanVersion::getPlanNo,c.planNo())
                    .eq(SvRechargePlanVersion::getVersionNo,c.versionNo()));
            if(v==null)conflict();
            StoredValueAccountScope.requireMigrated(accounts,ctx.tenantNo(),c.userNo(),entity,p.getCurrency());
            var funding=new Funding(no,c.userNo(),entity,c.storeNo(),p.getCurrency(),p.getPlanNo(),v.getVersionNo(),
                    p.getName(),v.getPaidAmountMinor(),v.getBonusAmountMinor(),json.readValue(v.getScopeSnapshot(),RedemptionScope.class),c.customerNo());
            var at=LocalDateTime.now(ZoneOffset.UTC);
            var r=new SvRecharge();audit(r,ctx,at);r.setRechargeNo(no);r.setRequestId(ctx.requestId());r.setPayloadHash(digest(c,json));
            r.setSourceKind("PLAN");r.setPlanNo(p.getPlanNo());r.setPlanVersionNo(v.getVersionNo());
            r.setUserNo(c.userNo());r.setCustomerNo(c.customerNo());r.setEntityNo(entity);r.setStoreNo(c.storeNo());r.setStoreName(c.storeName());
            r.setCurrency(p.getCurrency());r.setPrincipalMinor(v.getPaidAmountMinor());r.setBonusMinor(v.getBonusAmountMinor());
            r.setStatus("WAIT_COLLECTION");r.setOperatorNo(ctx.actorNo());r.setExpiresAt(at.plusMinutes(30));
            r.setPermitSnapshot(json.writeValueAsString(funding));
            r.setSourceSnapshot(json.writeValueAsString(new SourceRef("PAY","RECHARGE_PLAN",no,null,null,null,0)));
            recharges.insert(r);return view(r);
        });
        return recharges.find(ctx.tenantNo(),no)==null&&reply.status()==RechargeState.CANCELLED?reply:query(ctx,entity,no);
    }

    /** A single Pay-local transaction; all Core authorization/identity facts are resolved before entry. */
    @Override @Transactional("payTxManager")
    public RechargeView confirmOffline(EntitlementContext ctx,String entity,Create command) {
        var current=create(ctx,entity,command);
        if(current.status()==RechargeState.RECEIVED||current.status()==RechargeState.CREDITED)return current;
        String receipt=no("OC",List.of(ctx.tenantNo(),entity,current.rechargeNo()),json);
        String evidence=digest(List.of("MERCHANT_OFFLINE",ctx.tenantNo(),entity,current.rechargeNo(),receipt),json);
        var submitCtx=new EntitlementContext(ctx.tenantNo(),ctx.actorNo(),no("OS",List.of(ctx.requestId()),json),"0".repeat(64));
        if(current.status()==RechargeState.WAIT_COLLECTION){
            current=writer.submit(submitCtx,entity,new ai.neargo.shop.spi.storedvalue.StoredValuePort.SubmitRecharge(
                    current.rechargeNo(),current.revision(),current.paidAmountMinor(),
                    ai.neargo.shop.spi.storedvalue.StoredValuePort.CollectionMethod.MERCHANT_OFFLINE,receipt,evidence));
        }
        if(current.status()!=RechargeState.SUBMITTED)throw BizException.of(ErrorCode.CONFLICT);
        var receiveCtx=new EntitlementContext(ctx.tenantNo(),ctx.actorNo(),no("OR",List.of(ctx.requestId()),json),"0".repeat(64));
        return writer.receive(receiveCtx,entity,new ai.neargo.shop.spi.storedvalue.StoredValuePort.ReceiveRecharge(
                current.rechargeNo(),current.revision(),evidence,"Merchant confirmed offline collection"));
    }

    @Override public ReplayResult replay(EntitlementContext ctx,String entity,Replay c){
        var prior=commands.replay(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),"SV_PLAN_RECHARGE",
                ctx.requestId(),identity(c.storeNo(),c.planNo(),c.versionNo(),c.referenceHash()),RechargeView.class);
        if(prior.isEmpty())return new ReplayResult(false,null);
        var r=recharges.find(ctx.tenantNo(),prior.get().rechargeNo());
        return new ReplayResult(true,r==null?prior.get():view(owned(ctx,entity,r.getRechargeNo())));
    }
    @Override @Transactional("payTxManager")
    public RechargeView cancelCreation(EntitlementContext ctx,String entity,Replay c){
        String no=no("PR",List.of(ctx.tenantNo(),entity,ctx.actorNo(),ctx.requestId()),json);
        var cancelled=new RechargeView(no,RechargeState.CANCELLED,null,entity,c.storeNo(),null,null,null,null,null,0);
        var original=commands.execute(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),"SV_PLAN_RECHARGE",
                ctx.requestId(),identity(c.storeNo(),c.planNo(),c.versionNo(),c.referenceHash()),no,RechargeView.class,()->cancelled);
        var r=recharges.find(ctx.tenantNo(),no);
        if(r==null)return original;
        if(!entity.equals(r.getEntityNo())||!c.storeNo().equals(r.getStoreNo())||!"PLAN".equals(r.getSourceKind()))conflict();
        if("CANCELLED".equals(r.getStatus()))return view(r);
        var cancelContext=new EntitlementContext(ctx.tenantNo(),ctx.actorNo(),no("PSC",List.of(ctx.requestId()),json),"0".repeat(64));
        return writer.cancel(cancelContext,entity,new CancelRecharge(no,r.getVersion(),"Merchant cancelled original collection"),null);
    }
    private static List<String> identity(String store,String plan,String version,String hash){return List.of(store,plan,version,hash);}

    @Override public RechargeView query(EntitlementContext ctx,String entity,String no){return view(owned(ctx,entity,no));}

    @Override public boolean recover(EntitlementContext ctx,String entity,String no){
        var r=recharges.find(ctx.tenantNo(),no);
        if(r==null||!"PLAN".equals(r.getSourceKind()))return false;
        if(!entity.equals(r.getEntityNo()))throw BizException.of(ErrorCode.NOT_FOUND);
        if("RECEIVED".equals(r.getStatus())){
            credits.credit(ctx,new CreditRecharge(no,new SourceRef("PAY","RECHARGE",no,null,null,null,0),
                    json.readValue(r.getReceivedDecisionSnapshot(),DecisionRef.class)));
        }
        return true;
    }
    private SvRecharge owned(EntitlementContext ctx,String entity,String no){
        var r=recharges.find(ctx.tenantNo(),no);
        if(r==null||!entity.equals(r.getEntityNo())||!"PLAN".equals(r.getSourceKind()))throw BizException.of(ErrorCode.NOT_FOUND);
        return r;
    }
}
