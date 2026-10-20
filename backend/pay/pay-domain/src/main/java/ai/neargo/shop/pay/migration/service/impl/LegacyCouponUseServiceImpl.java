package ai.neargo.shop.pay.migration.service.impl;
import ai.neargo.shop.pay.migration.service.LegacyCouponSnapshotReader;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseService;
import ai.neargo.shop.pay.migration.service.LegacyCouponUseWriter;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.entity.EntInstance;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.mapper.entitlement.EntInstanceMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntLegacyCouponOperationMapper;
import ai.neargo.shop.pay.migration.entity.EntLegacyCouponOperation;
import ai.neargo.shop.pay.migration.policy.LegacyCouponQuotePolicy;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.CheckoutFact;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Decision;
import ai.neargo.shop.spi.entitlement.LegacyCouponCheckoutSourcePort.Outcome;
import ai.neargo.shop.spi.entitlement.LegacyCouponUsePort.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Nontransactional orchestration. Source snapshots/decisions and owner lookups never run under pay/core financial locks. */
@Service("legacyCouponUseService")
public class LegacyCouponUseServiceImpl implements LegacyCouponUseService {
    private final EntLegacyCouponOperationMapper operations;
    private final EntInstanceMapper instances;
    private final LegacyCouponUseWriter writer;
    private final PayEntitlementFeatureGate features;
    private final ObjectProvider<LegacyCouponCheckoutSourcePort> sources;
    private final EntitlementOwnerRegistryPort owners;
    private final ObjectMapper json;
    private final LegacyCouponSnapshotReader snapshots;
    public LegacyCouponUseServiceImpl(EntLegacyCouponOperationMapper operations,EntInstanceMapper instances,
            LegacyCouponUseWriter writer,PayEntitlementFeatureGate features,ObjectProvider<LegacyCouponCheckoutSourcePort> sources,
            EntitlementOwnerRegistryPort owners,ObjectMapper json,LegacyCouponSnapshotReader snapshots) {
        this.operations=operations; this.instances=instances; this.writer=writer; this.features=features;
        this.sources=sources; this.owners=owners; this.json=json;this.snapshots=snapshots;
    }
    public Snapshot snapshot(EntitlementContext ctx,Owned command) {
        outside(ctx);ContractChecks.present(command);
        ownedAsset(ctx,command.origin(),command.targetInstanceNo(),command.ownerBatchNo(),command.ownerPlanHash(),command.userNo(),command.entityNo());
        return snapshots.read(ctx,command);
    }
    public Estimate estimate(EntitlementContext ctx,EstimateCommand command) {
        outside(ctx);ContractChecks.present(command);features.requireLegacyCoupon();
        if(!"CNY".equals(command.currency())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var asset=ownedAsset(ctx,command.origin(),command.targetInstanceNo(),command.ownerBatchNo(),command.ownerPlanHash(),
                command.userNo(),command.entityNo());
        var rule=json.readValue(asset.getRuleSnapshot(),EntitlementDefinition.class);
        LegacyCouponUseWriter.eligible(asset,rule,command.currency(),true);
        if(asset.getRemainingUnits()==null || asset.getReservedUnits()==null || asset.getRemainingUnits()-asset.getReservedUnits()<1) conflict();
        var calculated=new LegacyCouponQuotePolicy().calculate(rule,command.entityNo(),command.groups());
        return new Estimate(asset.getInstanceNo(),command.currency(),rule.title(),calculated.discountMinor(),calculated.allocations(),
                asset.getEffectiveUntil().toInstant(ZoneOffset.UTC));
    }
    public Quote quote(EntitlementContext ctx,Prepare command) {
        outside(ctx); ContractChecks.present(command); features.requireLegacyCoupon();
        var fact=fact(ctx,command); requireOpen(ctx,fact);
        var asset=ownedAsset(ctx,fact);
        var rule=json.readValue(asset.getRuleSnapshot(),EntitlementDefinition.class);
        LegacyCouponUseWriter.eligible(asset,rule,fact,true);
        if(asset.getRemainingUnits()==null || asset.getReservedUnits()==null
                || asset.getRemainingUnits()-asset.getReservedUnits()<1) conflict();
        var calculated=new LegacyCouponQuotePolicy().calculate(rule,fact.entityNo(),fact.groups());
        var until=asset.getEffectiveUntil().toInstant(ZoneOffset.UTC);
        if(fact.deadlineAt().isBefore(until)) until=fact.deadlineAt();
        return new Quote(asset.getInstanceNo(),fact.currency(),calculated.discountMinor(),calculated.allocations(),
                fact.economicHash(),until);
    }
    public Operation prepare(EntitlementContext ctx,Prepare command) {
        outside(ctx); ContractChecks.present(command);
        var previous=operations.find(ctx.tenantNo(),command.operationNo());
        if(previous!=null) {
            if(!LegacyCouponUseWriter.commandHash(command,json).equals(previous.getCommandHash())
                    || !command.userNo().equals(previous.getUserNo()) || !command.entityNo().equals(previous.getEntityNo()))
                conflict();
            return LegacyCouponUseWriter.view(previous,json);
        }
        features.requireLegacyCoupon();
        var fact=fact(ctx,command); requireCheckout(fact);
        var decided=decided(ctx,fact.checkoutNo());
        if(decided.isPresent()) {
            if(decided.get().outcome()!=Outcome.ABORT) conflict(); // A real core COMMIT cannot exist without an accepted hold.
            return writer.settle(ctx,new Complete(fact.checkoutNo(),fact.operationNo(),fact.entityNo(),decided.get().decisionNo()),
                    fact,decided.get());
        }
        if(!Instant.now().isBefore(fact.deadlineAt())) conflict();
        ownedAsset(ctx,fact); // Checks the authoritative registry before entering the writer.
        return writer.prepare(ctx,command,fact);
    }
    public Operation commit(EntitlementContext ctx,Complete command) {
        return settle(ctx,command,Outcome.COMMIT);
    }
    public Operation release(EntitlementContext ctx,Complete command) {
        return settle(ctx,command,Outcome.ABORT);
    }
    private Operation settle(EntitlementContext ctx,Complete command,Outcome outcome) {
        outside(ctx); ContractChecks.present(command);
        var prior=operations.find(ctx.tenantNo(),command.operationNo());
        if(prior!=null) {
            bind(prior,command);
            String accepted=outcome==Outcome.COMMIT?prior.getCommitDecisionNo():prior.getAbortDecisionNo();
            if(accepted!=null && accepted.equals(command.decisionNo())) return outcome==Outcome.COMMIT
                    ?writer.replay(ctx,command,false):LegacyCouponUseWriter.view(prior,json);
        }
        var fact=fact(ctx,command.checkoutNo(),command.operationNo(),null,command.entityNo()); requireCheckout(fact);
        var decision=decided(ctx,command.checkoutNo()).orElseThrow(
                ()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        if(decision.outcome()!=outcome || !command.decisionNo().equals(decision.decisionNo())) conflict();
        return writer.settle(ctx,command,fact,decision);
    }
    public Operation reverse(EntitlementContext ctx,Complete command) {
        outside(ctx); ContractChecks.present(command);
        var prior=operations.find(ctx.tenantNo(),command.operationNo());
        if(prior==null) throw BizException.of(ErrorCode.NOT_FOUND);
        bind(prior,command);
        if("REVERSED".equals(prior.getStatus())) {
            if(!command.decisionNo().equals(prior.getReverseDecisionNo())) conflict();
            return writer.replay(ctx,command,true);
        }
        var fact=fact(ctx,command.checkoutNo(),command.operationNo(),prior.getUserNo(),command.entityNo()); requireCheckout(fact);
        var original=decided(ctx,command.checkoutNo()).orElseThrow(
                ()->BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
        var reversed=source().reversal(ctx,command.checkoutNo());
        if(reversed==null || reversed.isEmpty()) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(!command.decisionNo().equals(reversed.get().decisionNo())) conflict();
        return writer.reverse(ctx,command,fact,original,reversed.get());
    }
    public Operation query(EntitlementContext ctx,String entity,String operation) {
        outside(ctx); ContractChecks.id(entity); ContractChecks.id(operation);
        var row=operations.find(ctx.tenantNo(),operation);
        if(row==null || !entity.equals(row.getEntityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        return LegacyCouponUseWriter.view(row,json);
    }
    private void requireOpen(EntitlementContext ctx,CheckoutFact fact) {
        if(decided(ctx,fact.checkoutNo()).isPresent() || !Instant.now().isBefore(fact.deadlineAt())) conflict();
    }
    private CheckoutFact fact(EntitlementContext ctx,Prepare command) {
        return fact(ctx,command.checkoutNo(),command.operationNo(),command.userNo(),command.entityNo());
    }
    private CheckoutFact fact(EntitlementContext ctx,String checkout,String operation,String user,String entity) {
        var fact=source().resolve(ctx,checkout);
        if(fact==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(!ctx.tenantNo().equals(fact.tenantNo()) || !checkout.equals(fact.checkoutNo())
                || !operation.equals(fact.operationNo()) || (user!=null && !user.equals(fact.userNo()))
                || !entity.equals(fact.entityNo())) conflict();
        if(!"CNY".equals(fact.currency())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return fact;
    }
    private Optional<Decision> decided(EntitlementContext ctx,String checkout) {
        var result=source().decision(ctx,checkout);
        if(result==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return result;
    }
    private LegacyCouponCheckoutSourcePort source() {
        var port=sources.getIfAvailable();
        if(port==null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        return port;
    }
    private EntInstance ownedAsset(EntitlementContext ctx,CheckoutFact fact) {
        return ownedAsset(ctx,fact.origin(),fact.targetInstanceNo(),fact.ownerBatchNo(),fact.ownerPlanHash(),fact.userNo(),fact.entityNo());
    }
    private EntInstance ownedAsset(EntitlementContext ctx,SourceIdentity source,String target,String batch,String proof,String user,String entity) {
        var owner=owners.resolve(ctx,source);
        if(owner==null || owner.ownerSystem()!=OwnerSystem.PAY || owner.status()!=MigrationState.CUTOVER
                || !source.equals(owner.source()) || !target.equals(owner.targetNo())
                || !batch.equals(owner.migrationBatchNo()) || !proof.equals(owner.cutoverWatermark())) conflict();
        var asset=instances.selectOne(Wrappers.<EntInstance>lambdaQuery().eq(EntInstance::getTenantNo,ctx.tenantNo())
                .eq(EntInstance::getInstanceNo,target));
        if(asset==null || !target.equals(asset.getInstanceNo()) || !user.equals(asset.getUserNo())
                || !entity.equals(asset.getEntityNo()) || !"ORDER_COUPON".equals(asset.getKind())) conflict();
        return asset;
    }
    private static void requireCheckout(CheckoutFact fact) {
        if(fact.intentKind()!=LegacyCouponCheckoutSourcePort.IntentKind.CHECKOUT) conflict();
    }
    private static void bind(EntLegacyCouponOperation row,Complete command) {
        if(!command.checkoutNo().equals(row.getCheckoutNo()) || !command.entityNo().equals(row.getEntityNo())) conflict();
    }
    private static void outside(EntitlementContext ctx) {
        ContractChecks.present(ctx);
        if(!"MAIN".equals(ctx.tenantNo())) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if(TransactionSynchronizationManager.isActualTransactionActive()) conflict();
    }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
