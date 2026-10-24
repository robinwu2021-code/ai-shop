package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.conflict;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.UseSnapshots.Prepared;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementUseWriter;
import ai.neargo.shop.pay.mapper.entitlement.EntUseIntentMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvAccountMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvLotMapper;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAllocationPolicy;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueUseFacts;
import ai.neargo.shop.pay.storedvalue.policy.StoredValueAccountScope;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionPostingService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRedemptionService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.State;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationState;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Quote;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Reason;
import ai.neargo.shop.spi.storedvalue.StoredValueUseQuotePort.Selection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Actual source and consumer consent are read before any pay write lock; deadline never releases an existing hold. */
@Service
@Transactional(transactionManager = "payTxManager", propagation = Propagation.NOT_SUPPORTED)
public class StoredValueRedemptionServiceImpl implements StoredValueRedemptionService {
    private final ObjectProvider<EntitlementUseSourcePort> sources;
    private final ObjectProvider<SourceDecisionPort> decisions;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerAccountService issuerAccounts;
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerQueryService walletQueries;
    @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.issuer.StoredValueLotBasisService lotBasis;
    private final SvAccountMapper accounts;
    private final SvLotMapper lots;
    private final EntUseIntentMapper intents;
    private final StoredValueAllocationPolicy policy;
    private final PayEntitlementFeatureGate features;
    private final StoredValueRedemptionPostingService posting;
    private final EntitlementUseWriter views;
    private final ObjectMapper json;
    public StoredValueRedemptionServiceImpl(ObjectProvider<EntitlementUseSourcePort> sources,
            ObjectProvider<SourceDecisionPort> decisions, SvAccountMapper accounts, SvLotMapper lots,
            EntUseIntentMapper intents, StoredValueAllocationPolicy policy, PayEntitlementFeatureGate features,
            StoredValueRedemptionPostingService posting, EntitlementUseWriter views, ObjectMapper json) {
        this.sources = sources; this.decisions = decisions; this.accounts = accounts; this.lots = lots;
        this.intents = intents; this.policy = policy; this.features = features; this.posting = posting;
        this.views = views; this.json = json;
    }
    @Override public Quote quote(EntitlementContext ctx, SourceRef ref) {
        enabled(ctx); var source = source(ctx, ref); long amount = StoredValueUseFacts.amount(source);
        StoredValueAccountScope.requireMigrated(accounts, ctx.tenantNo(), source.userNo(), source.entityNo(), source.currency());
        var selected=issuerAccounts.lookup(ctx.tenantNo(),source.entityNo(),source.storeNo(),source.currency(),source.userNo(),false);
        var account=selected.issuerScoped()?accounts.findAccount(ctx.tenantNo(),selected.accountNo()):accounts.findOwner(ctx.tenantNo(),source.userNo(),source.entityNo(),source.currency(),source.storeNo());
        String accountNo = account == null ? null : account.getAccountNo();
        long revision = account == null ? 0 : account.getVersion();
        long applicable = 0; Reason reason = Reason.NO_ACCOUNT;
        if (account != null) {
            if (!"ACTIVE".equals(account.getStatus())) reason = Reason.FROZEN;
            else {
                // Account/lots can change during this read-only offer. The prepare transaction rechecks its revision.
                var available = StoredValueUseFacts.available(lots.live(ctx.tenantNo(), accountNo), json);
                applicable = policy.allocate(available, source).amountMinor();
                reason = applicable == amount ? Reason.OK : Reason.INSUFFICIENT_APPLICABLE_BALANCE;
            }
        }
        var selection = new Selection(ref, source.orderSnapshotHash(), accountNo, amount, revision);
        return new Quote("SQ" + UUID.randomUUID().toString().replace("-", ""), ref, accountNo, source.currency(),
                amount, applicable, reason == Reason.OK, reason, revision, digest(selection, json),
                Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(120));
    }
    @Override public Quote quote(EntitlementContext ctx,SourceRef ref,ai.neargo.shop.spi.storedvalue.StoredValueWallets.Choice choice){
        enabled(ctx);var source=source(ctx,ref);var query=choice.query();var scope=query.scope();
        if(!ctx.tenantNo().equals(scope.tenantNo())||!ctx.actorNo().equals(scope.actorNo())||!source.userNo().equals(query.customer().userNo())||!source.entityNo().equals(scope.entityNo())||!source.storeNo().equals(scope.storeNo())||!source.currency().equals(scope.currency()))conflict();
        var selected=walletQueries.select(query,choice.issuerRef(),choice.accountRef());var account=accounts.findAccount(ctx.tenantNo(),selected.accountNo());
        if(account==null||!selected.customerNo().equals(account.getCustomerNo())||selected.assetRevision()!=account.getVersion())conflict();
        long amount=StoredValueUseFacts.amount(source);long applicable=0;Reason reason=Reason.FROZEN;
        if("ACTIVE".equals(account.getStatus())){applicable=policy.allocate("BRAND".equals(selected.scopeType())?lotBasis.available(ctx.tenantNo(),lots.live(ctx.tenantNo(),account.getAccountNo())):StoredValueUseFacts.available(lots.live(ctx.tenantNo(),account.getAccountNo()),json),source).amountMinor();reason=applicable==amount?Reason.OK:Reason.INSUFFICIENT_APPLICABLE_BALANCE;}
        var selection=new Selection(ref,source.orderSnapshotHash(),account.getAccountNo(),amount,account.getVersion());
        return new Quote("SQ"+UUID.randomUUID().toString().replace("-",""),ref,account.getAccountNo(),source.currency(),amount,applicable,reason==Reason.OK,reason,account.getVersion(),digest(selection,json),Instant.ofEpochMilli(System.currentTimeMillis()).plusSeconds(120),new ai.neargo.shop.spi.storedvalue.StoredValueWallets.Proof(scope,selected,source.userNo()));
    }
    @Override public HoldView prepare(EntitlementContext ctx, UseCommand c) {
        var prior = intents.find(ctx.tenantNo(), c.operationNo());
        if (prior != null) {
            if (!digest(c, json).equals(prior.getPayloadHash())) conflict(); return views.view(prior);
        }
        enabled(ctx);
        if (c.assetKind() != AssetKind.STORED_VALUE) conflict();
        var source = source(ctx, c.sourceRef());
        if (!c.userNo().equals(source.userNo()) || !c.entityNo().equals(source.entityNo())
                || !c.storeNo().equals(source.storeNo()) || !c.currency().equals(source.currency())
                || !c.operationNo().equals(source.stagedOperationNo()) || !c.itemsSnapshot().equals(source.items())
                || c.amountMinor() != StoredValueUseFacts.amount(source)) conflict();
        var owner = decisions.getIfAvailable(); if (owner == null) unavailable();
        var auth = owner.authorization(ctx, c.authorizationRef());
        var selection = new Selection(c.sourceRef(), source.orderSnapshotHash(), c.accountNo(), c.amountMinor(),
                c.expectedAssetRevision());
        String selectionHash = digest(selection, json);
        if (auth == null || auth.status() != AuthorizationState.AUTHORIZED
                || !c.authorizationRef().equals(auth.authorizationRef()) || !c.sourceRef().equals(auth.source())
                || !c.userNo().equals(auth.userNo()) || !c.entityNo().equals(auth.entityNo())
                || !c.storeNo().equals(auth.storeNo()) || !c.currency().equals(auth.currency())
                || !c.amountMinor().equals(auth.amountMinor()) || !selectionHash.equals(auth.selectionDigest())
                || !source.orderSnapshotHash().equals(auth.orderSnapshotHash())
                || !Instant.now().isBefore(auth.expiresAt())) conflict();
        return posting.prepare(ctx, new Prepared(c, source, null, auth, selectionHash, c.serviceDay(), 0,
                c.expectedAssetRevision(), List.of()));
    }
    @Override public HoldView finish(EntitlementContext ctx, String no, SourceRef source, DecisionRef ref,
                                    boolean commit) {
        var row = intents.find(ctx.tenantNo(), no);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var frozen = json.readValue(row.getCommandSnapshot(), Prepared.class); var c = frozen.command();
        if (!source.equals(c.sourceRef()) || c.assetKind() != AssetKind.STORED_VALUE) conflict();
        ref.requireFor(source, commit ? DecisionRef.DecisionType.USE_COMMIT : DecisionRef.DecisionType.USE_CANCEL);
        if ((commit ? "COMMITTED" : "RELEASED").equals(row.getStatus())) {
            if (!ref.decisionNo().equals(row.getSourceDecisionNo())) conflict(); return views.view(row);
        }
        if (!"HELD".equals(row.getStatus())) conflict();
        var owner = decisions.getIfAvailable(); if (owner == null) unavailable();
        var truth = owner.query(ctx, source); if (truth == null) unavailable();
        if (truth.status() == SourceDecisionPort.State.UNKNOWN || truth.status() == SourceDecisionPort.State.PENDING) {
            return new HoldView(no, State.PROCESSING, no, null, null);
        }
        if (!source.equals(truth.source()) || !ref.equals(truth.decision())
                || !c.userNo().equals(truth.userNo()) || !c.entityNo().equals(truth.entityNo())
                || !c.storeNo().equals(truth.storeNo()) || !c.currency().equals(truth.currency())
                || !c.amountMinor().equals(truth.amountMinor())
                || !frozen.source().orderSnapshotHash().equals(truth.snapshotHash())
                || truth.status() != (commit ? SourceDecisionPort.State.COMMITTED : SourceDecisionPort.State.CANCELLED)) {
            conflict();
        }
        return posting.finish(ctx, no, source, ref, commit, truth);
    }
    private UseSourceFact source(EntitlementContext ctx, SourceRef ref) {
        var owner = sources.getIfAvailable(); if (owner == null) unavailable();
        var source = owner.resolve(ctx, ref);
        if (source == null) unavailable();
        if (!ctx.tenantNo().equals(source.tenantNo()) || !ref.equals(source.source())
                || !"CORE".equals(ref.sourceSystem()) || !"SERVICE_USE".equals(ref.sourceType())
                || source.freightMinor() != 0 || !"CNY".equals(source.currency())) conflict();
        StoredValueUseFacts.amount(source); return source;
    }
    private void enabled(EntitlementContext ctx) {
        if (!"MAIN".equals(ctx.tenantNo())) unavailable();
        features.requireStoredValue(); features.requireUse();
    }
    private static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
